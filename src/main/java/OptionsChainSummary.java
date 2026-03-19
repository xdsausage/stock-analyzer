public record OptionsChainSummary(
    int callCount,
    int putCount,
    long totalCallVolume,
    long totalPutVolume,
    long totalCallOpenInterest,
    long totalPutOpenInterest,
    double daysToExpiration,
    double atmStrike,
    double atmStraddleMidpoint,
    double impliedMoveAmount,
    double impliedMovePercent,
    double maxPainStrike
) {}
