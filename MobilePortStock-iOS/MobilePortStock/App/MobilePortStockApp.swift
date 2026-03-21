import SwiftUI
import SwiftData

@main
struct MobilePortStockApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
        .modelContainer(for: [
            PortfolioPosition.self,
            TransactionRecord.self,
            WatchlistEntry.self,
            PortfolioSnapshot.self,
            StockNote.self
        ])
    }
}
