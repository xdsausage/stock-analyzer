import SwiftUI

/// Sheet for selling shares from a portfolio position.
struct SellSheet: View {
    @Environment(\.dismiss) private var dismiss
    let position: PortfolioPosition
    let onSell: (Double, Double) -> Void

    @State private var shares = ""
    @State private var price = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        Text("Ticker")
                        Spacer()
                        Text(position.ticker).bold()
                    }
                    HStack {
                        Text("Shares Owned")
                        Spacer()
                        Text(String(format: "%.2f", position.sharesOwned))
                    }
                    HStack {
                        Text("Avg Buy Price")
                        Spacer()
                        Text(FormatUtils.price(position.averageBuyPrice))
                    }
                }

                Section("Sell Details") {
                    TextField("Shares to Sell", text: $shares)
                        .keyboardType(.decimalPad)
                    TextField("Sell Price Per Share", text: $price)
                        .keyboardType(.decimalPad)
                }

                if let s = Double(shares), let p = Double(price), s > 0 {
                    Section("Estimated P&L") {
                        let gain = s * (p - position.averageBuyPrice)
                        HStack {
                            Text("Realized Gain")
                            Spacer()
                            Text(FormatUtils.change(gain))
                                .foregroundStyle(AppColors.changeColor(for: gain))
                                .bold()
                        }
                    }
                }
            }
            .navigationTitle("Sell \(position.ticker)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Sell") {
                        if let s = Double(shares), let p = Double(price) {
                            onSell(s, p)
                            dismiss()
                        }
                    }
                    .disabled({
                        guard let s = Double(shares), let p = Double(price) else { return true }
                        return s <= 0 || s > position.sharesOwned || p <= 0
                    }())
                }
            }
        }
    }
}
