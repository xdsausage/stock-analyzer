public record OptionAnalysis(
    boolean call,
    double midpoint,
    double spread,
    double spreadPercent,
    double breakEven,
    double signedStrikeDistancePercent,
    double intrinsicValue,
    double extrinsicValue,
    double daysToExpiration,
    double timeToExpirationYears,
    double delta,
    double gamma,
    double thetaPerDay,
    double vegaPerVolPoint,
    double probabilityInTheMoney
) {}
