import Foundation

/// Computed Greeks and analysis for a single options contract.
struct OptionAnalysis: Codable, Identifiable, Hashable {
    let isCall: Bool
    let midpoint: Double
    let spread: Double
    let spreadPercent: Double
    let breakEven: Double
    let signedStrikeDistancePercent: Double
    let intrinsicValue: Double
    let extrinsicValue: Double
    let daysToExpiration: Double
    let timeToExpirationYears: Double
    let delta: Double
    let gamma: Double
    let thetaPerDay: Double
    let vegaPerVolPoint: Double
    let probabilityInTheMoney: Double

    var id: String {
        "\(isCall ? "C" : "P")_\(breakEven)_\(daysToExpiration)"
    }
}
