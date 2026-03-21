import SwiftUI

/// Dividend calendar showing upcoming ex-dividend dates.
struct DividendCalendarView: View {
    @State private var viewModel = DividendCalendarViewModel()

    var body: some View {
        NavigationStack {
            List {
                if viewModel.entries.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No Dividend Data", systemImage: "calendar",
                                           description: Text("Pull to refresh."))
                }

                ForEach(viewModel.entries) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(entry.ticker)
                                .font(.subheadline.bold())
                            Text(entry.companyName)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        HStack {
                            Text("Ex-Div: \(entry.exDividendDateFormatted, format: .dateTime.month(.abbreviated).day().year())")
                                .font(.caption)
                            Spacer()
                            Text("$\(FormatUtils.price(entry.dividendAmount))")
                                .font(.caption.bold())
                            Text("(\(FormatUtils.percent(entry.dividendYield)))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }

                        Text(entry.frequency)
                            .font(.caption2)
                            .foregroundStyle(.tertiary)
                    }
                }
            }
            .navigationTitle("Dividend Calendar")
            .refreshable { await viewModel.load() }
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
            .task { await viewModel.load() }
        }
    }
}
