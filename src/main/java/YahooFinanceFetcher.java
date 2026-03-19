import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches stock data and price charts from the unofficial Yahoo Finance API.
 *
 * <h3>Authentication — the "crumb" mechanism</h3>
 * Yahoo Finance protects its JSON endpoints with a session-based anti-bot token
 * called a "crumb".  To obtain one the client must:
 * <ol>
 *   <li>Load the Yahoo Finance homepage so the server sets session cookies.</li>
 *   <li>Request {@code /v1/test/getcrumb} — the server validates the cookies
 *       and returns a short opaque string.</li>
 *   <li>Append {@code &crumb=<token>} to every subsequent API URL.</li>
 * </ol>
 * The crumb is cached after the first successful fetch.  If the server later
 * returns HTTP 401 (session expired) the crumb is refreshed automatically and
 * the request is retried once.
 *
 * <h3>Exchange restriction</h3>
 * Only NASDAQ and NYSE listings are accepted.  Tickers from other exchanges
 * (OTC, TSX, LSE, etc.) are rejected with a descriptive error message.
 *
 * <h3>Thread safety</h3>
 * {@link #sessionCrumb} is a shared mutable field accessed from multiple
 * threads.  The current implementation is not synchronised; in practice the
 * app only fires one request at a time from a single SwingWorker so this is
 * safe, but it would need a lock in a multi-threaded context.
 */
public class YahooFinanceFetcher {

    // =========================================================================
    // HTTP infrastructure
    // =========================================================================

    /**
     * Shared cookie jar so session cookies set by the homepage request are
     * automatically included in all subsequent API requests.
     */
    private static final CookieManager COOKIE_JAR =
            new CookieManager(null, CookiePolicy.ACCEPT_ALL);

    /** Shared, reusable HTTP client. Follows redirects and uses the shared cookie jar. */
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .cookieHandler(COOKIE_JAR)
            .build();

    /**
     * User-Agent string that mimics a real Chrome browser.
     * Yahoo Finance rejects requests that look like automated tools.
     */
    private static final String BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36";

    // =========================================================================
    // API endpoint templates
    // =========================================================================

    /** Yahoo Finance homepage — visited once per session to establish cookies. */
    private static final String YAHOO_HOMEPAGE   = "https://finance.yahoo.com/";

    /** Returns the crumb token as plain text once a valid session cookie is set. */
    private static final String CRUMB_ENDPOINT   = "https://query1.finance.yahoo.com/v1/test/getcrumb";

    /**
     * quoteSummary endpoint — returns comprehensive fundamental data.
     * The {@code modules} parameter selects which data sets to include.
     * Format args: (1) ticker symbol, (2) crumb token.
     */
    private static final String QUOTE_SUMMARY_URL =
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%s" +
            "?modules=price,summaryDetail,defaultKeyStatistics&crumb=%s";

    /**
     * chart endpoint — returns OHLC price bars and volume for a given interval/range.
     * Format args: (1) ticker, (2) bar interval e.g. "1d", (3) range e.g. "1mo", (4) crumb.
     */
    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/%s" +
            "?interval=%s&range=%s&crumb=%s";

    /**
     * Yahoo Finance search endpoint — returns news articles matching a query.
     * Format arg: (1) URL-encoded ticker symbol.
     * {@code quotesCount=0} suppresses quote results so only news is returned.
     */
    private static final String NEWS_SEARCH_URL =
            "https://query1.finance.yahoo.com/v1/finance/search" +
            "?q=%s&newsCount=8&quotesCount=0";

    // =========================================================================
    // Session state
    // =========================================================================

    /**
     * Cached crumb token.  {@code null} until the first session is established.
     * Re-fetched automatically when the server returns HTTP 401.
     */
    private static String sessionCrumb = null;

    // =========================================================================
    // Public fetch methods
    // =========================================================================

    /**
     * Fetches comprehensive stock data for {@code ticker} and returns a
     * populated {@link StockData} object.
     *
     * <p>Only NASDAQ and NYSE listings are accepted; an {@link IOException} is
     * thrown for any other exchange.
     *
     * @param ticker stock ticker symbol (case-insensitive, e.g. "aapl" or "AAPL")
     * @throws IllegalArgumentException if {@code ticker} is blank
     * @throws IOException if the network request fails, the ticker doesn't exist,
     *                     or the listing is not on NASDAQ/NYSE
     */
    public static StockData fetch(String ticker) throws Exception {
        String upperTicker = ticker.trim().toUpperCase();
        if (upperTicker.isEmpty()) {
            throw new IllegalArgumentException("Ticker symbol cannot be empty.");
        }

        // Ensure we have a valid session crumb before making the real request
        if (sessionCrumb == null) {
            sessionCrumb = fetchNewCrumb();
        }

        HttpResponse<String> response =
                sendGetRequest(String.format(QUOTE_SUMMARY_URL, upperTicker, sessionCrumb));

        // 401 means our crumb/session expired — refresh and retry once
        if (response.statusCode() == 401) {
            sessionCrumb = fetchNewCrumb();
            response = sendGetRequest(String.format(QUOTE_SUMMARY_URL, upperTicker, sessionCrumb));
        }

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode()
                    + " — ticker may be invalid or not listed on NASDAQ/NYSE.");
        }

        return parseStockData(upperTicker, response.body());
    }

    /**
     * Fetches OHLC chart bars for {@code ticker} at the requested granularity
     * and returns a {@link ChartData} object.
     *
     * @param ticker    upper-case ticker symbol
     * @param barInterval Yahoo Finance interval code, e.g. {@code "5m"}, {@code "1d"}
     * @param timeRange   Yahoo Finance range code, e.g. {@code "1d"}, {@code "1y"}
     * @throws IOException if the network request fails or no chart data is available
     */
    public static ChartData fetchChart(String ticker, String barInterval,
                                       String timeRange) throws Exception {
        if (sessionCrumb == null) {
            sessionCrumb = fetchNewCrumb();
        }

        String chartUrl = String.format(CHART_URL, ticker, barInterval, timeRange, sessionCrumb);
        HttpResponse<String> response = sendGetRequest(chartUrl);

        // 401 — refresh crumb and retry
        if (response.statusCode() == 401) {
            sessionCrumb = fetchNewCrumb();
            chartUrl  = String.format(CHART_URL, ticker, barInterval, timeRange, sessionCrumb);
            response  = sendGetRequest(chartUrl);
        }

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return parseChartData(response.body());
    }

    /**
     * Fetches up to 8 recent news articles for {@code ticker} and returns them
     * as a list of {@link NewsItem} records.
     *
     * <p>Never throws — returns an empty list on any error (network failure,
     * parse error, empty response) so callers can display a graceful fallback.
     *
     * @param ticker stock ticker symbol (case-insensitive)
     * @return list of news items, possibly empty; never {@code null}
     */
    public static List<NewsItem> fetchNews(String ticker) {
        try {
            String url = String.format(NEWS_SEARCH_URL,
                    java.net.URLEncoder.encode(ticker.toUpperCase(),
                            java.nio.charset.StandardCharsets.UTF_8));
            HttpResponse<String> response = sendGetRequest(url);
            if (response.statusCode() != 200) return new ArrayList<>();
            return parseNewsItems(response.body());
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    /**
     * Parses the JSON body of a {@code /v1/finance/search} response and
     * extracts the {@code news} array into a list of {@link NewsItem} records.
     *
     * <p>Expected structure:
     * <pre>
     * { "news": [ {
     *     "title": "...",
     *     "publisher": "...",
     *     "link": "https://...",
     *     "providerPublishTime": 1700000000
     * }, ... ] }
     * </pre>
     */
    private static List<NewsItem> parseNewsItems(String body) {
        List<NewsItem> items = new ArrayList<>();
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray newsArray = root.getAsJsonArray("news");
            if (newsArray == null) return items;
            for (JsonElement element : newsArray) {
                JsonObject article = element.getAsJsonObject();
                String title     = extractString(article, "title");
                String publisher = extractString(article, "publisher");
                String url       = extractString(article, "link");
                long   published = article.has("providerPublishTime")
                        && !article.get("providerPublishTime").isJsonNull()
                        ? article.get("providerPublishTime").getAsLong() : 0L;
                if (title != null && url != null) {
                    items.add(new NewsItem(
                            title,
                            publisher != null ? publisher : "Unknown",
                            url,
                            published));
                }
            }
        } catch (Exception ignored) { /* return whatever was collected */ }
        return items;
    }

    // =========================================================================
    // Session / crumb management
    // =========================================================================

    /**
     * Performs the two-step session handshake with Yahoo Finance and returns a
     * fresh crumb token.
     *
     * <p>Step 1 — visit the homepage so the server sets session cookies in
     * {@link #COOKIE_JAR}.  Step 2 — request the crumb endpoint; the server
     * validates the cookies and responds with the token as plain text.
     *
     * @throws IOException if either HTTP step fails or the crumb response is empty
     */
    private static String fetchNewCrumb() throws Exception {
        // Step 1: visit the homepage to receive session cookies
        HttpRequest homepageRequest = HttpRequest.newBuilder()
                .uri(URI.create(YAHOO_HOMEPAGE))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .GET()
                .build();
        HTTP_CLIENT.send(homepageRequest, HttpResponse.BodyHandlers.discarding());

        // Step 2: exchange the session cookies for a crumb token
        HttpRequest crumbRequest = HttpRequest.newBuilder()
                .uri(URI.create(CRUMB_ENDPOINT))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "*/*")
                .GET()
                .build();

        HttpResponse<String> crumbResponse =
                HTTP_CLIENT.send(crumbRequest, HttpResponse.BodyHandlers.ofString());

        if (crumbResponse.statusCode() != 200
                || crumbResponse.body() == null
                || crumbResponse.body().isBlank()) {
            throw new IOException(
                    "Failed to authenticate with Yahoo Finance (HTTP "
                    + crumbResponse.statusCode() + "). Try again later.");
        }

        return crumbResponse.body().trim();
    }

    // =========================================================================
    // HTTP helper
    // =========================================================================

    /**
     * Sends a GET request to {@code url} with browser-like headers and returns
     * the full response (status code + body).
     */
    private static HttpResponse<String> sendGetRequest(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", BROWSER_USER_AGENT)
                .header("Accept", "application/json")
                .GET()
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // =========================================================================
    // JSON parsing — chart data
    // =========================================================================

    /**
     * Parses the JSON body of a {@code /v8/finance/chart} response into a
     * {@link ChartData} object.
     *
     * <p>The response has the structure:
     * <pre>
     * { "chart": { "result": [ {
     *     "timestamp": [ ... ],
     *     "indicators": { "quote": [ { "close": [...], "volume": [...] } ] }
     * } ] } }
     * </pre>
     * Bars where {@code close} is {@code null} (API gap-fills) are skipped so
     * that the resulting arrays contain only valid data points.
     *
     * @throws IOException if the expected JSON structure is missing
     */
    private static ChartData parseChartData(String responseBody) throws IOException {
        JsonObject root         = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject chartSection = root.getAsJsonObject("chart");

        if (chartSection == null
                || !chartSection.has("result")
                || chartSection.get("result").isJsonNull()) {
            throw new IOException("No chart data available.");
        }

        JsonObject firstResult  = chartSection.getAsJsonArray("result").get(0).getAsJsonObject();
        JsonArray  rawTimestamps = firstResult.getAsJsonArray("timestamp");

        // Navigate to the quote indicators sub-object which holds price/volume arrays
        JsonObject quoteIndicators = firstResult
                .getAsJsonObject("indicators")
                .getAsJsonArray("quote").get(0).getAsJsonObject();

        JsonArray closingPrices = quoteIndicators.getAsJsonArray("close");
        JsonArray tradingVolumes = quoteIndicators.has("volume")
                ? quoteIndicators.getAsJsonArray("volume")
                : null;

        if (rawTimestamps == null || closingPrices == null) {
            throw new IOException("No chart data available.");
        }

        // Build parallel lists, skipping bars where the API returned null close prices
        List<Long>   validTimestamps = new ArrayList<>();
        List<Double> validPrices     = new ArrayList<>();
        List<Long>   validVolumes    = new ArrayList<>();

        for (int i = 0; i < rawTimestamps.size() && i < closingPrices.size(); i++) {
            JsonElement priceElement = closingPrices.get(i);
            if (priceElement.isJsonNull()) continue; // skip gap-fill bar

            validTimestamps.add(rawTimestamps.get(i).getAsLong());
            validPrices.add(priceElement.getAsDouble());

            long volume = 0;
            if (tradingVolumes != null && i < tradingVolumes.size()) {
                JsonElement volumeElement = tradingVolumes.get(i);
                if (!volumeElement.isJsonNull()) volume = volumeElement.getAsLong();
            }
            validVolumes.add(volume);
        }

        // Convert lists to primitive arrays
        int barCount = validTimestamps.size();
        long[]   timestampArray = new long[barCount];
        double[] priceArray     = new double[barCount];
        long[]   volumeArray    = new long[barCount];
        for (int i = 0; i < barCount; i++) {
            timestampArray[i] = validTimestamps.get(i);
            priceArray[i]     = validPrices.get(i);
            volumeArray[i]    = validVolumes.get(i);
        }
        return new ChartData(timestampArray, priceArray, volumeArray);
    }

    // =========================================================================
    // JSON parsing — stock fundamental data
    // =========================================================================

    /**
     * Parses the JSON body of a {@code /v10/finance/quoteSummary} response into
     * a {@link StockData} object.
     *
     * <p>The response bundles three modules:
     * <ul>
     *   <li>{@code price} — current price, change, market cap, volume, company name</li>
     *   <li>{@code summaryDetail} — P/E ratios, 52-week range, moving averages,
     *       dividend yield, average volume</li>
     *   <li>{@code defaultKeyStatistics} — EPS, beta, price-to-book, forward P/E</li>
     * </ul>
     * Some fields appear in multiple modules with different levels of precision;
     * the code tries the most specific source first and falls back to the next.
     *
     * <p>Most numeric values in this API are wrapped objects like
     * {@code {"raw": 173.5, "fmt": "173.50"}} — {@link #extractRawDouble} handles
     * both that wrapper and plain JSON numbers.
     *
     * @throws IOException if the response cannot be parsed or the exchange is not
     *                     NASDAQ/NYSE
     */
    private static StockData parseStockData(String ticker,
                                            String responseBody) throws IOException {
        JsonObject root         = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonObject quoteSummary = root.getAsJsonObject("quoteSummary");

        if (quoteSummary == null
                || !quoteSummary.has("result")
                || quoteSummary.get("result").isJsonNull()) {
            throw new IOException(
                    "Could not parse response. Ticker may not exist or is not on NASDAQ/NYSE.");
        }

        JsonObject firstResult = quoteSummary.getAsJsonArray("result").get(0).getAsJsonObject();

        // Each module is an object inside the result; fall back to empty objects
        // so later get-or-null calls don't need null checks on the module itself.
        JsonObject priceModule   = firstResult.getAsJsonObject("price");
        JsonObject summaryModule = firstResult.has("summaryDetail")
                ? firstResult.getAsJsonObject("summaryDetail") : new JsonObject();
        JsonObject statsModule   = firstResult.has("defaultKeyStatistics")
                ? firstResult.getAsJsonObject("defaultKeyStatistics") : new JsonObject();

        if (priceModule == null) {
            throw new IOException(
                    "Could not parse response. Ticker may not exist or is not on NASDAQ/NYSE.");
        }

        StockData stockData = new StockData();
        stockData.symbol = ticker;

        // --- Company name (try long name first, then short name, then ticker) ---
        stockData.companyName = extractString(priceModule, "longName");
        if (stockData.companyName == null)
            stockData.companyName = extractString(priceModule, "shortName");
        if (stockData.companyName == null)
            stockData.companyName = ticker;

        // --- Exchange validation: only NASDAQ and NYSE are accepted ---
        stockData.exchange = extractString(priceModule, "exchangeName");
        if (stockData.exchange == null)
            stockData.exchange = extractString(priceModule, "exchange");

        if (stockData.exchange != null) {
            String upperExchange = stockData.exchange.toUpperCase();
            // Known exchange codes used by Yahoo Finance for NASDAQ and NYSE listings
            boolean isAcceptedExchange =
                    upperExchange.contains("NASDAQ") || upperExchange.contains("NMS")
                    || upperExchange.contains("NYQ")  || upperExchange.contains("NYSE")
                    || upperExchange.contains("NGM")  || upperExchange.contains("NCM");
            if (!isAcceptedExchange) {
                throw new IOException(
                        "Exchange \"" + stockData.exchange + "\" is not NASDAQ or NYSE.");
            }
        }

        stockData.currency = extractString(priceModule, "currency");

        // --- Current price and daily change ---
        stockData.currentPrice       = extractRawDouble(priceModule, "regularMarketPrice",  0.0);
        stockData.priceChange        = extractRawDouble(priceModule, "regularMarketChange", 0.0);
        // API returns change percent as a decimal (e.g. 0.0235); multiply by 100 for display
        stockData.priceChangePercent = extractRawDouble(priceModule, "regularMarketChangePercent", 0.0) * 100.0;

        stockData.marketCap      = extractRawDouble(priceModule, "marketCap",          0.0);
        stockData.tradingVolume  = (long) extractRawDouble(priceModule, "regularMarketVolume", 0.0);

        // --- 52-week range ---
        stockData.fiftyTwoWeekHigh = extractRawDouble(summaryModule, "fiftyTwoWeekHigh", 0.0);
        stockData.fiftyTwoWeekLow  = extractRawDouble(summaryModule, "fiftyTwoWeekLow",  0.0);

        // --- P/E ratios (try summaryDetail first, fall back to defaultKeyStatistics) ---
        Double trailingPE = extractRawDoubleOrNull(summaryModule, "trailingPE");
        if (trailingPE == null) trailingPE = extractRawDoubleOrNull(statsModule, "trailingPE");
        stockData.peRatio = trailingPE != null ? trailingPE : Double.NaN;

        Double forwardPE = extractRawDoubleOrNull(summaryModule, "forwardPE");
        if (forwardPE == null) forwardPE = extractRawDoubleOrNull(statsModule, "forwardPE");
        stockData.forwardPE = forwardPE != null ? forwardPE : Double.NaN;

        // --- EPS, beta, price-to-book (only in defaultKeyStatistics) ---
        Double eps = extractRawDoubleOrNull(statsModule, "trailingEps");
        stockData.earningsPerShare = eps != null ? eps : Double.NaN;

        Double beta = extractRawDoubleOrNull(statsModule, "beta");
        stockData.beta = beta != null ? beta : Double.NaN;

        Double priceToBook = extractRawDoubleOrNull(statsModule, "priceToBook");
        stockData.priceToBook = priceToBook != null ? priceToBook : Double.NaN;

        // --- Dividend yield (stored as decimal fraction by the API, e.g. 0.0235) ---
        Double dividendYield = extractRawDoubleOrNull(summaryModule, "dividendYield");
        stockData.dividendYield = dividendYield != null ? dividendYield : Double.NaN;

        // --- Moving averages ---
        Double ma50 = extractRawDoubleOrNull(summaryModule, "fiftyDayAverage");
        stockData.fiftyDayMovingAverage = ma50 != null ? ma50 : Double.NaN;

        Double ma200 = extractRawDoubleOrNull(summaryModule, "twoHundredDayAverage");
        stockData.twoHundredDayMovingAverage = ma200 != null ? ma200 : Double.NaN;

        // --- Average daily volume ---
        Double avgVol = extractRawDoubleOrNull(summaryModule, "averageVolume");
        stockData.averageDailyVolume = avgVol != null ? avgVol.longValue() : 0L;

        return stockData;
    }

    // =========================================================================
    // JSON helper utilities
    // =========================================================================

    /**
     * Extracts a plain string value from a JSON object, or {@code null} if the
     * key is absent, null, or not a primitive.
     */
    private static String extractString(JsonObject obj, String key) {
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) return null;
        if (element.isJsonPrimitive()) return element.getAsString();
        return null;
    }

    /**
     * Extracts a numeric value from a JSON object and returns it as a
     * {@code double}, falling back to {@code defaultValue} when absent.
     *
     * <p>Handles both plain JSON numbers and the Yahoo Finance wrapper format
     * {@code {"raw": 173.5, "fmt": "173.50"}}.
     */
    private static double extractRawDouble(JsonObject obj, String key, double defaultValue) {
        Double value = extractRawDoubleOrNull(obj, key);
        return value != null ? value : defaultValue;
    }

    /**
     * Like {@link #extractRawDouble} but returns {@code null} instead of a
     * default so callers can distinguish "missing" from "zero".
     */
    private static Double extractRawDoubleOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement element = obj.get(key);
        if (element == null || element.isJsonNull()) return null;

        // Unwrap {"raw": ..., "fmt": ...} objects
        if (element.isJsonObject()) {
            JsonElement rawField = element.getAsJsonObject().get("raw");
            if (rawField == null || rawField.isJsonNull()) return null;
            try { return rawField.getAsDouble(); } catch (Exception e) { return null; }
        }

        // Plain numeric value
        if (element.isJsonPrimitive()) {
            try { return element.getAsDouble(); } catch (Exception e) { return null; }
        }

        return null;
    }
}
