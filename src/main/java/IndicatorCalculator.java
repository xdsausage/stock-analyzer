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
