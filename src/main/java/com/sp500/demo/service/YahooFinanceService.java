package com.sp500.demo.service;

import com.sp500.demo.model.StockQuote;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class YahooFinanceService {

	private static final Pattern TARGET_PRICE = Pattern.compile("\"targetMeanPrice\"\\s*:\\s*\\{\\s*\"raw\"\\s*:\\s*([0-9.]+)");
	private static final Pattern CURRENT_PRICE = Pattern.compile("\"regularMarketPrice\"\\s*:\\s*\\{\\s*\"raw\"\\s*:\\s*([0-9.]+)");
	private final RestClient client = RestClient.builder().build();

	public StockQuote getQuote(String symbol) {
		String normalized = normalizeSymbol(symbol);
		String encoded = URLEncoder.encode(normalized, StandardCharsets.UTF_8);
		String page = client.get().uri("https://finance.yahoo.com/quote/" + encoded + "/")
				.retrieve().body(String.class);
		if (page == null || page.isBlank()) {
			throw new IllegalStateException("Yahoo Finance returned an empty response for " + normalized);
		}
		Matcher priceMatcher = CURRENT_PRICE.matcher(page);
		if (!priceMatcher.find()) {
			throw new IllegalStateException("Yahoo Finance response did not contain a current price for " + normalized);
		}
		Matcher targetMatcher = TARGET_PRICE.matcher(page);
		Double target = targetMatcher.find() ? Double.valueOf(targetMatcher.group(1)) : null;
		return new StockQuote(normalized, Double.valueOf(priceMatcher.group(1)), target);
	}

	private String normalizeSymbol(String symbol) {
		if (symbol == null || !symbol.matches("[A-Za-z0-9.-]{1,10}")) {
			throw new IllegalArgumentException("symbol must contain 1-10 letters, digits, dots, or hyphens");
		}
		return symbol.toUpperCase();
	}
}
