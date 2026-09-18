package com.sp500.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sp500.demo.model.ScreenerStock;
import com.sp500.demo.model.Sp500Stock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;
import org.ta4j.core.num.DoubleNumFactory;

@Service
public class Sp500ScreenerService {

	private static final String YAHOO_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
			+ "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Safari/537.36";
	private final SlickChartsService slickChartsService;
	private final RestClient client = RestClient.builder().build();
	private final ObjectMapper objectMapper = new ObjectMapper();
	private volatile Instant yahooRateLimitedUntil = Instant.EPOCH;

	public Sp500ScreenerService(SlickChartsService slickChartsService) {
		this.slickChartsService = slickChartsService;
	}

	public List<ScreenerStock> screen() {
		List<ScreenAttempt> attempts = slickChartsService.getStocks(100).parallelStream()
				.map(this::screenAttempt)
				.toList();
		List<ScreenerStock> screenedStocks = attempts.stream()
				.map(ScreenAttempt::stock)
				.filter(result -> result != null && result.currentPrice() > result.sma200())
				.toList();
		long historyFailures = attempts.stream().filter(attempt -> attempt.stock() == null).count();
		if (screenedStocks.isEmpty() && historyFailures > 0) {
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					"Yahoo Finance price history is temporarily unavailable; please retry shortly");
		}
		return screenedStocks.stream()
				.sorted(Comparator.comparingDouble(ScreenerStock::twelveMonthReturn).reversed())
				.toList();
	}

	private ScreenAttempt screenAttempt(Sp500Stock stock) {
		try {
			return new ScreenAttempt(screen(stock));
		} catch (IllegalStateException exception) {
			return new ScreenAttempt(null);
		}
	}

	private ScreenerStock screen(Sp500Stock stock) {
		List<PricePoint> points = history(stock.symbol());
		if (points.size() < 200) {
			return null;
		}
		BarSeries series = new BaseBarSeriesBuilder()
				.withName(stock.symbol())
				.withNumFactory(DoubleNumFactory.getInstance())
				.build();
		points.forEach(point -> {
			var value = DoubleNum.valueOf(point.close());
			series.addBar(new BaseBar(Duration.ofDays(1), point.time(), point.time().plus(Duration.ofDays(1)),
					value, value, value, value, DoubleNum.valueOf(0), DoubleNum.valueOf(0), 0));
		});
		ClosePriceIndicator close = new ClosePriceIndicator(series);
		SMAIndicator sma50 = new SMAIndicator(close, 50);
		SMAIndicator sma200 = new SMAIndicator(close, 200);
		int end = series.getEndIndex();
		double yearAgo = points.get(Math.max(0, points.size() - 252)).close();
		return new ScreenerStock(stock.company(), stock.symbol(), stock.weight(), close.getValue(end).doubleValue(),
				sma50.getValue(end).doubleValue(), sma200.getValue(end).doubleValue(),
				(close.getValue(end).doubleValue() - yearAgo) / yearAgo * 100);
	}

	private List<PricePoint> history(String symbol) {
		long end = Instant.now().getEpochSecond();
		long start = Instant.now().minus(400, ChronoUnit.DAYS).getEpochSecond();
		if (Instant.now().isBefore(yahooRateLimitedUntil)) {
			return nasdaqHistory(symbol, start, end);
		}
		String body;
		try {
			body = fetchHistoryWithRetry(symbol, start, end);
		} catch (YahooRateLimitException exception) {
			yahooRateLimitedUntil = Instant.now().plus(1, ChronoUnit.MINUTES);
			return nasdaqHistory(symbol, start, end);
		}
		try {
			JsonNode result = objectMapper.readTree(body).path("chart").path("result").path(0);
			JsonNode timestamps = result.path("timestamp");
			JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
			List<PricePoint> points = new ArrayList<>();
			for (int i = 0; i < timestamps.size(); i++) {
				if (!closes.get(i).isNull()) {
					points.add(new PricePoint(Instant.ofEpochSecond(timestamps.get(i).asLong()), closes.get(i).asDouble()));
				}
			}
			return points;
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to parse Yahoo history for " + symbol, exception);
		}
	}

	private List<PricePoint> nasdaqHistory(String symbol, long start, long end) {
		LocalDate startDate = Instant.ofEpochSecond(start).atZone(ZoneOffset.UTC).toLocalDate();
		LocalDate endDate = Instant.ofEpochSecond(end).atZone(ZoneOffset.UTC).toLocalDate();
		String body = client.get()
				.uri("https://api.nasdaq.com/api/quote/" + symbol + "/historical?assetclass=stocks&fromdate="
						+ startDate + "&todate=" + endDate + "&limit=5000")
				.header("User-Agent", YAHOO_USER_AGENT)
				.header("Accept", "application/json")
				.retrieve()
				.body(String.class);
		try {
			JsonNode rows = objectMapper.readTree(body).path("data").path("tradesTable").path("rows");
			List<PricePoint> points = new ArrayList<>();
			for (JsonNode row : rows) {
				String close = row.path("close").asText().replace("$", "").replace(",", "");
				if (!close.isBlank() && !row.path("date").asText().isBlank()) {
					LocalDate date = LocalDate.parse(row.path("date").asText(),
							java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"));
					points.add(new PricePoint(date.atStartOfDay().toInstant(ZoneOffset.UTC), Double.parseDouble(close)));
				}
			}
			if (points.isEmpty()) {
				throw new IllegalStateException("Nasdaq returned no price history for " + symbol);
			}
			points.sort(Comparator.comparing(PricePoint::time));
			return points;
		} catch (IOException | RuntimeException exception) {
			throw new IllegalStateException("Unable to parse Nasdaq price history for " + symbol, exception);
		}
	}

	private String fetchHistoryWithRetry(String symbol, long start, long end) {
		String uri = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
				+ "?period1=" + start + "&period2=" + end + "&interval=1d";
		HttpClientErrorException.TooManyRequests last429 = null;
		for (int attempt = 1; attempt <= 3; attempt++) {
			try {
				return client.get().uri(uri)
						.header("User-Agent", YAHOO_USER_AGENT)
						.header("Accept-Language", "en-US,en;q=0.9")
						.retrieve()
						.body(String.class);
			} catch (HttpClientErrorException.TooManyRequests exception) {
				last429 = exception;
				if (attempt < 3) {
					sleepBackoff(attempt);
				}
			}
		}
		throw new YahooRateLimitException("Yahoo Finance rate-limited price history for " + symbol, last429);
	}

	private void sleepBackoff(int attempt) {
		long delayMillis = 1_000L * attempt + ThreadLocalRandom.current().nextLong(250);
		try {
			Thread.sleep(delayMillis);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while retrying Yahoo Finance request", exception);
		}
	}

	private record PricePoint(Instant time, double close) {
	}

	private record ScreenAttempt(ScreenerStock stock) {
	}

	private static final class YahooRateLimitException extends IllegalStateException {

		private YahooRateLimitException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
