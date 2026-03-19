public record OptionsContract(
    String contractSymbol,
    double strike,
    double lastPrice,
    double bid,
    double ask,
    double change,
    double changePercent,
    int    volume,
    int    openInterest,
    double impliedVolatility,
    boolean inTheMoney,
    long   expiration
) {}
