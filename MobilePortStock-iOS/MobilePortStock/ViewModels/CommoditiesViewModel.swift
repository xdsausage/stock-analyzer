import Foundation
import Observation

@Observable
final class CommoditiesViewModel {
    struct CommodityItem: Identifiable {
        let ticker: String
        let name: String
        var price: Double = 0
        var changePercent: Double = 0
        var sparkPrices: [Double] = []
        var id: String { ticker }
    }

    var items: [CommodityItem] = [
        CommodityItem(ticker: "GC=F", name: "Gold"),
        CommodityItem(ticker: "SI=F", name: "Silver"),
        CommodityItem(ticker: "CL=F", name: "Crude Oil"),
        CommodityItem(ticker: "NG=F", name: "Natural Gas"),
        CommodityItem(ticker: "HG=F", name: "Copper"),
        CommodityItem(ticker: "PL=F", name: "Platinum"),
        CommodityItem(ticker: "ZW=F", name: "Wheat"),
        CommodityItem(ticker: "ZC=F", name: "Corn"),
    ]

    var isLoading = false
    var selectedItem: CommodityItem?
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

    func loadDetail(_ item: CommodityItem, interval: ChartInterval = .oneDay) async {
        selectedItem = item
        do {
            detailChartData = try await service.fetchChart(item.ticker, interval: interval)
        } catch {
            detailChartData = nil
        }
    }
}
