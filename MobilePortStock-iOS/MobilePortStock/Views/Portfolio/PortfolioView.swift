import SwiftUI
import SwiftData

/// Portfolio management screen showing positions, summary, and transaction history.
struct PortfolioView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \PortfolioPosition.ticker) private var positions: [PortfolioPosition]
    @Query(sort: \TransactionRecord.timestamp, order: .reverse) private var transactions: [TransactionRecord]
    @Query(sort: \PortfolioSnapshot.epochDay) private var snapshots: [PortfolioSnapshot]
    @State private var viewModel = PortfolioViewModel()
    @State private var showAddSheet = false
    @State private var showTransactions = false

    var body: some View {
        NavigationStack {
            List {
                // Summary section
                Section("Summary") {
                    summaryRow("Total Value", FormatUtils.price(viewModel.totalMarketValue(positions)))
                    summaryRow("Cost Basis", FormatUtils.price(viewModel.totalCostBasis(positions)))

                    let unrealized = viewModel.totalUnrealizedGain(positions)
                    HStack {
                        Text("Unrealized P&L")
                        Spacer()
                        Text(FormatUtils.change(unrealized))
                            .foregroundStyle(AppColors.changeColor(for: unrealized))
                    }

                    let realized = viewModel.totalRealizedGain(positions)
                    HStack {
                        Text("Realized P&L")
                        Spacer()
                        Text(FormatUtils.change(realized))
                            .foregroundStyle(AppColors.changeColor(for: realized))
                    }
                }

                // Portfolio chart
                if !snapshots.isEmpty {
                    Section("Value Over Time") {
                        PortfolioChartView(snapshots: snapshots)
                            .frame(height: 150)
                    }
                }

                // Positions
                Section("Positions (\(positions.count))") {
                    if positions.isEmpty {
                        Text("No positions yet. Tap + to add.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(positions, id: \.ticker) { position in
                        PortfolioRowView(position: position) { shares, price in
                            viewModel.sellPosition(position, shares: shares,
                                                   pricePerShare: price, context: modelContext)
                        }
                    }
                    .onDelete { offsets in
                        for index in offsets {
                            modelContext.delete(positions[index])
                        }
                    }
                }
            }
            .navigationTitle("Portfolio")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    Button { showAddSheet = true } label: {
                        Image(systemName: "plus")
                    }
                }
                ToolbarItem(placement: .secondaryAction) {
                    Button("Transactions") { showTransactions = true }
                }
                ToolbarItem(placement: .status) {
                    if viewModel.isRefreshing {
                        ProgressView().controlSize(.small)
                    }
                }
            }
            .sheet(isPresented: $showAddSheet) {
                AddPositionSheet { ticker, shares, price in
                    Task {
                        await viewModel.addPosition(
                            ticker: ticker, shares: shares,
                            pricePerShare: price, context: modelContext,
                            positions: positions)
                    }
                }
            }
            .sheet(isPresented: $showTransactions) {
                TransactionHistoryView(transactions: transactions)
            }
            .refreshable {
                await viewModel.refreshPrices(positions)
                viewModel.takeSnapshot(positions, context: modelContext)
            }
            .task {
                await viewModel.refreshPrices(positions)
            }
        }
    }

    private func summaryRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).bold()
        }
    }
}
