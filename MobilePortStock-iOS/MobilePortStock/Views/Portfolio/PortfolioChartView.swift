import SwiftUI
import Charts

/// Area chart showing portfolio value over time.
struct PortfolioChartView: View {
    let snapshots: [PortfolioSnapshot]

    var body: some View {
        Chart {
            ForEach(snapshots, id: \.epochDay) { snapshot in
                AreaMark(
                    x: .value("Date", snapshot.date),
                    y: .value("Value", snapshot.totalValue)
                )
                .foregroundStyle(
                    .linearGradient(
                        colors: [.accentColor.opacity(0.3), .accentColor.opacity(0.05)],
                        startPoint: .top, endPoint: .bottom
                    )
                )
                LineMark(
                    x: .value("Date", snapshot.date),
                    y: .value("Value", snapshot.totalValue)
                )
                .foregroundStyle(.accentColor)
            }
        }
        .chartYScale(domain: .automatic(includesZero: false))
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { _ in
                AxisValueLabel(format: .dateTime.month(.abbreviated).day())
            }
        }
    }
}
