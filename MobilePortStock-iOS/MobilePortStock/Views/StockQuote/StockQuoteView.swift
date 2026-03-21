import SwiftUI

/// Main stock quote screen with search, hero card, stat grid, and interactive chart.
struct StockQuoteView: View {
    @State private var viewModel = StockQuoteViewModel()
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            ZStack {
                ScrollView {
                    VStack(spacing: 12) {
                        // Search bar
                        SearchBarView(text: $searchText) {
                            Task { await viewModel.search(ticker: searchText) }
                        }

                        // Error banner
                        if let error = viewModel.errorMessage {
                            ErrorBannerView(message: error) {
                                viewModel.errorMessage = nil
                            }
                        }

                        // Results
                        if let stock = viewModel.stockData {
                            HeroCardView(stockData: stock)

                            // Stat cards
                            statGrid(stock)

                            // Chart
                            if let chartData = viewModel.chartData, !chartData.isEmpty {
                                ChartControlsView(viewModel: viewModel)
                                StockChartView(chartData: chartData, viewModel: viewModel)
                            }

                            // Notes
                            StockNotesView(ticker: stock.symbol)
                        } else if !viewModel.isLoading {
                            ContentUnavailableView(
                                "Search for a Stock",
                                systemImage: "chart.line.uptrend.xyaxis",
                                description: Text("Enter a ticker symbol to view quote data and charts.")
                            )
                        }
                    }
                    .padding()
                }

                if viewModel.isLoading {
                    LoadingOverlay()
                }
            }
            .navigationTitle("Stock Quote")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    // MARK: - Stat Grid

    @ViewBuilder
    private func statGrid(_ stock: StockData) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 150))], spacing: 8) {
            // Market data
            StatCardView(title: "Market Cap", value: FormatUtils.compactNumber(stock.marketCap))
            StatCardView(title: "Volume", value: FormatUtils.volume(stock.tradingVolume))
            StatCardView(title: "Avg Volume", value: FormatUtils.volume(stock.averageDailyVolume))

            // Valuation
            StatCardView(title: "P/E Ratio", value: stock.peRatio.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "Forward P/E", value: stock.forwardPE.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "EPS", value: stock.earningsPerShare.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "P/B Ratio", value: stock.priceToBook.map { FormatUtils.price($0) } ?? "N/A")

            // Risk & income
            StatCardView(title: "Beta", value: stock.beta.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(
                title: "Dividend Yield",
                value: stock.dividendYield.map { FormatUtils.percent($0 * 100) } ?? "N/A"
            )

            // Range & MAs
            StatCardView(title: "52W High", value: stock.fiftyTwoWeekHigh.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "52W Low", value: stock.fiftyTwoWeekLow.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "50D MA", value: stock.fiftyDayMovingAverage.map { FormatUtils.price($0) } ?? "N/A")
            StatCardView(title: "200D MA", value: stock.twoHundredDayMovingAverage.map { FormatUtils.price($0) } ?? "N/A")
        }
    }
}
