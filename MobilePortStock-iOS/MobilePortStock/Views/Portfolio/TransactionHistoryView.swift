import SwiftUI

/// Shows the full transaction history.
struct TransactionHistoryView: View {
    @Environment(\.dismiss) private var dismiss
    let transactions: [TransactionRecord]

    var body: some View {
        NavigationStack {
            List {
                if transactions.isEmpty {
                    Text("No transactions yet.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(transactions, id: \.timestamp) { tx in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                HStack(spacing: 4) {
                                    Text(tx.isBuy ? "BUY" : "SELL")
                                        .font(.caption.bold())
                                        .foregroundStyle(tx.isBuy ? AppColors.gain : AppColors.loss)
                                    Text(tx.ticker)
                                        .font(.subheadline.bold())
                                }
                                Text("\(String(format: "%.2f", tx.shares)) shares @ \(FormatUtils.price(tx.pricePerShare))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }

                            Spacer()

                            VStack(alignment: .trailing, spacing: 2) {
                                Text(FormatUtils.price(tx.totalValue))
                                    .font(.subheadline)
                                Text(tx.timestamp, format: .dateTime.month(.abbreviated).day().year())
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Transaction History")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
