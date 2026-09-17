package com.sp500.demo.service;

import com.sp500.demo.model.Sp500Stock;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

@Service
public class SlickChartsService {

	private static final String URL = "https://www.slickcharts.com/sp500";

	public List<Sp500Stock> getStocks(Integer topN) {
		if (topN != null && topN < 1) {
			throw new IllegalArgumentException("topN must be greater than zero");
		}

		Document document;
		try {
			document = Jsoup.connect(URL)
					.userAgent("Mozilla/5.0")
					.timeout(15_000)
					.get();
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to retrieve S&P 500 data from SlickCharts", exception);
		}

		Element table = document.select("table").stream()
				.filter(candidate -> candidate.select("tr").stream()
						.anyMatch(row -> row.text().toLowerCase(Locale.ROOT).contains("symbol")))
				.findFirst()
				.orElseThrow(() -> new IllegalStateException("SlickCharts response did not contain an S&P 500 table"));

		Elements rows = table.select("tbody tr");
		if (rows.isEmpty()) {
			rows = table.select("tr:has(td)");
		}
		List<Sp500Stock> stocks = rows.stream()
				.map(this::parseRow)
				.filter(Objects::nonNull)
				.sorted(Comparator.comparingDouble(Sp500Stock::weight).reversed())
				.toList();

		return topN == null ? stocks : stocks.stream().limit(topN).toList();
	}

	private Sp500Stock parseRow(Element row) {
		Elements cells = row.select("td");
		if (cells.size() < 6) {
			return null;
		}
		try {
			String company = cells.get(1).text().trim();
			String symbol = cells.get(2).text().trim();
			return new Sp500Stock(company, symbol, number(cells.get(3).text()), number(cells.get(4).text()),
					number(cells.get(5).text()), number(cells.size() > 6 ? cells.get(6).text() : "0"));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private double number(String value) {
		String normalized = value.replace(",", "").replace("%", "").replace("$", "").trim();
		return Double.parseDouble(normalized.replace("−", "-").replace("—", "0"));
	}
}
