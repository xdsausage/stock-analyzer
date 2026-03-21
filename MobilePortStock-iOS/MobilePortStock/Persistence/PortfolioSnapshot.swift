import Foundation
import SwiftData

/// A daily snapshot of total portfolio value, persisted for the portfolio chart.
@Model
final class PortfolioSnapshot {
    /// Day number (e.g. days since epoch) for uniqueness.
    @Attribute(.unique) var epochDay: Int
    /// Total portfolio value on this day.
    var totalValue: Double
    /// The date this snapshot represents.
    var date: Date

    init(epochDay: Int, totalValue: Double, date: Date = .now) {
        self.epochDay = epochDay
        self.totalValue = totalValue
        self.date = date
    }
}
