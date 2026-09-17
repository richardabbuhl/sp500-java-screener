package com.sp500.demo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record StockQuote(
		@JsonProperty("symbol") String symbol,
		@JsonProperty("price") double price,
		@JsonProperty("oneYearTargetPrice") Double oneYearTargetPrice) {
}
