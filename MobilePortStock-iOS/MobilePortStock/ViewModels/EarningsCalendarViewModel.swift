import Foundation
import Observation

@Observable
final class EarningsCalendarViewModel {
    var entries: [EarningsEntry] = []
    var isLoading = false

    private let service = YahooFinanceService()

    /// Default tickers to check for earnings.
    private static let defaultTickers = [
        "AAPL", "MSFT", "GOOGL", "AMZN", "META", "NVDA", "TSLA", "JPM",
        "V", "JNJ", "WMT", "PG", "MA", "UNH", "HD", "DIS", "BAC", "ADBE"
    ]

    func load(tickers: [String]? = nil) async {
        isLoading = true
        entries = await service.fetchEarningsCalendar(tickers ?? Self.defaultTickers)
        entries.sort { ($0.earningsDate ?? 0) < ($1.earningsDate ?? 0) }
        isLoading = false
    }
}
