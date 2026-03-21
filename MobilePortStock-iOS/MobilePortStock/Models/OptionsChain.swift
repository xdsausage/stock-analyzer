import Foundation

/// Container for a full options chain for a given ticker and expiration.
struct OptionsChain: Codable, Hashable {
    let ticker: String
    let underlyingPrice: Double
    /// Available expiration dates as Unix epoch seconds.
    let expirationDates: [TimeInterval]
    let calls: [OptionsContract]
    let puts: [OptionsContract]
}
