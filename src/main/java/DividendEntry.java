public record DividendEntry(
    String ticker,
    String companyName,
    long   exDividendDate,
    double dividendAmount,
    double dividendYield,
    String frequency
) {}
