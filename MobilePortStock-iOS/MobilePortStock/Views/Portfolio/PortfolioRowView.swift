import SwiftUI

/// A single portfolio position row.
struct PortfolioRowView: View {
    let position: PortfolioPosition
    let onSell: (Double, Double) -> Void

    @State private var showSellSheet = false

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(position.ticker)
                    .font(.subheadline.bold())
                Spacer()
                if position.currentPrice > 0 {
                    Text(FormatUtils.price(position.marketValue))
                        .font(.subheadline.bold())
                }
            }

            HStack {
                Text("\(String(format: "%.2f", position.sharesOwned)) shares @ \(FormatUtils.price(position.averageBuyPrice))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                if position.currentPrice > 0 {
                    let gain = position.unrealizedGainPercent
                    Text(FormatUtils.percent(gain))
                        .font(.caption.bold())
                        .foregroundStyle(AppColors.changeColor(for: gain))
                }
            }
        }
        .swipeActions(edge: .trailing) {
            Button("Sell") { showSellSheet = true }
                .tint(.orange)
        }
        .sheet(isPresented: $showSellSheet) {
            SellSheet(position: position, onSell: onSell)
        }
    }
}
