import Foundation
import Observation

@Observable
final class NewsViewModel {
    var items: [NewsItem] = []
    var isLoading = false

    private let service = YahooFinanceService()

    func loadTopNews() async {
        isLoading = true
        items = await service.fetchTopNewsOfDay()
        isLoading = false
    }

    func search(query: String) async {
        isLoading = true
        items = await service.fetchNews(query)
        isLoading = false
    }
}
