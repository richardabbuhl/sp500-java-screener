package com.sp500.demo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sp500.demo.model.ScreenerStock;
import com.sp500.demo.model.Sp500Stock;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.ta4j.core.BarSeries;
import org.ta4j.core.BaseBar;
import org.ta4j.core.BaseBarSeriesBuilder;
import org.ta4j.core.indicators.averages.SMAIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DoubleNum;

@Service
public class Sp500ScreenerService {

	private final SlickChartsService slickChartsService;
	private final RestClient client = RestClient.builder().build();
	private final ObjectMapper objectMapper = new ObjectMapper();

	public Sp500ScreenerService(SlickChartsService slickChartsService) {
		this.slickChartsService = slickChartsService;
	}

	public List<ScreenerStock> screen() {
		return slickChartsService.getStocks(100).stream()
				.map(this::screen)
				.filter(result -> result != null && result.currentPrice() > result.sma200())
				.sorted(Comparator.comparingDouble(ScreenerStock::twelveMonthReturn).reversed())
				.toList();
	}

	private ScreenerStock screen(Sp500Stock stock) {
		try {
			List<PricePoint> points = history(stock.symbol());
			if (points.size() < 200) return null;
			BarSeries series = new BaseBarSeriesBuilder().withName(stock.symbol()).build();
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
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private List<PricePoint> history(String symbol) {
		long end = Instant.now().getEpochSecond();
		long start = Instant.now().minus(400, ChronoUnit.DAYS).getEpochSecond();
		String body = client.get().uri("https://query1.finance.yahoo.com/v8/finance/chart/" + symbol
				+ "?period1=" + start + "&period2=" + end + "&interval=1d")
				.retrieve().body(String.class);
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

	private record PricePoint(Instant time, double close) {
	}
}
