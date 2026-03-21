import Foundation

/// Pure static utility for computing technical indicators from a price series.
///
/// All methods are O(n) single-pass algorithms and produce output arrays of
/// the same length as the input, filling the warm-up period with `nil` so
/// callers can safely skip those slots when drawing.
enum IndicatorCalculator {

    // MARK: - MACD

    /// The three output series produced by ``computeMACD``.
    struct MACDResult {
        let macdLine: [Double?]
        let signalLine: [Double?]
        let histogram: [Double?]
    }

    /// Computes the MACD indicator using the given parameters (default 12/26/9).
    ///
    /// - Parameters:
    ///   - prices: Array of closing prices.
    ///   - fastPeriod: Short EMA period (typically 12).
    ///   - slowPeriod: Long EMA period (typically 26).
    ///   - signalPeriod: Signal EMA period (typically 9).
    /// - Returns: A ``MACDResult`` containing all three series.
    static func computeMACD(_ prices: [Double],
                            fastPeriod: Int = 12,
                            slowPeriod: Int = 26,
                            signalPeriod: Int = 9) -> MACDResult {
        let n = prices.count
        let fastEMA = computeEMA(prices, period: fastPeriod)
        let slowEMA = computeEMA(prices, period: slowPeriod)

        // MACD line: valid wherever both EMAs are valid
        var macd: [Double?] = Array(repeating: nil, count: n)
        for i in 0..<n {
            if let fast = fastEMA[i], let slow = slowEMA[i] {
                macd[i] = fast - slow
            }
        }

        // Signal line: EMA of the MACD line
        let signal = computeEMAOnPartialSeries(macd, period: signalPeriod)

        // Histogram: valid where both MACD and signal are valid
        var histogram: [Double?] = Array(repeating: nil, count: n)
        for i in 0..<n {
            if let m = macd[i], let s = signal[i] {
                histogram[i] = m - s
            }
        }

        return MACDResult(macdLine: macd, signalLine: signal, histogram: histogram)
    }

    // MARK: - EMA (private)

    /// Computes an EMA seeded with the SMA of the first `period` bars.
    private static func computeEMA(_ prices: [Double], period: Int) -> [Double?] {
        var ema: [Double?] = Array(repeating: nil, count: prices.count)
        guard prices.count >= period else { return ema }

        // Seed with simple average of first 'period' bars
        var sum = 0.0
        for i in 0..<period { sum += prices[i] }
        ema[period - 1] = sum / Double(period)

        let k = 2.0 / Double(period + 1)
        for i in period..<prices.count {
            ema[i] = prices[i] * k + ema[i - 1]! * (1 - k)
        }
        return ema
    }

    /// EMA on a series that starts with `nil` values (e.g. the MACD line).
    private static func computeEMAOnPartialSeries(_ values: [Double?], period: Int) -> [Double?] {
        var ema: [Double?] = Array(repeating: nil, count: values.count)

        // Find first valid index
        guard let firstValid = values.firstIndex(where: { $0 != nil }) else { return ema }
        guard firstValid + period <= values.count else { return ema }

        // Seed with simple average of first 'period' valid values
        var sum = 0.0
        for i in firstValid..<(firstValid + period) { sum += values[i]! }
        let seedIdx = firstValid + period - 1
        ema[seedIdx] = sum / Double(period)

        let k = 2.0 / Double(period + 1)
        for i in (seedIdx + 1)..<values.count {
            guard let value = values[i] else {
                ema[i] = nil
                continue
            }
            ema[i] = value * k + ema[i - 1]! * (1 - k)
        }
        return ema
    }

    // MARK: - Simple Moving Average

    /// Computes a Simple Moving Average (SMA) over a rolling window.
    ///
    /// Uses a running sum updated by adding the newest bar and subtracting
    /// the bar that fell out of the window.
    ///
    /// - Parameters:
    ///   - prices: Array of closing prices.
    ///   - period: Number of bars in the window (e.g. 20 or 50).
    /// - Returns: Array of SMA values, same length as `prices`.
    static func computeMovingAverage(_ prices: [Double], period: Int) -> [Double?] {
        var ma: [Double?] = Array(repeating: nil, count: prices.count)
        var runningSum = 0.0

        for i in 0..<prices.count {
            runningSum += prices[i]
            if i >= period { runningSum -= prices[i - period] }
            if i >= period - 1 { ma[i] = runningSum / Double(period) }
        }
        return ma
    }

    // MARK: - RSI

    /// Computes the Relative Strength Index using Wilder's smoothing.
    ///
    /// - Parameters:
    ///   - prices: Array of closing prices.
    ///   - period: Lookback window (typically 14).
    /// - Returns: Array of RSI values in [0, 100], same length as `prices`.
    static func computeRSI(_ prices: [Double], period: Int = 14) -> [Double?] {
        var rsi: [Double?] = Array(repeating: nil, count: prices.count)
        guard prices.count > period else { return rsi }

        // Seeding phase: simple average of first 'period' up/down moves
        var seedGain = 0.0, seedLoss = 0.0
        for i in 1...period {
            let change = prices[i] - prices[i - 1]
            if change > 0 { seedGain += change }
            else           { seedLoss -= change }
        }

        var avgGain = seedGain / Double(period)
        var avgLoss = seedLoss / Double(period)
        rsi[period] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss)

        // Wilder smoothing for remaining bars
        for i in (period + 1)..<prices.count {
            let change = prices[i] - prices[i - 1]
            let barGain = max(change, 0)
            let barLoss = max(-change, 0)
            avgGain = (avgGain * Double(period - 1) + barGain) / Double(period)
            avgLoss = (avgLoss * Double(period - 1) + barLoss) / Double(period)
            rsi[i] = avgLoss == 0 ? 100 : 100 - 100.0 / (1 + avgGain / avgLoss)
        }
        return rsi
    }
}
