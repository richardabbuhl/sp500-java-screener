---
applyTo: '**'
---
Create a Java service that calls https://www.slickcharts.com/sp500 and returns S&P 500 stocks with the fields: Company, Symbol, Weight, Price, Chg, and % Chg.

Support an option to return either all stocks or only the top N stocks.

If the top N option is selected, the service should accept a parameter for N and return only the top N stocks by weight.

If the application starts the endpoint should be available at `http://localhost:8080/sp500-stocks` and should return all stocks by default.


