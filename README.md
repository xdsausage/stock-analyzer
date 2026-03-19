# Stock Analyzer

A desktop application for exploring real-time stock data and price charts for
NASDAQ and NYSE listings, built with Java 17 and Swing.

---

## Features

| Feature | Description |
|---|---|
| **Live quotes** | Fetches price, daily change, market cap, P/E, EPS, beta, dividend yield, and more |
| **Interactive chart** | Zoomable price chart with volume bars and a hover crosshair/tooltip |
| **Time intervals** | Switch between 1D · 5D · 1M · 3M · 6M · 1Y |
| **Technical indicators** | Toggle MA 20, MA 50, and RSI (14-period) overlays on the chart |
| **Ticker comparison** | Overlay a second ticker on the same normalised (%) chart |
| **Watchlist** | Save tickers to a sidebar that auto-refreshes prices every 60 seconds; persists across sessions |
| **CSV export** | Export the current chart's price and volume data to a CSV file |

---

## Requirements

- **Java 17** or later
- Internet connection (data is fetched live from Yahoo Finance)
- Windows, macOS, or Linux (tested primarily on Windows 11)

---

## Running the app

The project uses the [Gradle wrapper](https://docs.gradle.org/current/userguide/gradle_wrapper.html),
so no local Gradle installation is needed.

```bash
# Clone or download the repository, then:
./gradlew run          # macOS / Linux
gradlew.bat run        # Windows
```

Or build a runnable JAR and execute it:

```bash
./gradlew jar
java -jar build/libs/TestingSomething-1.0-SNAPSHOT.jar
```

---

## How to use

### Searching for a stock

1. Type a ticker symbol in the search box (e.g. `AAPL`, `MSFT`, `NVDA`).
2. Press **Enter** or click **Analyze**.
3. The results panel appears with the current price, key metrics, and a 1-month chart.

> Only **NASDAQ** and **NYSE** listings are supported. OTC, TSX, LSE, and other
> exchanges will return an error.

### Navigating the chart

- Click an **interval button** (1D – 1Y) to change the time range.
- Hover over the chart to see a crosshair with price, date, and volume in a tooltip.
- Toggle **MA 20** or **MA 50** to overlay a simple moving average line on the chart.
- Toggle **RSI** to show a 14-period Relative Strength Index sub-panel beneath the
  volume bars. The dashed lines mark the 70 (overbought) and 30 (oversold) levels.

### Comparing two tickers

1. With a stock loaded, type a second ticker into the **"vs"** field above the chart.
2. Press **Enter** — both series are normalised to **% change from their first point**
   so they can be plotted on the same axis regardless of price differences.
3. A legend appears at the top right of the chart showing both tickers and their
   cumulative change over the selected period.
4. Click **Clear** to remove the comparison and revert to the normal price chart.

### Watchlist

- Click **★ Watchlist** in the hero card to save the current ticker.
- The sidebar on the right shows all saved tickers with their latest price and
  daily change percentage, colour-coded green (gain) or red (loss).
- Prices refresh automatically every **60 seconds** in the background.
- Click any row to load that ticker in the main panel.
- Click **×** on a row to remove the ticker from the watchlist.
- The watchlist is saved to `watchlist.dat` in the working directory and reloaded
  automatically on the next launch.

### Exporting data

- Click **↓ Export CSV** to save the current chart's data to a file.
- A save dialog opens with a suggested filename (`TICKER_RANGE.csv`, e.g. `AAPL_1mo.csv`).
- The file contains a metadata header followed by `timestamp, date, price, volume` rows.

### Starting a new search

- Click **← New** (next to the Analyze button) to hide the current results and
  return to the empty search state.

---

## Project structure

```
src/main/java/
├── Main.java               # Application entry point; builds and manages the entire UI
├── StockData.java          # Plain data object for a stock's fundamental metrics
├── ChartData.java          # Immutable container for a chart price/volume series
├── YahooFinanceFetcher.java# HTTP client; fetches quotes and chart data from Yahoo Finance
├── WatchlistManager.java   # Persistence layer for the saved-ticker watchlist
└── IndicatorCalculator.java# Static utility: Simple Moving Average and Wilder RSI algorithms
```

---

## Architecture notes

### Data flow

```
User input
  └─► triggerStockFetch()          (EDT)
        └─► SwingWorker.doInBackground()
              └─► YahooFinanceFetcher.fetch()   (background thread)
                    └─► Yahoo Finance API  (HTTPS)
        └─► SwingWorker.done()                  (EDT)
              └─► populateResultsFromStockData()
                    └─► triggerChartFetch()
                          └─► SwingWorker → YahooFinanceFetcher.fetchChart()
                                └─► chartPanel.setChartData()
```

All network calls run on background `SwingWorker` threads; the UI thread (EDT)
is only used to update labels and trigger repaints.

### Yahoo Finance authentication

Yahoo Finance's JSON API requires a **crumb token** tied to a browser session.
The fetcher performs a two-step handshake on first use:

1. Loads the Yahoo Finance homepage to receive session cookies.
2. Exchanges those cookies for a crumb token at `/v1/test/getcrumb`.

The crumb is cached and reused. A `401` response from the API automatically
triggers a fresh crumb fetch followed by one retry.

> **Note:** This uses unofficial, reverse-engineered Yahoo Finance endpoints.
> Yahoo does not provide a public API and these endpoints may change or break
> without notice.

### Technical indicators

Both indicators in `IndicatorCalculator` operate on the closing-price array
returned by the chart API:

- **SMA (Moving Average):** O(n) sliding window — running sum updated by one
  addition and one subtraction per step.
- **RSI:** Wilder's exponential smoothing — seeded over the first `period`
  price changes, then smoothed forward. Returns values in [0, 100]; values
  below 30 are conventionally oversold, above 70 are overbought.

---

## Dependencies

| Library | Version | Purpose |
|---|---|---|
| [Gson](https://github.com/google/gson) | 2.10.1 | Parsing Yahoo Finance JSON responses |

All other functionality uses the Java standard library (Swing, `java.net.http`,
`java.io`, etc.).

---

## Known limitations

- Only **NASDAQ and NYSE** listings are supported.
- Relies on **unofficial Yahoo Finance endpoints** that may break without warning.
- No request rate limiting — searching too rapidly may trigger temporary blocks
  from the Yahoo Finance API.
- The crumb token is not synchronised across threads; concurrent requests (unlikely
  in normal use) could race on a crumb refresh.
