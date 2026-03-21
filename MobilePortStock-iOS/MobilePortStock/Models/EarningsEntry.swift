import Foundation

/// Data for one upcoming (or recently-past) earnings event.
struct EarningsEntry: Codable, Identifiable, Hashable {
    /// Upper-case ticker symbol, e.g. "AAPL".
    let ticker: String

    /// Long company name, or ticker if unavailable.
    let companyName: String

    /// Unix epoch seconds of the expected earnings date; `nil` if unknown.
    let earningsDate: TimeInterval?

    /// "BMO" (before open), "AMC" (after close), or "—" if unknown.
    let earningsTime: String

    /// Analyst consensus EPS estimate; `nil` if unavailable.
    let epsEstimate: Double?

    /// Reported EPS; `nil` if not yet announced.
    let epsActual: Double?

    var id: String { ticker }

    /// Earnings date as a `Date` for display formatting.
    var earningsDateFormatted: Date? {
        guard let earningsDate else { return nil }
        return Date(timeIntervalSince1970: earningsDate)
    }
}
