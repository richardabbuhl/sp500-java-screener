package com.sp500.demo.service;

import com.sp500.demo.model.Sp500Stock;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
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
	private static final String USER_AGENT =
			"Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
					+ "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36";
	private final HttpClient httpClient = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	public List<Sp500Stock> getStocks(Integer topN) {
		if (topN != null && topN < 1) {
			throw new IllegalArgumentException("topN must be greater than zero");
		}

		Document document;
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
					.timeout(Duration.ofSeconds(15))
					.version(HttpClient.Version.HTTP_1_1)
					.header("User-Agent", USER_AGENT)
					.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
					.header("Accept-Language", "en-US,en;q=0.9")
					.header("Referer", "https://www.google.com/")
					.GET()
					.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IOException("SlickCharts returned HTTP " + response.statusCode());
			}
			document = Jsoup.parse(response.body(), URL);
		} catch (IOException exception) {
			throw new IllegalStateException("Unable to retrieve S&P 500 data from SlickCharts", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while retrieving S&P 500 data from SlickCharts", exception);
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
