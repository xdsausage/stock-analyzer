/**
 * Immutable row model used by the Screener tab.
 *
 * <p>Fields mirror the subset of quote data needed for filtering and table
 * display. Numeric values may be {@code Double.NaN} or {@code 0} when Yahoo
 * does not provide the field for a particular security.
 */
public record ScreenerStock(
        String symbol,
        String name,
        String exchange,
        String currency,
        double price,
        double changePercent,
        double marketCap,
        long volume,
        double peRatio,
        double beta,
        double dividendYield
) {}
