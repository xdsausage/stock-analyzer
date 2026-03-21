import Foundation

/// Black-Scholes options analytics: Greeks, implied move, max pain.
enum OptionsAnalytics {

    private static let secondsPerYear: Double = 365.25 * 24 * 60 * 60
    private static let minTimeYears: Double = 1.0 / (365.25 * 24.0)

    // MARK: - Single contract analysis

    /// Analyzes a single options contract, computing Greeks and related metrics.
    ///
    /// - Parameters:
    ///   - contract: The options contract to analyze.
    ///   - spotPrice: Current underlying price.
    ///   - valuationEpoch: Current time as Unix epoch seconds.
    ///   - isCall: `true` for call, `false` for put.
    /// - Returns: An ``OptionAnalysis`` with all computed metrics.
    static func analyze(_ contract: OptionsContract, spotPrice: Double,
                        valuationEpoch: TimeInterval, isCall: Bool) -> OptionAnalysis {
        guard spotPrice > 0, contract.strike > 0 else {
            return OptionAnalysis(
                isCall: isCall, midpoint: 0, spread: 0, spreadPercent: 0,
                breakEven: 0, signedStrikeDistancePercent: 0,
                intrinsicValue: 0, extrinsicValue: 0,
                daysToExpiration: 0, timeToExpirationYears: 0,
                delta: 0, gamma: 0, thetaPerDay: 0, vegaPerVolPoint: 0,
                probabilityInTheMoney: 0)
        }

        let mid = contract.midpoint
        let spread = max(contract.ask - contract.bid, 0)
        let spreadPercent = mid > 0 ? (spread / mid) * 100.0 : 0

        let intrinsic = isCall
            ? max(spotPrice - contract.strike, 0)
            : max(contract.strike - spotPrice, 0)
        let premiumBasis = mid > 0 ? mid : max(contract.lastPrice, 0)
        let extrinsic = max(premiumBasis - intrinsic, 0)
        let breakEven = isCall
            ? contract.strike + premiumBasis
            : contract.strike - premiumBasis
        let signedDistance = ((contract.strike - spotPrice) / spotPrice) * 100.0

        let secondsToExp = max(contract.expiration - valuationEpoch, 0)
        let yearsToExp = secondsToExp / secondsPerYear
        let effectiveYears = max(yearsToExp, minTimeYears)
        let daysToExp = yearsToExp * 365.25

        let iv = contract.impliedVolatility
        guard iv > 0 else {
            return OptionAnalysis(
                isCall: isCall, midpoint: mid, spread: spread,
                spreadPercent: spreadPercent, breakEven: breakEven,
                signedStrikeDistancePercent: signedDistance,
                intrinsicValue: intrinsic, extrinsicValue: extrinsic,
                daysToExpiration: daysToExp, timeToExpirationYears: yearsToExp,
                delta: 0, gamma: 0, thetaPerDay: 0, vegaPerVolPoint: 0,
                probabilityInTheMoney: 0)
        }

        let sqrtT = sqrt(effectiveYears)
        let d1 = (log(spotPrice / contract.strike) + 0.5 * iv * iv * effectiveYears) / (iv * sqrtT)
        let d2 = d1 - iv * sqrtT
        let pdf = normalPdf(d1)

        let delta = isCall ? normalCdf(d1) : normalCdf(d1) - 1.0
        let gamma = pdf / (spotPrice * iv * sqrtT)
        let thetaAnnual = -(spotPrice * pdf * iv) / (2.0 * sqrtT)
        let thetaPerDay = thetaAnnual / 365.25
        let vegaPerVolPoint = (spotPrice * pdf * sqrtT) / 100.0
        let probabilityItm = isCall ? normalCdf(d2) : normalCdf(-d2)

        return OptionAnalysis(
            isCall: isCall, midpoint: mid, spread: spread,
            spreadPercent: spreadPercent, breakEven: breakEven,
            signedStrikeDistancePercent: signedDistance,
            intrinsicValue: intrinsic, extrinsicValue: extrinsic,
            daysToExpiration: daysToExp, timeToExpirationYears: yearsToExp,
            delta: delta, gamma: gamma, thetaPerDay: thetaPerDay,
            vegaPerVolPoint: vegaPerVolPoint, probabilityInTheMoney: probabilityItm)
    }

    // MARK: - Chain summary

    /// Computes aggregate summary metrics for an options chain at a given expiration.
    static func summarize(_ chain: OptionsChain, expiration: TimeInterval,
                          valuationEpoch: TimeInterval) -> OptionsChainSummary {
        let calls = chain.calls.filter { $0.expiration == expiration }
        let puts = chain.puts.filter { $0.expiration == expiration }

        let callVolume = calls.reduce(0) { $0 + $1.volume }
        let putVolume = puts.reduce(0) { $0 + $1.volume }
        let callOI = calls.reduce(0) { $0 + $1.openInterest }
        let putOI = puts.reduce(0) { $0 + $1.openInterest }

        let yearsToExp = max(expiration - valuationEpoch, 0) / secondsPerYear
        let daysToExp = yearsToExp * 365.25

        let atmStrike = findAtmStrike(spotPrice: chain.underlyingPrice, calls: calls, puts: puts)
        let atmCall = findNearestStrike(contracts: calls, target: atmStrike)
        let atmPut = findNearestStrike(contracts: puts, target: atmStrike)
        let atmCallMid = atmCall?.midpoint ?? 0
        let atmPutMid = atmPut?.midpoint ?? 0
        let atmStraddle = atmCallMid + atmPutMid
        let impliedMovePercent = chain.underlyingPrice > 0
            ? (atmStraddle / chain.underlyingPrice) * 100.0
            : 0

        let maxPain = computeMaxPain(calls: calls, puts: puts)

        return OptionsChainSummary(
            callCount: calls.count, putCount: puts.count,
            totalCallVolume: callVolume, totalPutVolume: putVolume,
            totalCallOpenInterest: callOI, totalPutOpenInterest: putOI,
            daysToExpiration: daysToExp, atmStrike: atmStrike,
            atmStraddleMidpoint: atmStraddle,
            impliedMoveAmount: atmStraddle,
            impliedMovePercent: impliedMovePercent,
            maxPainStrike: maxPain)
    }

    // MARK: - Helpers

    private static func findAtmStrike(spotPrice: Double,
                                      calls: [OptionsContract],
                                      puts: [OptionsContract]) -> Double {
        var strikes = Set<Double>()
        for c in calls { strikes.insert(c.strike) }
        for p in puts { strikes.insert(p.strike) }
        guard !strikes.isEmpty else { return 0 }

        return strikes.min(by: { abs($0 - spotPrice) < abs($1 - spotPrice) }) ?? 0
    }

    private static func findNearestStrike(contracts: [OptionsContract],
                                          target: Double) -> OptionsContract? {
        contracts.min(by: { abs($0.strike - target) < abs($1.strike - target) })
    }

    static func computeMaxPain(calls: [OptionsContract],
                                puts: [OptionsContract]) -> Double {
        var strikes = Set<Double>()
        for c in calls { strikes.insert(c.strike) }
        for p in puts { strikes.insert(p.strike) }
        guard !strikes.isEmpty else { return 0 }

        var bestStrike = strikes.first!
        var lowestPayout = Double.infinity

        for settlement in strikes {
            var payout = 0.0
            for call in calls {
                payout += max(settlement - call.strike, 0) * Double(max(call.openInterest, 0)) * 100.0
            }
            for put in puts {
                payout += max(put.strike - settlement, 0) * Double(max(put.openInterest, 0)) * 100.0
            }
            if payout < lowestPayout {
                lowestPayout = payout
                bestStrike = settlement
            }
        }
        return bestStrike
    }

    // MARK: - Normal distribution

    private static func normalPdf(_ x: Double) -> Double {
        exp(-0.5 * x * x) / sqrt(2.0 * .pi)
    }

    private static func normalCdf(_ x: Double) -> Double {
        0.5 * (1.0 + erf(x / sqrt(2.0)))
    }

    /// Abramowitz & Stegun rational polynomial approximation of the error function.
    /// Maximum absolute error: 1.5e-7.
    private static func erf(_ x: Double) -> Double {
        let sign: Double = x < 0 ? -1.0 : 1.0
        let ax = abs(x)
        let t = 1.0 / (1.0 + 0.3275911 * ax)
        let y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * exp(-ax * ax)
        return sign * y
    }
}
