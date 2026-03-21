import Foundation

/// Aggregate summary metrics for an options chain at a given expiration.
struct OptionsChainSummary: Codable, Hashable {
    let callCount: Int
    let putCount: Int
    let totalCallVolume: Int
    let totalPutVolume: Int
    let totalCallOpenInterest: Int
    let totalPutOpenInterest: Int
    let daysToExpiration: Double
    let atmStrike: Double
    let atmStraddleMidpoint: Double
    let impliedMoveAmount: Double
    let impliedMovePercent: Double
    let maxPainStrike: Double
}
