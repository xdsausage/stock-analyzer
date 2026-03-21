import Foundation
import Observation
import SwiftData

/// ViewModel for portfolio management — add/sell positions with cost averaging.
@Observable
final class PortfolioViewModel {

    var isRefreshing = false
    var errorMessage: String?

    private let service = YahooFinanceService()

    /// Total portfolio market value.
    func totalMarketValue(_ positions: [PortfolioPosition]) -> Double {
        positions.reduce(0) { $0 + $1.marketValue }
    }

    /// Total unrealized gain/loss.
    func totalUnrealizedGain(_ positions: [PortfolioPosition]) -> Double {
        positions.reduce(0) { $0 + $1.unrealizedGain }
    }

    /// Total realized gain/loss.
    func totalRealizedGain(_ positions: [PortfolioPosition]) -> Double {
        positions.reduce(0) { $0 + $1.realizedGain }
    }

    /// Total cost basis.
    func totalCostBasis(_ positions: [PortfolioPosition]) -> Double {
        positions.reduce(0) { $0 + $1.costBasis }
    }

    /// Adds shares to a position with cost averaging.
    func addPosition(ticker: String, shares: Double, pricePerShare: Double,
                     context: ModelContext, positions: [PortfolioPosition]) async {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty, shares > 0, pricePerShare > 0 else { return }

        // Fetch current price and currency
        var currency = "USD"
        var currentPrice = pricePerShare
        do {
            let data = try await service.fetch(upper)
            currency = data.currency
            currentPrice = data.currentPrice
        } catch { /* use provided price */ }

        // Find existing position or create new
        if let existing = positions.first(where: { $0.ticker == upper }) {
            // Weighted average cost basis
            let totalShares = existing.sharesOwned + shares
            let totalCost = existing.sharesOwned * existing.averageBuyPrice + shares * pricePerShare
            existing.averageBuyPrice = totalCost / totalShares
            existing.sharesOwned = totalShares
            existing.currentPrice = currentPrice
        } else {
            let position = PortfolioPosition(
                ticker: upper, sharesOwned: shares,
                averageBuyPrice: pricePerShare, currency: currency)
            position.currentPrice = currentPrice
            context.insert(position)
        }

        // Record transaction
        let tx = TransactionRecord(ticker: upper, isBuy: true,
                                   shares: shares, pricePerShare: pricePerShare)
        context.insert(tx)
    }

    /// Sells shares from a position, calculating realized gain.
    func sellPosition(_ position: PortfolioPosition, shares: Double,
                      pricePerShare: Double, context: ModelContext) {
        guard shares > 0, shares <= position.sharesOwned else { return }

        let realizedGain = shares * (pricePerShare - position.averageBuyPrice)
        position.realizedGain += realizedGain
        position.sharesOwned -= shares

        // Record transaction
        let tx = TransactionRecord(ticker: position.ticker, isBuy: false,
                                   shares: shares, pricePerShare: pricePerShare)
        context.insert(tx)

        // Remove position if fully sold
        if position.sharesOwned <= 0.001 {
            context.delete(position)
        }
    }

    /// Refreshes current prices for all positions.
    func refreshPrices(_ positions: [PortfolioPosition]) async {
        isRefreshing = true
        for position in positions {
            do {
                let data = try await service.fetch(position.ticker)
                position.currentPrice = data.currentPrice
            } catch { /* skip */ }
        }
        isRefreshing = false
    }

    /// Takes a daily snapshot of total portfolio value.
    func takeSnapshot(_ positions: [PortfolioPosition], context: ModelContext) {
        let totalValue = totalMarketValue(positions)
        guard totalValue > 0 else { return }

        let epochDay = Int(Date().timeIntervalSince1970 / 86400)
        let snapshot = PortfolioSnapshot(epochDay: epochDay, totalValue: totalValue)
        context.insert(snapshot)
    }
}
