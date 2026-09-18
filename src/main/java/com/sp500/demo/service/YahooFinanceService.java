package com.sp500.demo.service;

import com.sp500.demo.model.StockQuote;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.num.DoubleNum;

@Service
public class YahooFinanceService {

	private final RestClient client = RestClient.builder().build();

	public StockQuote getQuote(String symbol) {
		String normalized = normalizeSymbol(symbol);
		List<PricePoint> points = history(normalized);
		if (points.size() < 2) {
			throw new IllegalStateException("Not enough history returned for " + normalized);
		}
		BarSeries series = new BaseBarSeriesBuilder().withName(normalized).build();
		for (PricePoint point : points) {
			var value = DoubleNum.valueOf(point.close());
			series.addBar(new BaseBar(Duration.ofDays(1), point.time(), point.time().plus(Duration.ofDays(1)),
					value, value, value, value, DoubleNum.valueOf(0), DoubleNum.valueOf(0), 0));
		}
		double currentPrice = series.getLastBar().getClosePrice().doubleValue();
		double firstClose = series.getFirstBar().getClosePrice().doubleValue();
		double annualizedReturn = Math.pow(currentPrice / firstClose, 252.0 / Math.max(1, series.getBarCount() - 1)) - 1;
		double targetPrice = currentPrice * (1 + annualizedReturn);
		return new StockQuote(normalized, currentPrice, targetPrice);
	}

	private String normalizeSymbol(String symbol) {
		if (symbol == null || !symbol.matches("[A-Za-z0-9.-]{1,10}")) {
			throw new IllegalArgumentException("symbol must contain 1-10 letters, digits, dots, or hyphens");
		}
		return symbol.toUpperCase();
	}

	private List<PricePoint> history(String symbol) {
		long end = Instant.now().getEpochSecond();
		long start = Instant.now().minus(400, ChronoUnit.DAYS).getEpochSecond();
		String body = fetchHistoryWithRetry(symbol, start, end);
		if (body == null || body.isBlank()) {
			throw new IllegalStateException("Yahoo history returned an empty response for " + symbol);
		}
		try {
			var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			var result = mapper.readTree(body).path("chart").path("result").path(0);
			var timestamps = result.path("timestamp");
			var closes = result.path("indicators").path("quote").path(0).path("close");
			List<PricePoint> points = new ArrayList<>();
			for (int i = 0; i < timestamps.size(); i++) {
				if (!closes.get(i).isNull()) {
					points.add(new PricePoint(Instant.ofEpochSecond(timestamps.get(i).asLong()), closes.get(i).asDouble()));
				}
			}
			return points;
		} catch (Exception exception) {
			throw new IllegalStateException("Unable to parse Yahoo history for " + symbol, exception);
		}
	}

	private String fetchHistoryWithRetry(String symbol, long start, long end) {
		String uri = "https://query1.finance.yahoo.com/v8/finance/chart/" + symbol + "?period1=" + start + "&period2="
				+ end + "&interval=1d";
		HttpClientErrorException.TooManyRequests last429 = null;
		for (int attempt = 1; attempt <= 4; attempt++) {
			try {
				return client.get().uri(uri).retrieve().body(String.class);
			} catch (HttpClientErrorException.TooManyRequests tooManyRequests) {
				last429 = tooManyRequests;
				if (attempt == 4) {
					break;
				}
				sleepBackoff(attempt);
			}
		}
		throw new IllegalStateException("Yahoo API rate-limited symbol " + symbol + " after retries", last429);
	}

	private void sleepBackoff(int attempt) {
		long baseDelayMs = 400L * (1L << (attempt - 1));
		long jitterMs = ThreadLocalRandom.current().nextLong(0, 250);
		try {
			Thread.sleep(baseDelayMs + jitterMs);
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting to retry Yahoo API request",
					interruptedException);
		}
	}

	private record PricePoint(Instant time, double close) {
	}
}
