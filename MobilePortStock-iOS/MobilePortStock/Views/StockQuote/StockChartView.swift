import SwiftUI
import Charts

/// Interactive stock price chart using Swift Charts.
struct StockChartView: View {
    let chartData: ChartData
    let viewModel: StockQuoteViewModel

    @State private var selectedIndex: Int?

    var body: some View {
        VStack(spacing: 0) {
            // Price chart
            priceChart
                .frame(height: 220)

            // Volume chart
            volumeChart
                .frame(height: 50)

            // RSI panel (toggled)
            if viewModel.showRSI {
                rsiChart
                    .frame(height: 80)
                    .padding(.top, 4)
            }

            // MACD panel (toggled)
            if viewModel.showMACD {
                macdChart
                    .frame(height: 80)
                    .padding(.top, 4)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 12))
    }

    // MARK: - Price Chart

    private var displayPrices: [(index: Int, date: Date, price: Double)] {
        let maxBars = viewModel.selectedInterval.maxBars
        let start = maxBars != nil ? max(0, chartData.count - maxBars!) : 0
        return (start..<chartData.count).map { i in
            (index: i - start,
             date: Date(timeIntervalSince1970: chartData.timestamps[i]),
             price: chartData.prices[i])
        }
    }

    private var priceChart: some View {
        Chart {
            ForEach(displayPrices, id: \.index) { point in
                LineMark(
                    x: .value("Time", point.date),
                    y: .value("Price", point.price)
                )
                .foregroundStyle(AppColors.priceLine)
                .interpolationMethod(.catmullRom)
            }

            // MA20 overlay
            if viewModel.showMA20 {
                ForEach(ma20Points, id: \.index) { point in
                    LineMark(
                        x: .value("Time", point.date),
                        y: .value("MA20", point.value)
                    )
                    .foregroundStyle(AppColors.ma20)
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                }
            }

            // MA50 overlay
            if viewModel.showMA50 {
                ForEach(ma50Points, id: \.index) { point in
                    LineMark(
                        x: .value("Time", point.date),
                        y: .value("MA50", point.value)
                    )
                    .foregroundStyle(AppColors.ma50)
                    .lineStyle(StrokeStyle(lineWidth: 1.5))
                }
            }

            // Crosshair
            if let idx = selectedIndex, idx < displayPrices.count {
                let point = displayPrices[idx]
                RuleMark(x: .value("Selected", point.date))
                    .foregroundStyle(.secondary.opacity(0.5))
                    .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
                    .annotation(position: .top, alignment: .center) {
                        VStack(spacing: 2) {
                            Text(FormatUtils.price(point.price))
                                .font(.caption.bold())
                            Text(point.date, format: .dateTime.month(.abbreviated).day().hour().minute())
                                .font(.caption2)
                                .foregroundStyle(.secondary)
                        }
                        .padding(6)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 6))
                    }
            }
        }
        .chartYScale(domain: .automatic(includesZero: false))
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 5)) { _ in
                AxisGridLine()
                AxisValueLabel(format: .dateTime.month(.abbreviated).day())
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geo in
                Rectangle()
                    .fill(.clear)
                    .contentShape(Rectangle())
                    .gesture(
                        DragGesture(minimumDistance: 0)
                            .onChanged { value in
                                let origin = geo[proxy.plotFrame!].origin
                                let x = value.location.x - origin.x
                                if let date: Date = proxy.value(atX: x) {
                                    selectedIndex = displayPrices.min(by: {
                                        abs($0.date.timeIntervalSince(date)) < abs($1.date.timeIntervalSince(date))
                                    })?.index
                                }
                            }
                            .onEnded { _ in selectedIndex = nil }
                    )
            }
        }
    }

    // MARK: - Volume Chart

    private var volumeChart: some View {
        Chart {
            ForEach(displayPrices, id: \.index) { point in
                let volIdx = point.index + volumeOffset
                if volIdx < chartData.volumes.count {
                    BarMark(
                        x: .value("Time", point.date),
                        y: .value("Volume", chartData.volumes[volIdx])
                    )
                    .foregroundStyle(AppColors.volumeBar)
                }
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(values: .automatic(desiredCount: 2)) { _ in
                AxisValueLabel()
            }
        }
    }

    private var volumeOffset: Int {
        let maxBars = viewModel.selectedInterval.maxBars
        return maxBars != nil ? max(0, chartData.count - maxBars!) : 0
    }

    // MARK: - RSI Chart

    private var rsiChart: some View {
        Chart {
            ForEach(rsiPoints, id: \.index) { point in
                LineMark(
                    x: .value("Time", point.date),
                    y: .value("RSI", point.value)
                )
                .foregroundStyle(.cyan)
            }

            // Overbought/oversold lines
            RuleMark(y: .value("Overbought", 70))
                .foregroundStyle(.red.opacity(0.4))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
            RuleMark(y: .value("Oversold", 30))
                .foregroundStyle(.green.opacity(0.4))
                .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }
        .chartYScale(domain: 0...100)
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(values: [30, 50, 70]) { _ in
                AxisGridLine()
                AxisValueLabel()
            }
        }
        .overlay(alignment: .topLeading) {
            Text("RSI (14)")
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
                .padding(4)
        }
    }

    // MARK: - MACD Chart

    private var macdChart: some View {
        Chart {
            if let macd = viewModel.macdResult {
                ForEach(macdLinePoints(macd.macdLine), id: \.index) { point in
                    LineMark(
                        x: .value("Time", point.date),
                        y: .value("MACD", point.value)
                    )
                    .foregroundStyle(.blue)
                }
                ForEach(macdLinePoints(macd.signalLine), id: \.index) { point in
                    LineMark(
                        x: .value("Time", point.date),
                        y: .value("Signal", point.value)
                    )
                    .foregroundStyle(.orange)
                }
                ForEach(macdLinePoints(macd.histogram), id: \.index) { point in
                    BarMark(
                        x: .value("Time", point.date),
                        y: .value("Hist", point.value)
                    )
                    .foregroundStyle(point.value >= 0 ? AppColors.gain.opacity(0.5) : AppColors.loss.opacity(0.5))
                }
            }
        }
        .chartXAxis(.hidden)
        .chartYAxis {
            AxisMarks(values: .automatic(desiredCount: 3)) { _ in
                AxisGridLine()
                AxisValueLabel()
            }
        }
        .overlay(alignment: .topLeading) {
            Text("MACD (12,26,9)")
                .font(.caption2.bold())
                .foregroundStyle(.secondary)
                .padding(4)
        }
    }

    // MARK: - Data point helpers

    private struct ChartPoint: Identifiable {
        let index: Int
        let date: Date
        let value: Double
        var id: Int { index }
    }

    private var ma20Points: [ChartPoint] {
        indicatorPoints(viewModel.ma20)
    }

    private var ma50Points: [ChartPoint] {
        indicatorPoints(viewModel.ma50)
    }

    private var rsiPoints: [ChartPoint] {
        indicatorPoints(viewModel.rsiValues)
    }

    private func indicatorPoints(_ values: [Double?]) -> [ChartPoint] {
        let maxBars = viewModel.selectedInterval.maxBars
        let start = maxBars != nil ? max(0, chartData.count - maxBars!) : 0
        var points: [ChartPoint] = []
        for i in start..<min(values.count, chartData.count) {
            if let value = values[i] {
                points.append(ChartPoint(
                    index: i - start,
                    date: Date(timeIntervalSince1970: chartData.timestamps[i]),
                    value: value
                ))
            }
        }
        return points
    }

    private func macdLinePoints(_ values: [Double?]) -> [ChartPoint] {
        indicatorPoints(values)
    }
}
