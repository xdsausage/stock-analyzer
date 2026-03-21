import Foundation

/// Data for a dividend calendar event.
struct DividendEntry: Codable, Identifiable, Hashable {
    let ticker: String
    let companyName: String
    /// Unix epoch seconds of the ex-dividend date.
    let exDividendDate: TimeInterval
    let dividendAmount: Double
    let dividendYield: Double
    let frequency: String

    var id: String { ticker }

    /// Ex-dividend date as a `Date` for display formatting.
    var exDividendDateFormatted: Date {
        Date(timeIntervalSince1970: exDividendDate)
    }
}
