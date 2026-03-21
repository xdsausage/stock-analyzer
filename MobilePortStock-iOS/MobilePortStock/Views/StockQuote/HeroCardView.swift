import SwiftUI

/// Displays the company name, current price, and daily change prominently.
struct HeroCardView: View {
    let stockData: StockData

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(stockData.companyName)
                .font(.headline)
                .foregroundStyle(.secondary)

            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(FormatUtils.price(stockData.currentPrice))
                    .font(.system(size: 36, weight: .bold, design: .rounded))

                Text(stockData.currency)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            HStack(spacing: 8) {
                Text(FormatUtils.change(stockData.priceChange))
                    .font(.subheadline.bold())
                    .foregroundStyle(AppColors.changeColor(for: stockData.priceChange))

                Text("(\(FormatUtils.percent(stockData.priceChangePercent)))")
                    .font(.subheadline)
                    .foregroundStyle(AppColors.changeColor(for: stockData.priceChangePercent))

                Spacer()

                Text(stockData.exchange)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(.quaternary, in: Capsule())
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
