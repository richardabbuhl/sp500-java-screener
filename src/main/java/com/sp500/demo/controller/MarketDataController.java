package com.sp500.demo.controller;

import com.sp500.demo.model.ScreenerStock;
import com.sp500.demo.model.Sp500Stock;
import com.sp500.demo.model.StockQuote;
import com.sp500.demo.service.SlickChartsService;
import com.sp500.demo.service.Sp500ScreenerService;
import com.sp500.demo.service.YahooFinanceService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MarketDataController {

	private final SlickChartsService slickChartsService;
	private final YahooFinanceService yahooFinanceService;
	private final Sp500ScreenerService screenerService;

	public MarketDataController(SlickChartsService slickChartsService, YahooFinanceService yahooFinanceService,
			Sp500ScreenerService screenerService) {
		this.slickChartsService = slickChartsService;
		this.yahooFinanceService = yahooFinanceService;
		this.screenerService = screenerService;
	}

	@GetMapping("/sp500-stocks")
	public List<Sp500Stock> stocks(@RequestParam(required = false) Integer topN) {
		return slickChartsService.getStocks(topN);
	}

	@GetMapping("/stock/{symbol}")
	public StockQuote quote(@PathVariable String symbol) {
		return yahooFinanceService.getQuote(symbol);
	}

	@GetMapping("/screener")
	public List<ScreenerStock> screen() {
		return screenerService.screen();
	}
}
