import SwiftUI

/// Options analysis tab with chain display and Greeks.
struct OptionsView: View {
    @State private var viewModel = OptionsViewModel()
    @State private var tickerText = ""

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 12) {
                    SearchBarView(text: $tickerText, placeholder: "Ticker (e.g. AAPL)") {
                        Task { await viewModel.load(ticker: tickerText) }
                    }

                    if let error = viewModel.errorMessage {
                        ErrorBannerView(message: error) { viewModel.errorMessage = nil }
                    }

                    if let chain = viewModel.chain {
                        // Expiration picker
                        if !chain.expirationDates.isEmpty {
                            ScrollView(.horizontal, showsIndicators: false) {
                                HStack(spacing: 6) {
                                    ForEach(chain.expirationDates, id: \.self) { exp in
                                        let date = Date(timeIntervalSince1970: exp)
                                        Button {
                                            Task { await viewModel.selectExpiration(exp) }
                                        } label: {
                                            Text(date, format: .dateTime.month(.abbreviated).day().year())
                                                .font(.caption)
                                        }
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 4)
                                        .background(
                                            viewModel.selectedExpiration == exp
                                                ? Color.accentColor : Color.secondary.opacity(0.15),
                                            in: Capsule()
                                        )
                                        .foregroundStyle(
                                            viewModel.selectedExpiration == exp ? .white : .primary
                                        )
                                    }
                                }
                            }
                        }

                        // Summary card
                        if let summary = viewModel.summary {
                            OptionsSummaryCard(summary: summary, underlyingPrice: chain.underlyingPrice)
                        }

                        // Chain tables
                        OptionsChainTable(title: "Calls", contracts: chain.calls.filter {
                            $0.expiration == viewModel.selectedExpiration
                        })
                        OptionsChainTable(title: "Puts", contracts: chain.puts.filter {
                            $0.expiration == viewModel.selectedExpiration
                        })
                    }
                }
                .padding()
            }
            .navigationTitle("Options")
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
        }
    }
}
