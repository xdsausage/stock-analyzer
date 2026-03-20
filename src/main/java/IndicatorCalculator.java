import java.util.Arrays;

/**
 * Pure static utility class for computing technical indicators from a price
 * series.  All methods are O(n) single-pass algorithms and produce output
 * arrays of the same length as the input, filling the warm-up period with
 * {@link Double#NaN} so callers can safely skip those slots when drawing.
 *
 * <p>None of these methods modify their inputs or hold any state.
 */
public class IndicatorCalculator {

    private IndicatorCalculator() { /* not instantiable */ }

    // =========================================================================
    // MACD — Moving Average Convergence/Divergence
    // =========================================================================

    /**
     * Holds the three output series produced by {@link #computeMACD}.
     *
     * @param macdLine   MACD line  = fast EMA − slow EMA
     * @param signalLine Signal line = 9-period EMA of the MACD line
     * @param histogram  Histogram   = MACD line − signal line
     */
    public record MACDData(double[] macdLine, double[] signalLine, double[] histogram) {}

    /**
     * Computes the MACD indicator using standard parameters (12/26/9 by default).
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Fast EMA ({@code fastPeriod}) and slow EMA ({@code slowPeriod}) of prices.</li>
     *   <li>MACD line = fast EMA − slow EMA; valid only once the slow EMA is seeded
     *       (index {@code slowPeriod − 1}).</li>
     *   <li>Signal line = {@code signalPeriod}-EMA of the MACD line; valid at
     *       index {@code slowPeriod + signalPeriod − 2}.</li>
     *   <li>Histogram = MACD line − signal line.</li>
     * </ol>
     *
     * <p>All three output arrays have the same length as {@code prices}.
     * Warm-up positions are filled with {@link Double#NaN}.
     *
     * @param prices       array of closing prices
     * @param fastPeriod   short EMA period, typically 12
     * @param slowPeriod   long  EMA period, typically 26
     * @param signalPeriod signal EMA period, typically 9
     * @return {@link MACDData} containing all three series
     */
    public static MACDData computeMACD(double[] prices,
                                       int fastPeriod, int slowPeriod, int signalPeriod) {
        int n = prices.length;
        double[] fastEMA = computeEMA(prices, fastPeriod);
        double[] slowEMA = computeEMA(prices, slowPeriod);

        // MACD line: valid wherever both EMAs are valid (slow EMA is the bottleneck)
        double[] macd = new double[n];
        Arrays.fill(macd, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(fastEMA[i]) && !Double.isNaN(slowEMA[i])) {
                macd[i] = fastEMA[i] - slowEMA[i];
            }
        }

        // Signal line: EMA of the MACD line, seeded from the first valid MACD value
        double[] signal = computeEMAOnPartialSeries(macd, signalPeriod);

        // Histogram: valid only where both MACD and signal are valid
        double[] histogram = new double[n];
        Arrays.fill(histogram, Double.NaN);
        for (int i = 0; i < n; i++) {
            if (!Double.isNaN(macd[i]) && !Double.isNaN(signal[i])) {
                histogram[i] = macd[i] - signal[i];
            }
        }

        return new MACDData(macd, signal, histogram);
    }

    /**
     * Computes an Exponential Moving Average seeded with the SMA of the first
     * {@code period} bars.  The first {@code period − 1} output values are
     * {@link Double#NaN}.
     *
     * <p>Multiplier = 2 / (period + 1), applied as:
     * {@code ema[i] = price[i] * k + ema[i-1] * (1 - k)}.
     */
    private static double[] computeEMA(double[] prices, int period) {
        double[] ema = new double[prices.length];
        Arrays.fill(ema, Double.NaN);
        if (prices.length < period) return ema;

        // Seed with the simple average of the first 'period' bars
        double sum = 0;
        for (int i = 0; i < period; i++) sum += prices[i];
        ema[period - 1] = sum / period;

        double k = 2.0 / (period + 1);
        for (int i = period; i < prices.length; i++) {
            ema[i] = prices[i] * k + ema[i - 1] * (1 - k);
        }
        return ema;
    }

    /**
     * Like {@link #computeEMA} but operates on a series that starts with
     * {@link Double#NaN} values (e.g. the MACD line).  Finds the first
     * non-NaN position, seeds the EMA from there, and leaves all preceding
     * positions as {@link Double#NaN}.
     */
    private static double[] computeEMAOnPartialSeries(double[] values, int period) {
        double[] ema = new double[values.length];
        Arrays.fill(ema, Double.NaN);

        // Find the first valid (non-NaN) index
        int firstValid = -1;
        for (int i = 0; i < values.length; i++) {
            if (!Double.isNaN(values[i])) { firstValid = i; break; }
        }
        if (firstValid < 0 || firstValid + period > values.length) return ema;

        // Seed with the simple average of the first 'period' valid values
        double sum = 0;
        for (int i = firstValid; i < firstValid + period; i++) sum += values[i];
        int seedIdx = firstValid + period - 1;
        ema[seedIdx] = sum / period;

        double k = 2.0 / (period + 1);
        for (int i = seedIdx + 1; i < values.length; i++) {
            if (Double.isNaN(values[i])) { ema[i] = Double.NaN; continue; }
            ema[i] = values[i] * k + ema[i - 1] * (1 - k);
        }
        return ema;
    }

    // =========================================================================
    // Moving Average
    // =========================================================================

    /**
     * Computes a Simple Moving Average (SMA) over a rolling window of
     * {@code period} bars.
     *
     * <p>The first {@code period - 1} output slots are {@link Double#NaN}
     * because there aren't enough preceding bars to fill the window.  The first
     * valid value appears at index {@code period - 1}.
     *
     * <p>Uses a running sum that is updated by adding the newest bar and
     * subtracting the bar that just fell out of the window — one addition and
     * one subtraction per step rather than re-summing the entire window.
     *
     * @param prices array of closing prices, length &ge; 1
     * @param period number of bars in the moving-average window (e.g. 20 or 50)
     * @return array of SMA values, same length as {@code prices}
     */
    public static double[] computeMovingAverage(double[] prices, int period) {
        double[] movingAverage = new double[prices.length];
        Arrays.fill(movingAverage, Double.NaN);

        double runningSum = 0;
        for (int i = 0; i < prices.length; i++) {
            runningSum += prices[i];
            // Once the window is full, remove the bar that dropped off the left edge
            if (i >= period) runningSum -= prices[i - period];
            // First valid average: when we have exactly 'period' bars
            if (i >= period - 1) movingAverage[i] = runningSum / period;
        }
        return movingAverage;
    }

    // =========================================================================
    // RSI — Relative Strength Index
    // =========================================================================

    /**
     * Computes the Relative Strength Index (RSI) using Wilder's exponential
     * smoothing method over a {@code period}-bar lookback (standard is 14).
     *
     * <h3>Algorithm</h3>
     * <ol>
     *   <li>Seed: compute the simple average gain and average loss over the first
     *       {@code period} price changes.</li>
     *   <li>Subsequent bars: apply Wilder's smoothing —
     *       {@code avgGain = (avgGain × (period−1) + gain) / period}.</li>
     *   <li>RSI = 100 − 100 / (1 + avgGain / avgLoss).  When {@code avgLoss}
     *       is zero the result is clamped to 100 (all-up period).</li>
     * </ol>
     *
     * <p>The first {@code period} output slots are {@link Double#NaN} because
     * the seeding phase requires {@code period} price differences, which in
     * turn requires {@code period + 1} prices.
     *
     * @param prices array of closing prices, length &ge; 2
     * @param period lookback window, typically 14
     * @return array of RSI values in the range [0, 100], same length as {@code prices}
     */
    public static double[] computeRSI(double[] prices, int period) {
        double[] rsiValues = new double[prices.length];
        Arrays.fill(rsiValues, Double.NaN);

        if (prices.length <= period) return rsiValues; // not enough data

        // --- Seeding phase: simple average of first 'period' up/down moves ---
        double seedGain = 0, seedLoss = 0;
        for (int i = 1; i <= period; i++) {
            double change = prices[i] - prices[i - 1];
            if (change > 0) seedGain += change;
            else            seedLoss -= change; // store as positive number
        }
        double avgGain = seedGain / period;
        double avgLoss = seedLoss / period;
        rsiValues[period] = avgLoss == 0 ? 100
                : 100 - 100.0 / (1 + avgGain / avgLoss);

        // --- Wilder smoothing for remaining bars ---
        for (int i = period + 1; i < prices.length; i++) {
            double change    = prices[i] - prices[i - 1];
            double barGain   = Math.max(change, 0);   // positive move, or 0
            double barLoss   = Math.max(-change, 0);  // negative move (stored positive), or 0
            avgGain = (avgGain * (period - 1) + barGain) / period;
            avgLoss = (avgLoss * (period - 1) + barLoss) / period;
            rsiValues[i] = avgLoss == 0 ? 100
                    : 100 - 100.0 / (1 + avgGain / avgLoss);
        }
        return rsiValues;
    }
}
