import Foundation
import Observation

@Observable
final class ScreenerViewModel {
    var stocks: [ScreenerStock] = []
    var isLoading = false
    var selectedSource = "day_gainers"

    static let sources: [(label: String, id: String)] = [
        ("Day Gainers", "day_gainers"),
        ("Day Losers", "day_losers"),
        ("Most Active", "most_actives"),
    ]

    private let service = YahooFinanceService()

    func load() async {
        isLoading = true
        stocks = await service.fetchScreener(selectedSource, count: 25)
        isLoading = false
    }

    func changeSource(_ sourceId: String) async {
        selectedSource = sourceId
        await load()
    }
}
