import Foundation

/// Immutable container for a single price chart series fetched from the API.
///
/// All three arrays are parallel — index `i` represents the same bar across
/// all three. Volumes may be all-zero when the API does not return volume
/// data for a particular interval.
struct ChartData: Codable, Hashable {

    /// Unix timestamps (seconds since epoch) for each bar, sorted ascending.
    let timestamps: [TimeInterval]

    /// Closing price for each bar, in the asset's native currency.
    let prices: [Double]

    /// Trading volume for each bar. A value of 0 means the API did not
    /// provide volume for that bar rather than that no shares traded.
    let volumes: [Int]

    /// Number of bars in the series.
    var count: Int { timestamps.count }

    /// Whether this chart data is empty (no bars).
    var isEmpty: Bool { timestamps.isEmpty }
}
