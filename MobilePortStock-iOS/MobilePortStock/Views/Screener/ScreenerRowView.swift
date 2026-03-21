import SwiftUI

/// A single row in the screener results.
struct ScreenerRowView: View {
    let stock: ScreenerStock

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(stock.symbol)
                    .font(.subheadline.bold())
                Text(stock.name)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                Text(FormatUtils.price(stock.price))
                    .font(.subheadline)
                Text(FormatUtils.percent(stock.changePercent))
                    .font(.caption.bold())
                    .foregroundStyle(AppColors.changeColor(for: stock.changePercent))
            }

            VStack(alignment: .trailing, spacing: 2) {
                Text(FormatUtils.compactNumber(stock.marketCap))
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(FormatUtils.volume(stock.volume))
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
            .frame(width: 70, alignment: .trailing)
        }
    }
}
