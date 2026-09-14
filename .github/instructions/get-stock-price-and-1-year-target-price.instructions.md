---
applyTo: '**'
---
Create a Java service that calls https://finance.yahoo.com/quote/{symbol}/ where symbol is a stock symbol, scrapes the stock price and 1-year target price, and returns them in JSON format.

If the application starts the endpoint should be available at `http://localhost:8080/stock/{symbol}` where `{symbol}` is the stock symbol provided in the request.






