import java.io.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages a persistent list of saved stock tickers (the "watchlist").
 *
 * <p>Entries are serialised to {@value #SAVE_FILE} in the application's working
 * directory using Java object serialisation so that the list survives across
 * sessions.  Each {@link WatchlistEntry} also caches the last known price and
 * change percentage so the sidebar can show live data without a fetch.
 *
 * <h3>Thread safety</h3>
 * All mutations ({@link #add}, {@link #remove}, {@link #updateEntry}) are
 * intended to be called from the Swing Event Dispatch Thread.  {@link #save}
 * and {@link #load} perform blocking I/O; call them from a background thread
 * (or accept the brief pause — the watchlist file is tiny).
 */
public class WatchlistManager {

    /** File name written to the current working directory. */
    private static final String SAVE_FILE =
            System.getProperty("user.dir") + "/watchlist.dat";

    /** In-memory list of watched tickers, preserved across saves. */
    private final List<WatchlistEntry> watchedTickers = new ArrayList<>();

    // =========================================================================
    // Inner model class
    // =========================================================================

    /**
     * Represents a single entry in the watchlist.
     * Serializable so it can be persisted with {@link ObjectOutputStream}.
     */
    public static class WatchlistEntry implements Serializable {

        private static final long serialVersionUID = 2L;

        /** Upper-case ticker symbol, e.g. "AAPL". */
        public String ticker;

        /**
         * Most recently fetched closing price.
         * {@code 0} means we haven't fetched a price yet (display as "—").
         */
        public double lastKnownPrice;

        /**
         * Most recently fetched daily change percentage (e.g. 2.35 for +2.35 %).
         * {@code Double.NaN} means no price has been fetched yet.
         */
        public double lastKnownChangePercent;

        /** ISO 4217 currency code for {@link #lastKnownPrice}, e.g. "USD". */
        public String currency;

        /**
         * Price level at which a price-alert should fire.
         * {@code Double.NaN} means no alert is set for this entry.
         * The alert fires when {@link #lastKnownPrice} first reaches or exceeds
         * this value, after which it is automatically reset to NaN.
         */
        public double alertPrice = Double.NaN;

        public WatchlistEntry(String ticker) {
            this.ticker                = ticker;
            this.lastKnownPrice        = 0;
            this.lastKnownChangePercent = Double.NaN;
            this.currency              = "USD";
        }
    }

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Adds {@code ticker} to the watchlist if it isn't already present
     * (comparison is case-insensitive), then persists the updated list.
     */
    public void add(String ticker) {
        String upperTicker = ticker.trim().toUpperCase();
        boolean alreadyTracked = watchedTickers.stream()
                .anyMatch(e -> e.ticker.equalsIgnoreCase(upperTicker));
        if (!alreadyTracked) {
            watchedTickers.add(new WatchlistEntry(upperTicker));
            save();
        }
    }

    /**
     * Removes the entry with the given ticker (case-insensitive) and persists
     * the updated list.  Does nothing if the ticker is not in the list.
     */
    public void remove(String ticker) {
        watchedTickers.removeIf(e -> e.ticker.equalsIgnoreCase(ticker));
        save();
    }

    /**
     * Sets a price alert for the given ticker.  The alert fires the next time
     * the ticker's price reaches or exceeds {@code price}.
     * Does nothing if the ticker is not in the watchlist.
     */
    public void setAlert(String ticker, double price) {
        for (WatchlistEntry entry : watchedTickers) {
            if (entry.ticker.equalsIgnoreCase(ticker)) {
                entry.alertPrice = price;
                save();
                return;
            }
        }
    }

    /**
     * Clears any active price alert for the given ticker.
     * Does nothing if the ticker is not in the watchlist or has no alert set.
     */
    public void clearAlert(String ticker) {
        for (WatchlistEntry entry : watchedTickers) {
            if (entry.ticker.equalsIgnoreCase(ticker)) {
                entry.alertPrice = Double.NaN;
                save();
                return;
            }
        }
    }

    /**
     * Updates the cached price data for an existing entry.
     * Called after a background price refresh.  Does not save automatically —
     * the caller is responsible for calling {@link #save()} when done.
     */
    public void updateEntry(String ticker, double price,
                            double changePercent, String currency) {
        for (WatchlistEntry entry : watchedTickers) {
            if (entry.ticker.equalsIgnoreCase(ticker)) {
                entry.lastKnownPrice        = price;
                entry.lastKnownChangePercent = changePercent;
                entry.currency              = currency != null ? currency : "USD";
                return;
            }
        }
    }

    /**
     * Returns a defensive copy of the current watchlist so callers cannot
     * accidentally mutate the internal list.
     */
    public List<WatchlistEntry> getEntries() {
        return new ArrayList<>(watchedTickers);
    }

    // =========================================================================
    // Persistence
    // =========================================================================

    /**
     * Serialises the watchlist to {@value #SAVE_FILE}.
     * Errors are logged to stderr but not rethrown — a failed save is not
     * fatal; the user simply loses persistence for that session.
     */
    public void save() {
        try (ObjectOutputStream out =
                     new ObjectOutputStream(new FileOutputStream(SAVE_FILE))) {
            out.writeObject(new ArrayList<>(watchedTickers));
        } catch (IOException e) {
            System.err.println("[WatchlistManager] Failed to save: " + e.getMessage());
        }
    }

    /**
     * Deserialises the watchlist from {@value #SAVE_FILE}.
     * If the file doesn't exist or can't be read (e.g. class version changed),
     * the watchlist starts empty and the error is logged to stderr.
     */
    @SuppressWarnings("unchecked")
    public void load() {
        File saveFile = new File(SAVE_FILE);
        if (!saveFile.exists()) return;

        try (ObjectInputStream in =
                     new ObjectInputStream(new FileInputStream(saveFile))) {
            List<WatchlistEntry> saved = (List<WatchlistEntry>) in.readObject();
            watchedTickers.clear();
            watchedTickers.addAll(saved);
        } catch (Exception e) {
            System.err.println("[WatchlistManager] Failed to load: " + e.getMessage());
        }
    }
}
