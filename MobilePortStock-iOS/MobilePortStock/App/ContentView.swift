import SwiftUI

struct ContentView: View {
    @Environment(\.horizontalSizeClass) private var sizeClass

    var body: some View {
        if sizeClass == .regular {
            // iPad: sidebar navigation with watchlist
            NavigationSplitView {
                WatchlistView()
            } detail: {
                MainTabView()
            }
        } else {
            // iPhone: tab-based navigation
            MainTabView()
        }
    }
}

/// Shared tab container used in both iPhone (root) and iPad (detail) layouts.
struct MainTabView: View {
    var body: some View {
        TabView {
            StockQuoteView()
                .tabItem { Label("Quote", systemImage: "chart.line.uptrend.xyaxis") }

            ScreenerView()
                .tabItem { Label("Screener", systemImage: "list.bullet") }

            PortfolioView()
                .tabItem { Label("Portfolio", systemImage: "briefcase") }

            NewsTabView()
                .tabItem { Label("News", systemImage: "newspaper") }

            MoreView()
                .tabItem { Label("More", systemImage: "ellipsis") }
        }
    }
}

/// Overflow menu for tabs beyond the 5-tab iPhone limit.
struct MoreView: View {
    var body: some View {
        NavigationStack {
            List {
                NavigationLink("Commodities", destination: CommoditiesView())
                NavigationLink("Crypto", destination: CryptoView())
                NavigationLink("Options", destination: OptionsView())
                NavigationLink("Earnings Calendar", destination: EarningsCalendarView())
                NavigationLink("Dividend Calendar", destination: DividendCalendarView())
                NavigationLink("Comparison", destination: ComparisonView())
            }
            .navigationTitle("More")
        }
    }
}

#Preview {
    ContentView()
}
