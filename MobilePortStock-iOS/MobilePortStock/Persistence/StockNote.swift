import Foundation
import SwiftData

/// Per-ticker user notes, replacing the Java `notes.properties` file.
@Model
final class StockNote {
    @Attribute(.unique) var ticker: String
    var text: String

    init(ticker: String, text: String = "") {
        self.ticker = ticker
        self.text = text
    }
}
