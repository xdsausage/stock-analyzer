import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a persistent portfolio of stock positions.
 *
 * <p>Positions are serialised to {@value #SAVE_FILE} in the application's working
 * directory using Java object serialisation so they survive across sessions.
 * Each {@link PortfolioPosition} stores the static purchase data (shares owned,
 * average buy price); {@code currentPrice} is a {@code transient} field updated
 * at runtime from live API data.
 *
 * <h3>Cost averaging</h3>
 * When {@link #add} is called for a ticker that is already in the portfolio, the
 * new shares and price are merged using the weighted-average-cost method so the
 * overall position grows rather than being replaced.
 *
 * <h3>Thread safety</h3>
 * All mutations are intended to be called from the Swing Event Dispatch Thread.
 * {@link #save} and {@link #load} perform blocking I/O.
 */
public class PortfolioManager {

    /** File name written to the current working directory. */
    private static final String SAVE_FILE =
            System.getProperty("user.dir") + "/portfolio.dat";

    /** In-memory list of portfolio positions. */
    private final List<PortfolioPosition> positions = new ArrayList<>();

    // =========================================================================
    // Inner model class
    // =========================================================================

    /**
     * Represents one stock position in the portfolio.
     * Serializable so it can be persisted with {@link ObjectOutputStream}.
     */
    public static class PortfolioPosition implements Serializable {

        private static final long serialVersionUID = 1L;

        /** Upper-case ticker symbol, e.g. "AAPL". */
        public String ticker;

        /** Number of shares held. */
        public double sharesOwned;

        /** Weighted-average purchase price per share in {@link #currency}. */
        public double averageBuyPrice;

        /** ISO 4217 currency code, e.g. "USD". */
        public String currency;

        /**
         * Live current price per share — NOT serialised because it changes
         * every session and must be fetched fresh from the API at runtime.
         */
        public transient double currentPrice = 0;

        public PortfolioPosition(String ticker, double sharesOwned,
                                 double averageBuyPrice, String currency) {
            this.ticker          = ticker;
            this.sharesOwned     = sharesOwned;
            this.averageBuyPrice = averageBuyPrice;
            this.currency        = currency != null ? currency : "USD";
        }

        // ---- Derived metrics ------------------------------------------------

        /** Current market value of this position (shares × current price). */
        public double marketValue() {
            return sharesOwned * currentPrice;
        }

        /** Unrealised gain/loss in currency units. */
        public double unrealizedGain() {
            return sharesOwned * (currentPrice - averageBuyPrice);
        }

        /**
         * Unrealised gain/loss as a percentage of total cost basis.
         * Returns 0 when average buy price is zero to avoid division by zero.
         */
        public double unrealizedGainPercent() {
            if (averageBuyPrice == 0) return 0;
            return (currentPrice - averageBuyPrice) / averageBuyPrice * 100.0;
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Adds a new position (or updates an existing one) for the given ticker.
     * If the ticker is already in the portfolio, the new shares and cost are
     * merged using the weighted-average-cost method.
     *
     * @param ticker       upper-case ticker symbol
     * @param shares       number of shares to add
     * @param avgBuyPrice  purchase price per share
     * @param currency     ISO 4217 currency code, e.g. "USD"
     */
    public void add(String ticker, double shares, double avgBuyPrice, String currency) {
        String upper = ticker.trim().toUpperCase();
        for (PortfolioPosition pos : positions) {
            if (pos.ticker.equalsIgnoreCase(upper)) {
                // Weighted average cost: combine existing and new cost bases
                double totalCost   = pos.sharesOwned * pos.averageBuyPrice
                                   + shares        * avgBuyPrice;
                pos.sharesOwned   += shares;
                pos.averageBuyPrice = totalCost / pos.sharesOwned;
                if (currency != null) pos.currency = currency;
                save();
                return;
            }
        }
        positions.add(new PortfolioPosition(upper, shares, avgBuyPrice, currency));
        save();
    }

    /**
     * Removes the position for the given ticker.
     * Does nothing if the ticker is not in the portfolio.
     */
    public void remove(String ticker) {
        positions.removeIf(p -> p.ticker.equalsIgnoreCase(ticker));
        save();
    }

    /**
     * Returns a defensive copy of the current positions list so callers cannot
     * accidentally mutate the internal state.
     */
    public List<PortfolioPosition> getPositions() {
        return new ArrayList<>(positions);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Serialises the portfolio to {@value #SAVE_FILE}.
     * Errors are logged to stderr but not rethrown.
     */
    public void save() {
        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream(SAVE_FILE))) {
            out.writeObject(new ArrayList<>(positions));
        } catch (IOException e) {
            System.err.println("[PortfolioManager] Failed to save: " + e.getMessage());
        }
    }

    /**
     * Deserialises the portfolio from {@value #SAVE_FILE}.
     * If the file does not exist or cannot be read the portfolio starts empty.
     */
    @SuppressWarnings("unchecked")
    public void load() {
        File saveFile = new File(SAVE_FILE);
        if (!saveFile.exists()) return;
        try (ObjectInputStream in =
                     new ObjectInputStream(new FileInputStream(saveFile))) {
            List<PortfolioPosition> saved = (List<PortfolioPosition>) in.readObject();
            positions.clear();
            positions.addAll(saved);
        } catch (Exception e) {
            System.err.println("[PortfolioManager] Failed to load: " + e.getMessage());
        }
    }
}
