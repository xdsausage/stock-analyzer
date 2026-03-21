import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class OptionsAnalytics {

    private static final double SECONDS_PER_YEAR = 365.25 * 24 * 60 * 60;
    private static final double MIN_TIME_YEARS = 1.0 / (365.25 * 24.0);

    private OptionsAnalytics() {}

    public static OptionAnalysis analyze(OptionsContract contract, double spotPrice,
                                         long valuationEpochSeconds, boolean isCall) {
        if (contract == null || spotPrice <= 0 || contract.strike() <= 0) {
            return new OptionAnalysis(isCall, 0, 0, Double.NaN, Double.NaN, Double.NaN,
                    0, 0, 0, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double midpoint = midpoint(contract);
        double spread = Math.max(contract.ask() - contract.bid(), 0.0);
        double spreadPercent = midpoint > 0 ? (spread / midpoint) * 100.0 : Double.NaN;
        double intrinsic = isCall
                ? Math.max(spotPrice - contract.strike(), 0.0)
                : Math.max(contract.strike() - spotPrice, 0.0);
        double premiumBasis = midpoint > 0 ? midpoint : Math.max(contract.lastPrice(), 0.0);
        double extrinsic = Math.max(premiumBasis - intrinsic, 0.0);
        double breakEven = isCall
                ? contract.strike() + premiumBasis
                : contract.strike() - premiumBasis;
        double signedDistance = ((contract.strike() - spotPrice) / spotPrice) * 100.0;

        double secondsToExpiration = Math.max(contract.expiration() - valuationEpochSeconds, 0L);
        double yearsToExpiration = secondsToExpiration / SECONDS_PER_YEAR;
        double effectiveYears = Math.max(yearsToExpiration, MIN_TIME_YEARS);
        double daysToExpiration = yearsToExpiration * 365.25;

        double iv = contract.impliedVolatility();
        if (iv <= 0) {
            return new OptionAnalysis(isCall, midpoint, spread, spreadPercent, breakEven,
                    signedDistance, intrinsic, extrinsic, daysToExpiration, yearsToExpiration,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }

        double sqrtT = Math.sqrt(effectiveYears);
        double d1 = (Math.log(spotPrice / contract.strike()) + 0.5 * iv * iv * effectiveYears)
                / (iv * sqrtT);
        double d2 = d1 - iv * sqrtT;
        double pdf = normalPdf(d1);

        double delta = isCall ? normalCdf(d1) : normalCdf(d1) - 1.0;
        double gamma = pdf / (spotPrice * iv * sqrtT);
        double thetaAnnual = -(spotPrice * pdf * iv) / (2.0 * sqrtT);
        double thetaPerDay = thetaAnnual / 365.25;
        double vegaPerVolPoint = (spotPrice * pdf * sqrtT) / 100.0;
        double probabilityItm = isCall ? normalCdf(d2) : normalCdf(-d2);

        return new OptionAnalysis(isCall, midpoint, spread, spreadPercent, breakEven,
                signedDistance, intrinsic, extrinsic, daysToExpiration, yearsToExpiration,
                delta, gamma, thetaPerDay, vegaPerVolPoint, probabilityItm);
    }

    public static OptionsChainSummary summarize(OptionsChain chain, long expiration,
                                                long valuationEpochSeconds) {
        List<OptionsContract> calls = contractsForExpiration(chain.calls, expiration);
        List<OptionsContract> puts = contractsForExpiration(chain.puts, expiration);

        long callVolume = calls.stream().mapToLong(OptionsContract::volume).sum();
        long putVolume = puts.stream().mapToLong(OptionsContract::volume).sum();
        long callOi = calls.stream().mapToLong(OptionsContract::openInterest).sum();
        long putOi = puts.stream().mapToLong(OptionsContract::openInterest).sum();

        double yearsToExpiration = Math.max(expiration - valuationEpochSeconds, 0L) / SECONDS_PER_YEAR;
        double daysToExpiration = yearsToExpiration * 365.25;

        double atmStrike = findAtmStrike(chain.underlyingPrice, calls, puts);
        OptionsContract atmCall = findNearestStrike(calls, atmStrike);
        OptionsContract atmPut = findNearestStrike(puts, atmStrike);
        double atmCallMid = midpoint(atmCall);
        double atmPutMid = midpoint(atmPut);
        double atmStraddle = atmCallMid + atmPutMid;
        double impliedMovePercent = chain.underlyingPrice > 0
                ? (atmStraddle / chain.underlyingPrice) * 100.0
                : Double.NaN;
        double maxPainStrike = computeMaxPain(calls, puts);

        return new OptionsChainSummary(
                calls.size(),
                puts.size(),
                callVolume,
                putVolume,
                callOi,
                putOi,
                daysToExpiration,
                atmStrike,
                atmStraddle,
                atmStraddle,       // impliedMoveAmount = ATM straddle price
                impliedMovePercent,
                maxPainStrike
        );
    }

    public static double midpoint(OptionsContract contract) {
        if (contract == null) return 0.0;
        if (contract.bid() > 0 && contract.ask() > 0 && contract.ask() >= contract.bid()) {
            return (contract.bid() + contract.ask()) / 2.0;
        }
        if (contract.lastPrice() > 0) return contract.lastPrice();
        if (contract.bid() > 0) return contract.bid();
        if (contract.ask() > 0) return contract.ask();
        return 0.0;
    }

    private static List<OptionsContract> contractsForExpiration(List<OptionsContract> contracts,
                                                                long expiration) {
        return contracts.stream()
                .filter(c -> c.expiration() == expiration)
                .collect(Collectors.toList());
    }

    private static double findAtmStrike(double spotPrice, List<OptionsContract> calls,
                                        List<OptionsContract> puts) {
        TreeSet<Double> strikes = new TreeSet<>();
        for (OptionsContract call : calls) strikes.add(call.strike());
        for (OptionsContract put : puts) strikes.add(put.strike());
        if (strikes.isEmpty()) return 0.0;

        double bestStrike = strikes.first();
        double bestDistance = Math.abs(bestStrike - spotPrice);
        for (double strike : strikes) {
            double distance = Math.abs(strike - spotPrice);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestStrike = strike;
            }
        }
        return bestStrike;
    }

    private static OptionsContract findNearestStrike(List<OptionsContract> contracts, double targetStrike) {
        if (contracts == null || contracts.isEmpty()) return null;
        OptionsContract best = contracts.get(0);
        double bestDistance = Math.abs(best.strike() - targetStrike);
        for (OptionsContract contract : contracts) {
            double distance = Math.abs(contract.strike() - targetStrike);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = contract;
            }
        }
        return best;
    }

    private static double computeMaxPain(List<OptionsContract> calls, List<OptionsContract> puts) {
        TreeSet<Double> candidateStrikes = new TreeSet<>();
        for (OptionsContract call : calls) candidateStrikes.add(call.strike());
        for (OptionsContract put : puts) candidateStrikes.add(put.strike());
        if (candidateStrikes.isEmpty()) return Double.NaN;

        double bestStrike = candidateStrikes.first();
        double lowestPayout = Double.POSITIVE_INFINITY;
        for (double settlement : candidateStrikes) {
            double payout = 0.0;
            for (OptionsContract call : calls) {
                payout += Math.max(settlement - call.strike(), 0.0)
                        * Math.max(call.openInterest(), 0) * 100.0;
            }
            for (OptionsContract put : puts) {
                payout += Math.max(put.strike() - settlement, 0.0)
                        * Math.max(put.openInterest(), 0) * 100.0;
            }
            if (payout < lowestPayout) {
                lowestPayout = payout;
                bestStrike = settlement;
            }
        }
        return bestStrike;
    }

    private static double normalPdf(double x) {
        return Math.exp(-0.5 * x * x) / Math.sqrt(2.0 * Math.PI);
    }

    private static double normalCdf(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }

    /**
     * Rational polynomial approximation of the error function with maximum
     * absolute error of 1.5×10⁻⁷.
     *
     * <p>Source: Abramowitz &amp; Stegun, <em>Handbook of Mathematical Functions</em>,
     * formula 7.1.26.
     */
    private static double erf(double x) {
        double sign = x < 0 ? -1.0 : 1.0;
        double ax = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * ax);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t)
                + 1.421413741) * t - 0.284496736) * t + 0.254829592)
                * t * Math.exp(-ax * ax);
        return sign * y;
    }
}
