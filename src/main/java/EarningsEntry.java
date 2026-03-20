/**
 * Data for one upcoming (or recently-past) earnings event.
 *
 * <p>Fields that are unknown or not yet reported are stored as
 * {@link Double#NaN} so callers can display "N/A" without special-casing nulls.
 *
 * @param ticker        upper-case ticker symbol, e.g. "AAPL"
 * @param companyName   long company name, or ticker if unavailable
 * @param earningsDate  Unix epoch seconds of the expected earnings date; 0 if unknown
 * @param earningsTime  "BMO" (before open), "AMC" (after close), or "—" if unknown
 * @param epsEstimate   analyst consensus EPS estimate; {@link Double#NaN} if unavailable
 * @param epsActual     reported EPS; {@link Double#NaN} if not yet announced
 */
public record EarningsEntry(
        String ticker,
        String companyName,
        long   earningsDate,
        String earningsTime,
        double epsEstimate,
        double epsActual
) {}
