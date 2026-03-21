import SwiftUI
import SwiftData

/// Watchlist sidebar (iPad) or standalone view showing tracked tickers with live prices.
struct WatchlistView: View {
    @Environment(\.modelContext) private var modelContext
    @Query(sort: \WatchlistEntry.ticker) private var entries: [WatchlistEntry]
    @State private var viewModel = WatchlistViewModel()
    @State private var showAddSheet = false
    @State private var newTicker = ""

    var body: some View {
        List {
            ForEach(entries, id: \.ticker) { entry in
                WatchlistRowView(entry: entry)
            }
            .onDelete(perform: deleteEntries)
        }
        .navigationTitle("Watchlist")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    showAddSheet = true
                } label: {
                    Image(systemName: "plus")
                }
            }
            ToolbarItem(placement: .status) {
                if viewModel.isRefreshing {
                    ProgressView()
                        .controlSize(.small)
                }
            }
        }
        .alert("Add Ticker", isPresented: $showAddSheet) {
            TextField("AAPL", text: $newTicker)
                .textInputAutocapitalization(.characters)
            Button("Add") {
                Task {
                    await viewModel.addTicker(newTicker, context: modelContext)
                    newTicker = ""
                }
            }
            Button("Cancel", role: .cancel) { newTicker = "" }
        }
        .refreshable {
            await viewModel.refreshPrices(entries: entries)
        }
        .task {
            await viewModel.refreshPrices(entries: entries)
            viewModel.startAutoRefresh(entries: entries)
        }
        .onDisappear {
            viewModel.stopAutoRefresh()
        }
    }

    private func deleteEntries(at offsets: IndexSet) {
        for index in offsets {
            viewModel.remove(entries[index], context: modelContext)
        }
    }
}
