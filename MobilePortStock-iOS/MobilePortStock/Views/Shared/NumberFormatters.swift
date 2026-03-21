import Foundation

/// Shared number formatting utilities.
enum FormatUtils {

    /// Formats a large number with suffix (K, M, B, T).
    static func compactNumber(_ value: Double) -> String {
        switch abs(value) {
        case 1_000_000_000_000...:
            String(format: "%.2fT", value / 1_000_000_000_000)
        case 1_000_000_000...:
            String(format: "%.2fB", value / 1_000_000_000)
        case 1_000_000...:
            String(format: "%.2fM", value / 1_000_000)
        case 1_000...:
            String(format: "%.2fK", value / 1_000)
        default:
            String(format: "%.2f", value)
        }
    }

    /// Formats a volume number with commas.
    static func volume(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.numberStyle = .decimal
        formatter.groupingSeparator = ","
        return formatter.string(from: NSNumber(value: value)) ?? "\(value)"
    }

    /// Formats a price with 2 decimal places.
    static func price(_ value: Double) -> String {
        String(format: "%.2f", value)
    }

    /// Formats a percentage with 2 decimal places and a sign.
    static func percent(_ value: Double) -> String {
        String(format: "%+.2f%%", value)
    }

    /// Formats a change value with sign.
    static func change(_ value: Double) -> String {
        String(format: "%+.2f", value)
    }
}
