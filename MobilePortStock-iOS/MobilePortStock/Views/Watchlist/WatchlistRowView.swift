import SwiftUI

/// A single row in the watchlist showing ticker, company name, price, and change.
struct WatchlistRowView: View {
    let entry: WatchlistEntry

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.ticker)
                    .font(.subheadline.bold())
                Text(entry.companyName)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }

            Spacer()

            VStack(alignment: .trailing, spacing: 2) {
                if entry.cachedPrice > 0 {
                    Text(FormatUtils.price(entry.cachedPrice))
                        .font(.subheadline.bold())
                    Text(FormatUtils.percent(entry.cachedChangePercent))
                        .font(.caption.bold())
                        .foregroundStyle(AppColors.changeColor(for: entry.cachedChangePercent))
                } else {
                    Text("—")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 2)
    }
}
