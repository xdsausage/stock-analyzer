import Foundation
import SwiftData

/// A ticker on the user's watchlist, persisted via SwiftData.
@Model
final class WatchlistEntry {
    @Attribute(.unique) var ticker: String
    var companyName: String

    /// Cached price for display (updated on refresh).
    @Transient var cachedPrice: Double = 0

    /// Cached daily change percent (updated on refresh).
    @Transient var cachedChangePercent: Double = 0

    /// Optional price alert threshold; `nil` means no alert set.
    var alertPrice: Double?

    init(ticker: String, companyName: String, alertPrice: Double? = nil) {
        self.ticker = ticker
        self.companyName = companyName
        self.alertPrice = alertPrice
    }
}
