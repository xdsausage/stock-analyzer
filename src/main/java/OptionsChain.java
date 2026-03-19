import java.util.List;

public class OptionsChain {
    public final String ticker;
    public final double underlyingPrice;
    public final long[] expirationDates;
    public final List<OptionsContract> calls;
    public final List<OptionsContract> puts;

    public OptionsChain(String ticker, double underlyingPrice,
                        long[] expirationDates,
                        List<OptionsContract> calls, List<OptionsContract> puts) {
        this.ticker          = ticker;
        this.underlyingPrice = underlyingPrice;
        this.expirationDates = expirationDates;
        this.calls           = calls;
        this.puts            = puts;
    }
}
