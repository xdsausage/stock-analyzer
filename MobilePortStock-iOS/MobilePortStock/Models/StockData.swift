import Foundation

/// All metrics retrieved for a single stock quote.
///
/// Fields are populated by ``YahooFinanceService/fetch(_:)`` and use Swift
/// optionals (`nil`) where the Java original used `Double.NaN` to signal
/// missing data.
struct StockData: Codable, Identifiable, Hashable {

    // MARK: - Identity

    /// Ticker symbol in upper-case, e.g. "AAPL".
    let symbol: String

    /// Human-readable company name, e.g. "Apple Inc."
    let companyName: String

    /// Exchange the stock is listed on, e.g. "NASDAQ" or "NYSE".
    let exchange: String

    /// ISO 4217 currency code for all price fields, e.g. "USD".
    let currency: String

    // MARK: - Price

    /// Most recent trade price.
    let currentPrice: Double

    /// Absolute price change vs. previous close (can be negative).
    let priceChange: Double

    /// Percentage change vs. previous close, e.g. 2.35 means +2.35%.
    let priceChangePercent: Double

    // MARK: - Market data

    /// Total market capitalisation in the stock's currency.
    let marketCap: Double

    /// Most recent session's trading volume in shares.
    let tradingVolume: Int

    /// 3-month average daily trading volume in shares.
    let averageDailyVolume: Int

    // MARK: - Valuation ratios

    /// Trailing twelve-month price-to-earnings ratio.
    let peRatio: Double?

    /// Forward (estimated next-12-month) price-to-earnings ratio.
    let forwardPE: Double?

    /// Trailing twelve-month earnings per share.
    let earningsPerShare: Double?

    /// Price-to-book ratio.
    let priceToBook: Double?

    // MARK: - Risk & income

    /// Beta — measures volatility relative to the broader market.
    let beta: Double?

    /// Annual dividend yield as a decimal fraction (0.0235 = 2.35%).
    /// `nil` when the stock pays no dividend.
    let dividendYield: Double?

    // MARK: - Price range & moving averages

    /// Highest closing price over the trailing 52 weeks.
    let fiftyTwoWeekHigh: Double?

    /// Lowest closing price over the trailing 52 weeks.
    let fiftyTwoWeekLow: Double?

    /// 50-day simple moving average of closing prices.
    let fiftyDayMovingAverage: Double?

    /// 200-day simple moving average of closing prices.
    let twoHundredDayMovingAverage: Double?

    // MARK: - Identifiable

    var id: String { symbol }
}
