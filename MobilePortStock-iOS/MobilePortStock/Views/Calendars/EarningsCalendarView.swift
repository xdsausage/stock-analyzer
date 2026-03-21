import SwiftUI

/// Earnings calendar showing upcoming earnings events.
struct EarningsCalendarView: View {
    @State private var viewModel = EarningsCalendarViewModel()

    var body: some View {
        NavigationStack {
            List {
                if viewModel.entries.isEmpty && !viewModel.isLoading {
                    ContentUnavailableView("No Earnings Data", systemImage: "calendar",
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
                            if let date = entry.earningsDateFormatted {
                                Text(date, format: .dateTime.month(.abbreviated).day().year())
                                    .font(.caption)
                            }
                            Text(entry.earningsTime)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Spacer()

                            if let est = entry.epsEstimate {
                                Text("Est: \(FormatUtils.price(est))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            if let actual = entry.epsActual {
                                Text("Act: \(FormatUtils.price(actual))")
                                    .font(.caption.bold())
                                    .foregroundStyle(
                                        actual >= (entry.epsEstimate ?? 0)
                                            ? AppColors.gain : AppColors.loss
                                    )
                            }
                        }
                    }
                }
            }
            .navigationTitle("Earnings Calendar")
            .refreshable { await viewModel.load() }
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
            .task { await viewModel.load() }
        }
    }
}
