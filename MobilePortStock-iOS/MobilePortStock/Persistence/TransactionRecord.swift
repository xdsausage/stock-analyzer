import Foundation
import SwiftData

/// A single buy or sell transaction, persisted via SwiftData.
@Model
final class TransactionRecord {
    var ticker: String
    var isBuy: Bool
    var shares: Double
    var pricePerShare: Double
    var totalValue: Double
    var timestamp: Date

    init(ticker: String, isBuy: Bool, shares: Double,
         pricePerShare: Double, timestamp: Date = .now) {
        self.ticker = ticker
        self.isBuy = isBuy
        self.shares = shares
        self.pricePerShare = pricePerShare
        self.totalValue = shares * pricePerShare
        self.timestamp = timestamp
    }
}
