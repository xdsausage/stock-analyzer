import SwiftUI
import Charts

/// Detail chart for a selected commodity.
struct CommodityDetailView: View {
    let name: String
    let chartData: ChartData

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(name)
                .font(.headline)

            Chart {
                ForEach(Array(chartData.prices.enumerated()), id: \.offset) { i, price in
                    LineMark(
                        x: .value("Time", Date(timeIntervalSince1970: chartData.timestamps[i])),
                        y: .value("Price", price)
                    )
                    .foregroundStyle(AppColors.priceLine)
                }
            }
            .chartYScale(domain: .automatic(includesZero: false))
            .frame(height: 200)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}
