import SwiftUI

/// News feed tab showing market news articles.
struct NewsTabView: View {
    @State private var viewModel = NewsViewModel()
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            List {
                if viewModel.items.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No News", systemImage: "newspaper",
                                           description: Text("Pull to refresh or search for news."))
                }

                ForEach(viewModel.items) { item in
                    NewsCardView(item: item)
                }
            }
            .navigationTitle("News")
            .searchable(text: $searchText, prompt: "Search news...")
            .onSubmit(of: .search) {
                Task { await viewModel.search(query: searchText) }
            }
            .refreshable {
                if searchText.isEmpty {
                    await viewModel.loadTopNews()
                } else {
                    await viewModel.search(query: searchText)
                }
            }
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
            .task {
                await viewModel.loadTopNews()
            }
        }
    }
}
