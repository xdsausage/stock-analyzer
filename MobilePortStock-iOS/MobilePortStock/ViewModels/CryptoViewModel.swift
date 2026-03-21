import Foundation
import Observation

@Observable
final class CryptoViewModel {
    struct CryptoItem: Identifiable {
        let ticker: String
        let name: String
        var price: Double = 0
        var changePercent: Double = 0
        var sparkPrices: [Double] = []
        var id: String { ticker }
    }

    var items: [CryptoItem] = [
        CryptoItem(ticker: "BTC-USD", name: "Bitcoin"),
        CryptoItem(ticker: "ETH-USD", name: "Ethereum"),
        CryptoItem(ticker: "BNB-USD", name: "BNB"),
        CryptoItem(ticker: "SOL-USD", name: "Solana"),
        CryptoItem(ticker: "XRP-USD", name: "XRP"),
        CryptoItem(ticker: "ADA-USD", name: "Cardano"),
        CryptoItem(ticker: "DOGE-USD", name: "Dogecoin"),
        CryptoItem(ticker: "DOT-USD", name: "Polkadot"),
    ]

    var isLoading = false
    var selectedItem: CryptoItem?
    var detailChartData: ChartData?

    private let service = YahooFinanceService()

    func refresh() async {
        isLoading = true
        for i in items.indices {
            do {
                let data = try await service.fetch(items[i].ticker)
                items[i].price = data.currentPrice
                items[i].changePercent = data.priceChangePercent
                let chart = try await service.fetchChart(items[i].ticker, interval: .oneDay)
                items[i].sparkPrices = chart.prices
            } catch { /* skip */ }
        }
        isLoading = false
    }

    func loadDetail(_ item: CryptoItem, interval: ChartInterval = .oneDay) async {
        selectedItem = item
        do {
            detailChartData = try await service.fetchChart(item.ticker, interval: interval)
        } catch {
            detailChartData = nil
        }
    }
}
