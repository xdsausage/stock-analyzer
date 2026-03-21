import Foundation
import Observation
import SwiftData

/// ViewModel for the watchlist — manages CRUD and auto-refresh of cached prices.
@Observable
final class WatchlistViewModel {

    var isRefreshing = false
    var errorMessage: String?

    private let service = YahooFinanceService()
    private var refreshTask: Task<Void, Never>?

    /// Refreshes cached prices for all watchlist entries.
    func refreshPrices(entries: [WatchlistEntry]) async {
        isRefreshing = true
        for entry in entries {
            do {
                let data = try await service.fetch(entry.ticker)
                entry.cachedPrice = data.currentPrice
                entry.cachedChangePercent = data.priceChangePercent
            } catch { /* skip failed entries */ }
        }
        isRefreshing = false
    }

    /// Starts auto-refresh every 60 seconds.
    func startAutoRefresh(entries: [WatchlistEntry]) {
        stopAutoRefresh()
        refreshTask = Task {
            while !Task.isCancelled {
                await refreshPrices(entries: entries)
                try? await Task.sleep(for: .seconds(60))
            }
        }
    }

    func stopAutoRefresh() {
        refreshTask?.cancel()
        refreshTask = nil
    }

    /// Adds a ticker to the watchlist.
    func addTicker(_ ticker: String, context: ModelContext) async {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty else { return }

        do {
            let data = try await service.fetch(upper)
            let entry = WatchlistEntry(ticker: upper, companyName: data.companyName)
            entry.cachedPrice = data.currentPrice
            entry.cachedChangePercent = data.priceChangePercent
            context.insert(entry)
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    /// Removes an entry from the watchlist.
    func remove(_ entry: WatchlistEntry, context: ModelContext) {
        context.delete(entry)
    }
}
