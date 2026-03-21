import SwiftUI

/// Stock screener tab with predefined filters.
struct ScreenerView: View {
    @State private var viewModel = ScreenerViewModel()

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Source picker
                Picker("Source", selection: $viewModel.selectedSource) {
                    ForEach(ScreenerViewModel.sources, id: \.id) { source in
                        Text(source.label).tag(source.id)
                    }
                }
                .pickerStyle(.segmented)
                .padding()
                .onChange(of: viewModel.selectedSource) { _, newValue in
                    Task { await viewModel.changeSource(newValue) }
                }

                // Results list
                List {
                    ForEach(viewModel.stocks) { stock in
                        ScreenerRowView(stock: stock)
                    }
                }
            }
            .navigationTitle("Screener")
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
            .task {
                await viewModel.load()
            }
        }
    }
}
