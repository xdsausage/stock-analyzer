import SwiftUI

/// App color palette that adapts to the system color scheme.
///
/// On iOS, we respect the system dark/light mode via `@Environment(\.colorScheme)`
/// rather than providing a manual toggle. These named colors map to the Java
/// `ThemeColors` record's dark and light palettes.
enum AppColors {

    // MARK: - Semantic colors (adapt automatically via asset catalog or colorScheme)

    static let gain = Color(red: 72/255, green: 199/255, blue: 142/255)
    static let loss = Color(red: 252/255, green: 100/255, blue: 100/255)

    // MARK: - Chart colors

    static let priceLine = Color(red: 120/255, green: 200/255, blue: 255/255)
    static let ma20 = Color.orange
    static let ma50 = Color(red: 180/255, green: 100/255, blue: 255/255)
    static let comparisonLine = Color(red: 255/255, green: 200/255, blue: 50/255)
    static let volumeBar = Color.accentColor.opacity(0.22)

    // MARK: - Helpers

    /// Returns gain or loss color based on sign of the value.
    static func changeColor(for value: Double) -> Color {
        value >= 0 ? gain : loss
    }
}
