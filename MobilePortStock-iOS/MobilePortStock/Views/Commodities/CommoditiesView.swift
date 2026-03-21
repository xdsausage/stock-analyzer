import SwiftUI
import Charts

/// Commodities grid with sparkline charts.
struct CommoditiesView: View {
    @State private var viewModel = CommoditiesViewModel()

    var body: some View {
        NavigationStack {
            ScrollView {
                LazyVGrid(columns: [GridItem(.adaptive(minimum: 160))], spacing: 12) {
                    ForEach(viewModel.items) { item in
                        Button {
                            Task { await viewModel.loadDetail(item) }
                        } label: {
                            commodityCard(item)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding()

                // Detail card
                if let selected = viewModel.selectedItem,
                   let chart = viewModel.detailChartData, !chart.isEmpty {
                    CommodityDetailView(name: selected.name, chartData: chart)
                        .padding(.horizontal)
                }
            }
            .navigationTitle("Commodities")
            .refreshable { await viewModel.refresh() }
            .overlay {
                if viewModel.isLoading { LoadingOverlay() }
            }
            .task { await viewModel.refresh() }
        }
    }

    private func commodityCard(_ item: CommoditiesViewModel.CommodityItem) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(item.name)
                .font(.subheadline.bold())

            if item.price > 0 {
                Text(FormatUtils.price(item.price))
                    .font(.headline)
                Text(FormatUtils.percent(item.changePercent))
                    .font(.caption.bold())
                    .foregroundStyle(AppColors.changeColor(for: item.changePercent))
            }

            if !item.sparkPrices.isEmpty {
                Chart {
                    ForEach(Array(item.sparkPrices.enumerated()), id: \.offset) { i, price in
                        LineMark(x: .value("i", i), y: .value("p", price))
                            .foregroundStyle(AppColors.changeColor(for: item.changePercent))
                    }
                }
                .chartXAxis(.hidden)
                .chartYAxis(.hidden)
                .frame(height: 30)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}
