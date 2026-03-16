import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class YahooFinanceFetcher {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final String BASE_URL =
            "https://query1.finance.yahoo.com/v10/finance/quoteSummary/%s" +
            "?modules=price,summaryDetail,defaultKeyStatistics";

    public static StockData fetch(String ticker) throws Exception {
        String upper = ticker.trim().toUpperCase();
        if (upper.isEmpty()) throw new IllegalArgumentException("Ticker symbol cannot be empty.");

        String url = String.format(BASE_URL, upper);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " — ticker may be invalid or not listed on NASDAQ/NYSE.");
        }

        return parse(upper, response.body());
    }

    private static StockData parse(String ticker, String body) throws IOException {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject quoteSummary = root.getAsJsonObject("quoteSummary");

        if (quoteSummary == null || !quoteSummary.has("result") || quoteSummary.get("result").isJsonNull()) {
            throw new IOException("Could not parse response. Ticker may not exist or is not on NASDAQ/NYSE.");
        }

        JsonObject result = quoteSummary.getAsJsonArray("result").get(0).getAsJsonObject();

        JsonObject price = result.getAsJsonObject("price");
        JsonObject summary = result.has("summaryDetail") ? result.getAsJsonObject("summaryDetail") : new JsonObject();
        JsonObject stats = result.has("defaultKeyStatistics") ? result.getAsJsonObject("defaultKeyStatistics") : new JsonObject();

        if (price == null) {
            throw new IOException("Could not parse response. Ticker may not exist or is not on NASDAQ/NYSE.");
        }

        StockData data = new StockData();
        data.symbol = ticker;

        // Company name
        data.companyName = getString(price, "longName");
        if (data.companyName == null) data.companyName = getString(price, "shortName");
        if (data.companyName == null) data.companyName = ticker;

        // Exchange validation — only NASDAQ and NYSE
        data.exchange = getString(price, "exchangeName");
        if (data.exchange == null) data.exchange = getString(price, "exchange");
        if (data.exchange != null) {
            String ex = data.exchange.toUpperCase();
            boolean valid = ex.contains("NASDAQ") || ex.contains("NMS") || ex.contains("NYQ")
                         || ex.contains("NYSE") || ex.contains("NGM") || ex.contains("NCM");
            if (!valid) {
                throw new IOException("Exchange \"" + data.exchange + "\" is not NASDAQ or NYSE.");
            }
        }

        data.currency = getString(price, "currency");
        data.currentPrice = getRaw(price, "regularMarketPrice", 0.0);
        data.change = getRaw(price, "regularMarketChange", 0.0);
        data.changePercent = getRaw(price, "regularMarketChangePercent", 0.0) * 100.0;
        data.marketCap = getRaw(price, "marketCap", 0.0);
        data.volume = (long) getRaw(price, "regularMarketVolume", 0.0);

        data.fiftyTwoWeekHigh = getRaw(summary, "fiftyTwoWeekHigh", 0.0);
        data.fiftyTwoWeekLow = getRaw(summary, "fiftyTwoWeekLow", 0.0);

        // P/E ratio — try trailing then forward then EPS
        Double pe = getRawOrNull(summary, "trailingPE");
        if (pe == null) pe = getRawOrNull(summary, "forwardPE");
        if (pe == null) pe = getRawOrNull(stats, "trailingEps");
        data.peRatio = pe != null ? pe : Double.NaN;

        return data;
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) return el.getAsString();
        return null;
    }

    private static double getRaw(JsonObject obj, String key, double defaultVal) {
        Double val = getRawOrNull(obj, key);
        return val != null ? val : defaultVal;
    }

    private static Double getRawOrNull(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return null;
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonObject()) {
            JsonElement raw = el.getAsJsonObject().get("raw");
            if (raw == null || raw.isJsonNull()) return null;
            try { return raw.getAsDouble(); } catch (Exception e) { return null; }
        }
        if (el.isJsonPrimitive()) {
            try { return el.getAsDouble(); } catch (Exception e) { return null; }
        }
        return null;
    }
}
