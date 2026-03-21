import SwiftUI

/// Multi-stock comparison view.
struct ComparisonView: View {
    @State private var viewModel = ComparisonViewModel()
    @State private var tickerText = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Add ticker bar
                HStack {
                    SearchBarView(text: $tickerText, placeholder: "Add ticker...") {
                        Task { await viewModel.addTicker(tickerText); tickerText = "" }
                    }
                }
                .padding(.horizontal)
                .padding(.top)

                if let error = viewModel.errorMessage {
                    ErrorBannerView(message: error) { viewModel.errorMessage = nil }
                        .padding(.horizontal)
                }

                // Comparison table
                List {
                    if viewModel.rows.isEmpty {
                        ContentUnavailableView("No Stocks Added",
                                               systemImage: "rectangle.stack",
                                               description: Text("Add tickers above to compare."))
                    }

                    ForEach(viewModel.rows) { row in
                        let s = row.stockData
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(s.symbol).font(.subheadline.bold())
                                Text(s.companyName).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                                Spacer()
                                Text(FormatUtils.price(s.currentPrice)).font(.subheadline.bold())
                            }

                            HStack(spacing: 16) {
                                metric("Chg", FormatUtils.percent(s.priceChangePercent),
                                       color: AppColors.changeColor(for: s.priceChangePercent))
                                metric("P/E", s.peRatio.map { FormatUtils.price($0) } ?? "—")
                                metric("Beta", s.beta.map { FormatUtils.price($0) } ?? "—")
                                metric("Mkt Cap", FormatUtils.compactNumber(s.marketCap))
                            }
                        }
                    }
                    .onDelete { viewModel.remove(at: $0) }
                }
            }
            .navigationTitle("Comparison")
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
        }
    }

    private func metric(_ label: String, _ value: String, color: Color = .primary) -> some View {
        VStack(spacing: 1) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.bold()).foregroundStyle(color)
        }
    }
}
