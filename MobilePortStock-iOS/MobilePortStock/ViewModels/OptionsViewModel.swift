import Foundation
import Observation

@Observable
final class OptionsViewModel {
    var chain: OptionsChain?
    var summary: OptionsChainSummary?
    var selectedExpiration: TimeInterval?
    var analyses: [OptionAnalysis] = []
    var isLoading = false
    var errorMessage: String?

    private let service = YahooFinanceService()

    func load(ticker: String) async {
        let upper = ticker.trimmingCharacters(in: .whitespaces).uppercased()
        guard !upper.isEmpty else { return }

        isLoading = true
        errorMessage = nil

        do {
            let chain = try await service.fetchOptions(upper)
            self.chain = chain
            if let firstExp = chain.expirationDates.first {
                selectedExpiration = firstExp
                computeAnalysis(chain: chain, expiration: firstExp)
            }
        } catch {
            errorMessage = error.localizedDescription
        }

        isLoading = false
    }

    func selectExpiration(_ expiration: TimeInterval) async {
        guard let chain else { return }
        selectedExpiration = expiration

        isLoading = true
        do {
            let updatedChain = try await service.fetchOptions(chain.ticker, expiration: expiration)
            self.chain = updatedChain
            computeAnalysis(chain: updatedChain, expiration: expiration)
        } catch {
            errorMessage = error.localizedDescription
        }
        isLoading = false
    }

    private func computeAnalysis(chain: OptionsChain, expiration: TimeInterval) {
        let now = Date().timeIntervalSince1970
        summary = OptionsAnalytics.summarize(chain, expiration: expiration, valuationEpoch: now)

        var results: [OptionAnalysis] = []
        for call in chain.calls.filter({ $0.expiration == expiration }) {
            results.append(OptionsAnalytics.analyze(call, spotPrice: chain.underlyingPrice,
                                                    valuationEpoch: now, isCall: true))
        }
        for put in chain.puts.filter({ $0.expiration == expiration }) {
            results.append(OptionsAnalytics.analyze(put, spotPrice: chain.underlyingPrice,
                                                    valuationEpoch: now, isCall: false))
        }
        analyses = results
    }
}
