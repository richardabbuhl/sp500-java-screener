package com.sp500.demo.model;

public record ScreenerStock(
		String company,
		String symbol,
		double weight,
		double currentPrice,
		double sma50,
		double sma200,
		double twelveMonthReturn) {
}
