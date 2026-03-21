import Foundation
import Observation

/// ViewModel for the stock quote screen — manages search, quote data, and chart state.
@Observable
final class StockQuoteViewModel {

    // MARK: - Published state

    var stockData: StockData?
    var chartData: ChartData?
    var selectedInterval: ChartInterval = .defaultInterval
    var isLoading = false
    var errorMessage: String?

    // Chart overlay toggles
    var showMA20 = false
    var showMA50 = false
    var showRSI = false
    var showMACD = false

    // Computed indicators (recalculated when chartData changes)
    var ma20: [Double?] = []
    var ma50: [Double?] = []
    var rsiValues: [Double?] = []
    var macdResult: IndicatorCalculator.MACDResult?

    // MARK: - Private

    private let service = YahooFinanceService()
    private(set) var currentTicker: String?

    // MARK: - Actions

    /// Searches for a ticker, fetching both quote data and chart.
    func search(ticker: String) async {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty else { return }

        isLoading = true
        errorMessage = nil
        currentTicker = upper

        do {
            let data = try await service.fetch(upper)
            stockData = data
            selectedInterval = .defaultInterval
            await loadChart()
        } catch {
            errorMessage = error.localizedDescription
            stockData = nil
            chartData = nil
        }

        isLoading = false
    }

    /// Loads chart data for the current ticker and selected interval.
    func loadChart() async {
        guard let ticker = currentTicker else { return }

        do {
            let data = try await service.fetchChart(ticker, interval: selectedInterval)
            chartData = data
            recalculateIndicators()
        } catch {
            chartData = nil
        }
    }

    /// Changes the chart interval and reloads chart data.
    func selectInterval(_ interval: ChartInterval) async {
        selectedInterval = interval
        await loadChart()
    }

    // MARK: - Indicator calculation

    private func recalculateIndicators() {
        guard let chart = chartData, !chart.prices.isEmpty else {
            ma20 = []; ma50 = []; rsiValues = []; macdResult = nil
            return
        }

        ma20 = IndicatorCalculator.computeMovingAverage(chart.prices, period: 20)
        ma50 = IndicatorCalculator.computeMovingAverage(chart.prices, period: 50)
        rsiValues = IndicatorCalculator.computeRSI(chart.prices, period: 14)
        macdResult = IndicatorCalculator.computeMACD(chart.prices)
    }
}
