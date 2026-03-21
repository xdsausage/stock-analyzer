import Foundation
import Observation

@Observable
final class DividendCalendarViewModel {
    var entries: [DividendEntry] = []
    var isLoading = false

    private let service = YahooFinanceService()

    private static let defaultTickers = [
        "AAPL", "MSFT", "JNJ", "PG", "KO", "PEP", "T", "VZ",
        "XOM", "CVX", "ABBV", "MRK", "O", "SCHD", "SPY", "QQQ"
    ]

    func load(tickers: [String]? = nil) async {
        isLoading = true
        entries = await service.fetchDividendCalendar(tickers ?? Self.defaultTickers)
        entries.sort { $0.exDividendDate < $1.exDividendDate }
        isLoading = false
    }
}
