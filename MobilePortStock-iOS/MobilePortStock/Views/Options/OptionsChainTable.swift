import SwiftUI

/// Table displaying call or put contracts for a given expiration.
struct OptionsChainTable: View {
    let title: String
    let contracts: [OptionsContract]

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.headline)

            if contracts.isEmpty {
                Text("No \(title.lowercased()) available.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else {
                // Header
                HStack {
                    Text("Strike").font(.caption2.bold()).frame(width: 60, alignment: .leading)
                    Text("Last").font(.caption2.bold()).frame(width: 55, alignment: .trailing)
                    Text("Bid").font(.caption2.bold()).frame(width: 50, alignment: .trailing)
                    Text("Ask").font(.caption2.bold()).frame(width: 50, alignment: .trailing)
                    Text("Vol").font(.caption2.bold()).frame(width: 45, alignment: .trailing)
                    Text("OI").font(.caption2.bold()).frame(width: 45, alignment: .trailing)
                    Text("IV").font(.caption2.bold()).frame(width: 50, alignment: .trailing)
                }
                .foregroundStyle(.secondary)

                ForEach(contracts) { contract in
                    HStack {
                        Text(FormatUtils.price(contract.strike))
                            .font(.caption).frame(width: 60, alignment: .leading)
                            .bold(contract.inTheMoney)
                        Text(FormatUtils.price(contract.lastPrice))
                            .font(.caption).frame(width: 55, alignment: .trailing)
                        Text(FormatUtils.price(contract.bid))
                            .font(.caption).frame(width: 50, alignment: .trailing)
                        Text(FormatUtils.price(contract.ask))
                            .font(.caption).frame(width: 50, alignment: .trailing)
                        Text(FormatUtils.volume(contract.volume))
                            .font(.caption2).frame(width: 45, alignment: .trailing)
                        Text(FormatUtils.volume(contract.openInterest))
                            .font(.caption2).frame(width: 45, alignment: .trailing)
                        Text(String(format: "%.0f%%", contract.impliedVolatility * 100))
                            .font(.caption2).frame(width: 50, alignment: .trailing)
                    }
                    .padding(.vertical, 1)
                    .background(contract.inTheMoney ? Color.accentColor.opacity(0.05) : .clear)
                }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
