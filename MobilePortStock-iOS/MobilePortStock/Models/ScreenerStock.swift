import Foundation

/// Row model used by the Screener tab.
///
/// Fields mirror the subset of quote data needed for filtering and table
/// display. Numeric values use optionals where Yahoo may not provide the field.
struct ScreenerStock: Codable, Identifiable, Hashable {
    let symbol: String
    let name: String
    let exchange: String
    let currency: String
    let price: Double
    let changePercent: Double
    let marketCap: Double
    let volume: Int
    let peRatio: Double?
    let beta: Double?
    let dividendYield: Double?

    var id: String { symbol }
}
