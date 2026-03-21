import SwiftUI

/// Summary metrics card for the options chain.
struct OptionsSummaryCard: View {
    let summary: OptionsChainSummary
    let underlyingPrice: Double

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 140))], spacing: 8) {
            StatCardView(title: "Days to Expiry", value: String(format: "%.0f", summary.daysToExpiration))
            StatCardView(title: "ATM Strike", value: FormatUtils.price(summary.atmStrike))
            StatCardView(title: "ATM Straddle", value: FormatUtils.price(summary.atmStraddleMidpoint))
            StatCardView(title: "Implied Move", value: FormatUtils.percent(summary.impliedMovePercent))
            StatCardView(title: "Max Pain", value: FormatUtils.price(summary.maxPainStrike))
            StatCardView(title: "Call/Put Ratio",
                         value: summary.totalPutVolume > 0
                            ? String(format: "%.2f", Double(summary.totalCallVolume) / Double(summary.totalPutVolume))
                            : "N/A")
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
