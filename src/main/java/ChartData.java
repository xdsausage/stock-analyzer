/**
 * Immutable container for a single price chart series fetched from the API.
 *
 * All three arrays are parallel — index {@code i} represents the same bar across
 * all three.  Volumes may be all-zero when the API does not return volume data
 * for a particular interval (e.g. some extended-hours sessions).
 */
public class ChartData {

    /** Unix timestamps (seconds since epoch) for each bar, sorted ascending. */
    public final long[] timestamps;

    /** Closing price for each bar, in the asset's native currency. */
    public final double[] prices;

    /**
     * Trading volume for each bar.  A value of {@code 0} means the API did not
     * provide volume for that bar rather than that no shares traded.
     */
    public final long[] volumes;

    public ChartData(long[] timestamps, double[] prices, long[] volumes) {
        this.timestamps = timestamps;
        this.prices     = prices;
        this.volumes    = volumes;
    }
}
