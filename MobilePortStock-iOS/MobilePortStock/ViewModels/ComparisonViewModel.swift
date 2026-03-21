import Foundation
import Observation

@Observable
final class ComparisonViewModel {
    struct ComparisonRow: Identifiable {
        let stockData: StockData
        var id: String { stockData.symbol }
    }

    var rows: [ComparisonRow] = []
    var isLoading = false
    var errorMessage: String?

    private let service = YahooFinanceService()

    func addTicker(_ ticker: String) async {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty else { return }
        guard !rows.contains(where: { $0.stockData.symbol == upper }) else { return }

        isLoading = true
        do {
            let data = try await service.fetch(upper)
            rows.append(ComparisonRow(stockData: data))
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    func remove(at offsets: IndexSet) {
        rows.remove(atOffsets: offsets)
    }
}
