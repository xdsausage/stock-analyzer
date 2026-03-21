import Foundation

/// Predefined chart time intervals matching the Yahoo Finance API parameters.
///
/// Replaces the Java `CHART_INTERVALS` string matrix with a type-safe enum.
enum ChartInterval: String, CaseIterable, Identifiable, Codable {
    case fifteenMin
    case thirtyMin
    case oneHour
    case twoHour
    case oneDay
    case fiveDay
    case oneMonth
    case threeMonth
    case sixMonth
    case oneYear

    var id: String { rawValue }

    /// Display label shown in the UI interval picker.
    var label: String {
        switch self {
        case .fifteenMin:  "15M"
        case .thirtyMin:   "30M"
        case .oneHour:     "1H"
        case .twoHour:     "2H"
        case .oneDay:      "1D"
        case .fiveDay:     "5D"
        case .oneMonth:    "1M"
        case .threeMonth:  "3M"
        case .sixMonth:    "6M"
        case .oneYear:     "1Y"
        }
    }

    /// Yahoo Finance bar interval code (e.g. "1m", "5m", "1d").
    var yahooInterval: String {
        switch self {
        case .fifteenMin:  "1m"
        case .thirtyMin:   "1m"
        case .oneHour:     "1m"
        case .twoHour:     "2m"
        case .oneDay:      "5m"
        case .fiveDay:     "15m"
        case .oneMonth:    "1d"
        case .threeMonth:  "1d"
        case .sixMonth:    "1d"
        case .oneYear:     "1d"
        }
    }

    /// Yahoo Finance range code (e.g. "1d", "1mo", "1y").
    var yahooRange: String {
        switch self {
        case .fifteenMin:  "1d"
        case .thirtyMin:   "1d"
        case .oneHour:     "1d"
        case .twoHour:     "1d"
        case .oneDay:      "1d"
        case .fiveDay:     "5d"
        case .oneMonth:    "1mo"
        case .threeMonth:  "3mo"
        case .sixMonth:    "6mo"
        case .oneYear:     "1y"
        }
    }

    /// Maximum number of bars to display, or `nil` for no limit.
    /// Short intraday windows limit bars to avoid showing pre-market data.
    var maxBars: Int? {
        switch self {
        case .fifteenMin:  15
        case .thirtyMin:   30
        case .oneHour:     60
        case .twoHour:     60
        default:           nil
        }
    }

    /// The default interval used when a stock is first loaded.
    static let defaultInterval: ChartInterval = .oneMonth

    /// The default interval used for commodity and crypto detail views.
    static let commodityDefault: ChartInterval = .oneDay
}
