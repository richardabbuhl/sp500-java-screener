package com.sp500.demo.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Sp500Stock(
		@JsonProperty("Company") String company,
		@JsonProperty("Symbol") String symbol,
		@JsonProperty("Weight") double weight,
		@JsonProperty("Price") double price,
		@JsonProperty("Chg") double change,
		@JsonProperty("% Chg") double percentChange) {
}
