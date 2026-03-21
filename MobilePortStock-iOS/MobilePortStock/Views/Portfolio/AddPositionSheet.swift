import SwiftUI

/// Sheet for adding a new portfolio position.
struct AddPositionSheet: View {
    @Environment(\.dismiss) private var dismiss
    @State private var ticker = ""
    @State private var shares = ""
    @State private var price = ""

    let onAdd: (String, Double, Double) -> Void

    var body: some View {
        NavigationStack {
            Form {
                Section("Position Details") {
                    TextField("Ticker (e.g. AAPL)", text: $ticker)
                        .textInputAutocapitalization(.characters)
                        .autocorrectionDisabled()

                    TextField("Number of Shares", text: $shares)
                        .keyboardType(.decimalPad)

                    TextField("Price Per Share", text: $price)
                        .keyboardType(.decimalPad)
                }
            }
            .navigationTitle("Add Position")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        if let s = Double(shares), let p = Double(price), !ticker.isEmpty {
                            onAdd(ticker, s, p)
                            dismiss()
                        }
                    }
                    .disabled(ticker.isEmpty || Double(shares) == nil || Double(price) == nil)
                }
            }
        }
    }
}
