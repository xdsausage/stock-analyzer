import SwiftUI

/// Interval picker and indicator toggles for the chart.
struct ChartControlsView: View {
    @Bindable var viewModel: StockQuoteViewModel

    var body: some View {
        VStack(spacing: 8) {
            // Interval picker
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(ChartInterval.allCases) { interval in
                        Button(interval.label) {
                            Task { await viewModel.selectInterval(interval) }
                        }
                        .font(.caption.bold())
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(
                            viewModel.selectedInterval == interval
                                ? Color.accentColor
                                : Color.secondary.opacity(0.15),
                            in: Capsule()
                        )
                        .foregroundStyle(
                            viewModel.selectedInterval == interval
                                ? .white
                                : .primary
                        )
                    }
                }
                .padding(.horizontal)
            }

            // Indicator toggles
            HStack(spacing: 12) {
                ToggleChip(label: "MA 20", isOn: $viewModel.showMA20, color: .orange)
                ToggleChip(label: "MA 50", isOn: $viewModel.showMA50, color: .purple)
                ToggleChip(label: "RSI", isOn: $viewModel.showRSI, color: .cyan)
                ToggleChip(label: "MACD", isOn: $viewModel.showMACD, color: .green)
                Spacer()
            }
            .padding(.horizontal)
        }
    }
}

/// A small toggle chip button.
private struct ToggleChip: View {
    let label: String
    @Binding var isOn: Bool
    let color: Color

    var body: some View {
        Button {
            isOn.toggle()
        } label: {
            Text(label)
                .font(.caption2.bold())
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(isOn ? color.opacity(0.2) : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(isOn ? color : .secondary.opacity(0.3), lineWidth: 1))
                .foregroundStyle(isOn ? color : .secondary)
        }
    }
}
