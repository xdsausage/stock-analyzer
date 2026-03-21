import Foundation
import SwiftData

/// A stock position in the user's portfolio, persisted via SwiftData.
@Model
final class PortfolioPosition {
    var ticker: String
    var sharesOwned: Double
    var averageBuyPrice: Double
    var currency: String
    var realizedGain: Double = 0

    /// Not persisted — updated at runtime from live quotes.
    @Transient var currentPrice: Double = 0

    init(ticker: String, sharesOwned: Double, averageBuyPrice: Double,
         currency: String, realizedGain: Double = 0) {
        self.ticker = ticker
        self.sharesOwned = sharesOwned
        self.averageBuyPrice = averageBuyPrice
        self.currency = currency
        self.realizedGain = realizedGain
    }

    /// Current market value of the position.
    var marketValue: Double { sharesOwned * currentPrice }

    /// Unrealized gain/loss in currency terms.
    var unrealizedGain: Double { sharesOwned * (currentPrice - averageBuyPrice) }

    /// Unrealized gain/loss as a percentage.
    var unrealizedGainPercent: Double {
        guard averageBuyPrice != 0 else { return 0 }
        return (currentPrice - averageBuyPrice) / averageBuyPrice * 100
    }

    /// Total cost basis of the position.
    var costBasis: Double { sharesOwned * averageBuyPrice }
}
