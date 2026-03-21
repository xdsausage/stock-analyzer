import Foundation

/// A single options contract from the options chain.
struct OptionsContract: Codable, Identifiable, Hashable {
    let contractSymbol: String
    let strike: Double
    let lastPrice: Double
    let bid: Double
    let ask: Double
    let change: Double
    let changePercent: Double
    let volume: Int
    let openInterest: Int
    let impliedVolatility: Double
    let inTheMoney: Bool
    /// Unix epoch seconds of the contract expiration.
    let expiration: TimeInterval

    var id: String { contractSymbol }

    /// Midpoint between bid and ask.
    var midpoint: Double {
        guard bid > 0, ask > 0 else { return lastPrice }
        return (bid + ask) / 2.0
    }

    /// Bid-ask spread.
    var spread: Double {
        guard bid > 0, ask > 0 else { return 0 }
        return ask - bid
    }
}
