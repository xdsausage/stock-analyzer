/**
 * Plain data object holding all the metrics retrieved for a single stock.
 *
 * Fields are populated by {@link YahooFinanceFetcher#fetch(String)} and may be
 * {@code Double.NaN} when the API does not supply a value (e.g. a stock that
 * pays no dividend will have {@code dividendYield == NaN}).  Numeric fields that
 * default to {@code 0.0} instead of NaN (price, change, volume, marketCap) are
 * always present in a valid API response, so 0 is treated as "not available" for
 * display purposes only.
 */
public class StockData {

    // ---- Identity ------------------------------------------------------------

    /** Ticker symbol in upper-case, e.g. "AAPL". */
    public String symbol;

    /** Human-readable company name, e.g. "Apple Inc." */
    public String companyName;

    /** Exchange the stock is listed on, e.g. "NASDAQ" or "NYSE". */
    public String exchange;

    /** ISO 4217 currency code for all price fields, e.g. "USD". */
    public String currency;

    // ---- Price ---------------------------------------------------------------

    /** Most recent trade price. */
    public double currentPrice;

    /** Absolute price change vs. previous close (can be negative). */
    public double priceChange;

    /** Percentage change vs. previous close, e.g. 2.35 means +2.35 %. */
    public double priceChangePercent;

    // ---- Market data ---------------------------------------------------------

    /** Total market capitalisation in the stock's currency. */
    public double marketCap;

    /** Most recent session's trading volume in shares. */
    public long tradingVolume;

    /** 3-month average daily trading volume in shares. */
    public long averageDailyVolume;

    // ---- Valuation ratios ----------------------------------------------------

    /** Trailing twelve-month price-to-earnings ratio. */
    public double peRatio;

    /** Forward (estimated next-12-month) price-to-earnings ratio. */
    public double forwardPE;

    /** Trailing twelve-month earnings per share. */
    public double earningsPerShare;

    /** Price-to-book ratio. */
    public double priceToBook;

    // ---- Risk & income -------------------------------------------------------

    /**
     * Beta — measures volatility relative to the broader market.
     * A value above 1.0 means more volatile than the market.
     */
    public double beta;

    /**
     * Annual dividend yield stored as a <em>decimal fraction</em>
     * (e.g. 0.0235 means 2.35 %).  Multiply by 100 before displaying as a
     * percentage.  {@code NaN} when the stock pays no dividend.
     */
    public double dividendYield;

    // ---- Price range & moving averages ---------------------------------------

    /** Highest closing price over the trailing 52 weeks. */
    public double fiftyTwoWeekHigh;

    /** Lowest closing price over the trailing 52 weeks. */
    public double fiftyTwoWeekLow;

    /** 50-day simple moving average of closing prices. */
    public double fiftyDayMovingAverage;

    /** 200-day simple moving average of closing prices. */
    public double twoHundredDayMovingAverage;

    public StockData() {}
}
