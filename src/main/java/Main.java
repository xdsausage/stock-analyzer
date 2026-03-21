import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URI;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;
import java.util.List;
import java.util.Locale;
import java.util.function.*;

/**
 * Entry point and main window for the Stock Analyzer application.
 *
 * <p>The UI is a single Swing window composed of:
 * <ul>
 *   <li>A header bar with the app title.</li>
 *   <li>A search bar (ticker input + Analyze button + "← New" back button).</li>
 *   <li>A results panel (hidden until a stock is loaded) containing:
 *     <ul>
 *       <li>A hero card showing the company name, live price, and daily change.</li>
 *       <li>Four rows of stat cards (market data, valuation, risk, technicals).</li>
 *       <li>An interactive price chart with interval selector, MA/RSI toggles,
 *           and a comparison-ticker input.</li>
 *     </ul>
 *   </li>
 *   <li>A watchlist sidebar (right edge) showing saved tickers with live prices.</li>
 *   <li>A status bar at the bottom showing the last update time or error messages.</li>
 * </ul>
 *
 * <p>All network calls are executed on background {@link SwingWorker} threads so
 * the UI never freezes during fetches.
 */
public class Main {

    // =========================================================================
    // Visual design constants
    // =========================================================================

    // --- Colours are now provided by the ThemeColors record (see theme field) --

    // --- Fonts ----------------------------------------------------------------

    /** Large bold font for the app title in the header. */
    private static final Font HEADING_FONT      = new Font("Segoe UI", Font.BOLD, 22);

    /** Normal-weight font for body text and descriptions. */
    private static final Font BODY_FONT         = new Font("Segoe UI", Font.PLAIN, 13);

    /** Bold font for stat values shown in the metrics cards. */
    private static final Font STAT_VALUE_FONT   = new Font("Segoe UI", Font.BOLD, 14);

    /** Extra-large bold font for the hero price display. */
    private static final Font HERO_PRICE_FONT   = new Font("Segoe UI", Font.BOLD, 32);

    /** Small font for labels, timestamps, and secondary text. */
    private static final Font CAPTION_FONT      = new Font("Segoe UI", Font.PLAIN, 11);

    /** Monospace font used in ticker input fields for clarity. */
    private static final Font MONOSPACE_FONT    = new Font("Consolas", Font.PLAIN, 13);

    /** Standard font for action/interval buttons. */
    private static final Font BUTTON_FONT       = new Font("Segoe UI", Font.PLAIN, 12);

    /** Bold font for primary action buttons (e.g. Analyze). */
    private static final Font BUTTON_BOLD_FONT  = new Font("Segoe UI", Font.BOLD, 13);

    /** Bold caption font for small labels on coloured backgrounds. */
    private static final Font CAPTION_BOLD_FONT = new Font("Segoe UI", Font.BOLD, 11);

    // --- Layout spacing constants --------------------------------------------

    /** Vertical gap between major sections (e.g. chart card → notes card). */
    private static final int SECTION_GAP      = 12;

    /** Vertical gap between cards within a section (e.g. between stat rows). */
    private static final int CARD_GAP          = 8;

    /** Vertical inner padding inside cards. */
    private static final int CARD_PADDING_V    = 12;

    /** Horizontal inner padding inside cards. */
    private static final int CARD_PADDING_H    = 14;

    /** Top padding for tab content areas. */
    private static final int PAGE_PADDING_TOP  = 16;

    /** Gap below section headers before content begins. */
    private static final int HEADER_BOTTOM_GAP = 12;

    // =========================================================================
    // Chart interval configuration
    // =========================================================================

    /**
     * Supported chart time intervals.  Each row is a triple:
     * {@code { buttonLabel, yahooInterval, yahooRange }}.
     * <ul>
     *   <li>{@code yahooInterval} — bar size sent to the chart API, e.g. {@code "1d"}</li>
     *   <li>{@code yahooRange}    — total lookback sent to the chart API, e.g. {@code "1mo"}</li>
     * </ul>
     */
    /**
     * Supported chart time intervals.  Each row is a 4-tuple:
     * {@code { buttonLabel, yahooInterval, yahooRange, maxBars }}.
     * <ul>
     *   <li>{@code maxBars} — if non-null, the fetched data is trimmed to the
     *       last {@code maxBars} data points before display.  Used to carve
     *       intraday windows (e.g. the last 15 minutes) out of a full-day
     *       1-minute response.</li>
     * </ul>
     */
    private static final String[][] CHART_INTERVALS = {
        {"15M", "1m",  "1d",  "15"},   // last 15 minutes  — 1-min bars
        {"30M", "1m",  "1d",  "30"},   // last 30 minutes  — 1-min bars
        {"1H",  "1m",  "1d",  "60"},   // last 1 hour      — 1-min bars
        {"2H",  "2m",  "1d",  "60"},   // last 2 hours     — 2-min bars (60 × 2m = 120 min)
        {"1D",  "5m",  "1d",  null},   // full trading day — 5-min bars
        {"5D",  "15m", "5d",  null},   // 5 days           — 15-min bars
        {"1M",  "1d",  "1mo", null},   // 1 month          — daily bars
        {"3M",  "1d",  "3mo", null},
        {"6M",  "1d",  "6mo", null},
        {"1Y",  "1d",  "1y",  null},
    };

    /** Index into {@link #CHART_INTERVALS} shown by default when a ticker loads. */
    private static final int DEFAULT_INTERVAL_INDEX = 6; // 1M

    /** Commodity detail charts open to the 1D interval by default. */
    private static final int COMMODITY_DEFAULT_INTERVAL_INDEX = 4; // 1D

    /** Predefined screener sources exposed in the Screener tab. */
    private static final String[][] SCREENER_SOURCES = {
        {"Broad Market Blend",   "blend"},
        {"Day Gainers",          "day_gainers"},
        {"Day Losers",           "day_losers"},
        {"Most Active",          "most_actives"},
        {"Custom (Watchlist)",   "custom"},
    };

    /** Blend used for the default broad-market screener experience. */
    private static final String[] DEFAULT_SCREENER_BLEND = {
        "day_gainers", "day_losers", "most_actives"
    };

    private static final String[] OPTIONS_TYPE_FILTERS = {
        "All Contracts", "Calls Only", "Puts Only"
    };

    private static final String[] OPTIONS_MONEYNESS_FILTERS = {
        "All", "ITM", "ATM +/-2%", "OTM"
    };

    private static final String[] OPTIONS_SORT_MODES = {
        "Open Interest", "Volume", "IV %", "Nearest ATM", "Tightest Spread"
    };

    // =========================================================================
    // Commodities configuration
    // =========================================================================

    /** Commodity futures tickers and display names shown in the Commodities tab. */
    private static final String[][] COMMODITIES = {
        {"GC=F", "Gold"},
        {"SI=F", "Silver"},
        {"CL=F", "Crude Oil (WTI)"},
        {"NG=F", "Natural Gas"},
        {"ZC=F", "Corn"},
        {"ZW=F", "Wheat"},
        {"HG=F", "Copper"},
        {"KC=F", "Coffee"},
    };

    /** Crypto asset tickers (Yahoo Finance format) shown in the Crypto tab. */
    private static final String[][] CRYPTOS = {
        {"BTC-USD",  "Bitcoin"},
        {"ETH-USD",  "Ethereum"},
        {"BNB-USD",  "BNB"},
        {"SOL-USD",  "Solana"},
        {"XRP-USD",  "XRP"},
        {"DOGE-USD", "Dogecoin"},
        {"ADA-USD",  "Cardano"},
        {"AVAX-USD", "Avalanche"},
    };

    private static final String[][] SECTOR_ETFS = {
        {"XLK",  "Technology"},
        {"XLV",  "Health Care"},
        {"XLF",  "Financials"},
        {"XLE",  "Energy"},
        {"XLY",  "Cons. Discret."},
        {"XLP",  "Cons. Staples"},
        {"XLI",  "Industrials"},
        {"XLU",  "Utilities"},
        {"XLB",  "Materials"},
        {"XLRE", "Real Estate"},
        {"XLC",  "Comm. Services"},
    };

    private record ThemeColors(
        Color background, Color card, Color accent, Color primaryText,
        Color mutedText, Color gain, Color loss, Color border,
        Color btnBg, Color btnBorder, Color btnHoverBg, Color btnHoverBorder,
        Color volumeBar, Color btnFlatBorder,
        Color priceLineColor, Color ma20Color, Color ma50Color, Color comparisonLineColor,
        /** Background for selected rows/items in lists, tables, and combo boxes. */
        Color selectionBg,
        /** Background applied to rows and cards when the mouse hovers over them. */
        Color rowHoverBg
    ) {}

    private static final ThemeColors DARK_THEME = new ThemeColors(
        new Color(18,18,28),       // background
        new Color(28,28,44),       // card
        new Color(99,179,237),     // accent
        new Color(240,240,255),    // primaryText
        new Color(140,140,170),    // mutedText
        new Color(72,199,142),     // gain
        new Color(252,100,100),    // loss
        new Color(50,50,75),       // border
        new Color(36,36,56),       // btnBg         — subtle, matches dark-blue palette
        new Color(56,56,82),       // btnBorder     — visible but not harsh
        new Color(46,48,72),       // btnHoverBg    — noticeable lift on hover
        new Color(80,100,160),     // btnHoverBorder — shifts toward accent blue
        new Color(99,179,237,55),  // volumeBar
        new Color(44,44,64),       // btnFlatBorder  — softer for interval/toggle buttons
        new Color(120,200,255), new Color(255,165,0), new Color(180,100,255), new Color(255,200,50),
        new Color(42, 60, 96),     // selectionBg
        new Color(38, 38, 60)      // rowHoverBg
    );

    private static final ThemeColors LIGHT_THEME = new ThemeColors(
        new Color(242,244,248),    // background
        new Color(255,255,255),    // card
        new Color(30,100,195),     // accent
        new Color(20,20,40),       // primaryText
        new Color(100,100,120),    // mutedText
        new Color(0,150,80),       // gain
        new Color(200,30,30),      // loss
        new Color(210,215,225),    // border
        new Color(228,230,240),    // btnBg         — very light with blue tint
        new Color(190,195,210),    // btnBorder     — slightly more visible
        new Color(215,218,232),    // btnHoverBg    — gentle lift
        new Color(120,145,195),    // btnHoverBorder — shifts toward accent
        new Color(30,100,195,55),  // volumeBar
        new Color(200,204,218),    // btnFlatBorder  — softer for interval/toggle buttons
        new Color(0,80,180), new Color(200,120,0), new Color(130,50,200), new Color(180,140,0),
        new Color(195, 215, 245),  // selectionBg
        new Color(225, 230, 242)   // rowHoverBg
    );

    private ThemeColors theme = DARK_THEME;
    private boolean isDarkTheme = true;

    // =========================================================================
    // UI component references
    // =========================================================================

    // --- Top-level window and layout ------------------------------------------

    private JFrame     mainWindow;
    private JPanel     topBar;              // NORTH panel; alert banner lives here
    private JPanel     resultsPanel;         // hidden until a stock is successfully loaded
    private JLabel     statusLabel;         // status bar at the bottom of the window
    private SpinnerLabel statusSpinner;     // rotating spinner shown during fetches

    // --- Navigation bar & active tab tracking --------------------------------

    private final Map<String, JButton> navButtonMap = new LinkedHashMap<>();
    private String activeCardKey = "results";
    private JPanel navButtonsPanel;

    // --- Collapsible sidebar -------------------------------------------------

    private JPanel    watchlistSidebar;
    private JPanel    sidebarContent;
    private JButton   sidebarToggleBtn;
    private boolean   sidebarCollapsed = false;

    // --- Parallel fetch pool -------------------------------------------------

    private static final java.util.concurrent.ExecutorService FETCH_POOL =
            java.util.concurrent.Executors.newFixedThreadPool(4);

    // --- Ticker autocomplete -------------------------------------------------

    private final LinkedHashSet<String> recentTickers = new LinkedHashSet<>();

    // --- Center card panel (results ↔ portfolio toggle) ----------------------

    private CardLayout centerCardLayout;    // controls which view is visible in the center
    private JPanel     centerCardPanel;     // holds "results" and "portfolio" cards

    // --- Search bar -----------------------------------------------------------

    private JTextField tickerInputField;  // where the user types the ticker symbol
    private JButton    analyzeButton;     // triggers a new stock fetch
    private JButton    newSearchButton;   // clears results and returns to the empty state

    // --- Hero card labels (company name, price, change) -----------------------

    private JLabel companyNameLabel;   // e.g. "Apple Inc."
    private JLabel exchangeNameLabel;  // e.g. "NASDAQ"
    private JLabel heroPriceLabel;     // large price display, e.g. "USD 173.50"
    private JLabel dailyChangeLabel;   // e.g. "+1.23  (+0.71%)"

    // --- Market data stat card labels -----------------------------------------

    private JLabel marketCapLabel;         // total market capitalisation
    private JLabel currentVolumeLabel;     // today's trading volume
    private JLabel averageVolumeLabel;     // 3-month average daily volume

    // --- Valuation stat card labels -------------------------------------------

    private JLabel peRatioLabel;       // trailing 12-month P/E ratio
    private JLabel forwardPELabel;     // forward (next-12-month) P/E
    private JLabel epsLabel;           // earnings per share (TTM)

    // --- Risk & income stat card labels ---------------------------------------

    private JLabel betaLabel;          // market-relative volatility
    private JLabel dividendYieldLabel; // annual dividend yield as a percentage
    private JLabel priceToBookLabel;   // price-to-book ratio

    // --- Technical / range stat card labels -----------------------------------

    private JLabel weekHighLabel;          // 52-week high price
    private JLabel weekLowLabel;           // 52-week low price
    private JLabel fiftyDayAvgLabel;       // 50-day moving average
    private JLabel twoHundredDayAvgLabel;  // 200-day moving average

    // --- Chart section --------------------------------------------------------

    private StockChartPanel chartPanel;          // the custom-drawn chart component
    private JButton         selectedIntervalBtn; // currently highlighted interval button
    private JButton[]       intervalButtons;     // all interval buttons (for reset on new ticker)

    // --- Action buttons in the hero card (visible only when results are shown) -

    private JButton addToWatchlistButton; // saves the current ticker to the watchlist
    private JButton exportCsvButton;      // exports chart data to a CSV file

    // --- Comparison feature ---------------------------------------------------

    private JTextField comparisonTickerField; // second ticker for normalised chart overlay

    // --- Watchlist sidebar ---------------------------------------------------

    private WatchlistManager  watchlistManager;        // handles persistence
    private JPanel            watchlistItemsContainer; // BoxLayout panel rebuilt on each refresh
    private javax.swing.Timer watchlistRefreshTimer;   // fires every 60 s to update prices

    // --- In-app alert banner -------------------------------------------------

    private JPanel            alertBannerPanel;       // slides down from top when alert fires
    private JLabel            alertBannerLabel;       // message shown inside the banner
    private javax.swing.Timer alertDismissTimer;      // auto-hides the banner after 5 s

    // --- System tray icon (for background alerts) ----------------------------

    private TrayIcon systemTrayIcon;   // created once, reused for all tray notifications

    // --- News section --------------------------------------------------------

    private JPanel newsSectionContainer;  // BoxLayout Y panel rebuilt after each stock fetch

    // --- News tab ------------------------------------------------------------

    private JTextField         newsSearchField;
    private JLabel             newsTabStatusLabel;
    private JPanel             newsTopStoriesContainer;
    private JPanel             newsSearchResultsContainer;
    private String             currentNewsQuery;

    // --- Screener tab --------------------------------------------------------

    private JComboBox<String>  screenerSourceCombo;
    private JTextField         screenerMinMarketCapField;
    private JTextField         screenerMaxPeField;
    private JTextField         screenerMinVolumeField;
    private JTextField         screenerMinChangeField;
    private JTextField         screenerMaxBetaField;
    private JTextField         screenerMinDividendField;
    private JLabel             screenerStatusLabel;
    private JTable             screenerTable;
    private ScreenerTableModel screenerTableModel;
    private boolean            screenerHasLoaded = false;

    // --- Portfolio -----------------------------------------------------------

    private PortfolioManager portfolioManager;       // handles persistence
    private JPanel           portfolioRowsContainer; // BoxLayout Y panel of position rows
    private JLabel           portfolioTotalLabel;    // shows total value + overall P&L
    private PortfolioHistoryManager portfolioHistoryManager;
    private PortfolioChartPanel     portfolioChartPanel;
    private int                     portfolioChartRangeDays = 30;

    // --- Commodities tab -----------------------------------------------------

    private JPanel                commoditiesGrid;         // 4×2 GridLayout, rebuilt on refresh
    private JPanel                commodityDetailCard;     // inline detail panel shown below the grid
    private JLabel                commoditiesLastUpdated;  // "Updated HH:mm:ss"
    private javax.swing.Timer     commoditiesRefreshTimer; // 60 s auto-refresh
    private JLabel                commodityDetailTitleLabel;
    private JLabel                commodityDetailMetaLabel;
    private JLabel                commodityDetailPriceLabel;
    private JLabel                commodityDetailChangeLabel;
    private JLabel                commodityDetailRangeLabel;
    private StockChartPanel       commodityChartPanel;
    private JButton               selectedCommodityIntervalBtn;
    private JButton[]             commodityIntervalButtons;
    private JToggleButton         commodityMa20Toggle;
    private JToggleButton         commodityMa50Toggle;
    private JToggleButton         commodityRsiToggle;
    private final Map<String, CommoditySnapshot> commoditySnapshots = new LinkedHashMap<>();
    private String                selectedCommodityTicker;
    private String                selectedCommodityName;
    private String                currentCommodityBarInterval =
            CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][1];
    private String                currentCommodityTimeRange =
            CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][2];
    private int                   currentCommodityMaxBars = 0;

    /** Snapshot of a single commodity's live data used to build a card. */
    private record CommoditySnapshot(String ticker, String name,
            double price, double changePercent, double dayHigh, double dayLow,
            String currency, double[] sparkPrices) {}

    // --- Options tab ---------------------------------------------------------

    private JTextField              optionsTickerField;
    private JComboBox<String>       expirationCombo;
    private JLabel                  optionsStatusLabel;
    private JLabel                  optionsResultsLabel;
    private JLabel                  optionsUnderlyingValueLabel;
    private JLabel                  optionsDaysToExpiryValueLabel;
    private JLabel                  optionsAtmValueLabel;
    private JLabel                  optionsImpliedMoveValueLabel;
    private JLabel                  optionsPutCallRatioValueLabel;
    private JLabel                  optionsMaxPainValueLabel;
    private JLabel                  optionsIvRankValueLabel;
    private JComboBox<String>       optionsTypeFilterCombo;
    private JComboBox<String>       optionsMoneynessFilterCombo;
    private JComboBox<String>       optionsSortCombo;
    private JTextField              optionsMinVolumeField;
    private JTextField              optionsMinOpenInterestField;
    private JTextField              optionsMaxDistanceField;
    private JTable                  optionsContractsTable;
    private OptionsContractsTableModel optionsContractsTableModel;
    private JLabel                  optionsDetailTitleLabel;
    private JLabel                  optionsDetailSubtitleLabel;
    private JLabel                  optionsDetailPremiumLabel;
    private JLabel                  optionsDetailSpreadLabel;
    private JLabel                  optionsDetailBreakEvenLabel;
    private JLabel                  optionsDetailIntrinsicLabel;
    private JLabel                  optionsDetailExtrinsicLabel;
    private JLabel                  optionsDetailDistanceLabel;
    private JLabel                  optionsDetailLiquidityLabel;
    private JLabel                  optionsDetailDeltaProbLabel;
    private JLabel                  optionsDetailGammaVegaLabel;
    private JLabel                  optionsDetailThetaLabel;
    private JLabel                  optionsDetailCapitalLabel;
    private JTextArea               optionsDetailNotesArea;
    private OptionsChain            currentOptionsChain;
    private final Map<Long, OptionsChain> optionsChainCache = new HashMap<>();
    private final List<OptionTableRow>    currentOptionTableRows = new ArrayList<>();
    private OptionTableRow          selectedOptionRow;
    private String                  currentOptionsTicker;
    private long                    selectedExpiration = 0;
    private boolean                 optionsAdjustingExpiration = false;
    private OptionsPayoffPanel      optionsPayoffPanel;

    // --- Crypto tab ----------------------------------------------------------

    private JPanel                cryptoGrid;
    private JPanel                cryptoDetailCard;
    private JLabel                cryptoLastUpdated;
    private javax.swing.Timer     cryptoRefreshTimer;
    private JLabel                cryptoDetailTitleLabel;
    private JLabel                cryptoDetailMetaLabel;
    private JLabel                cryptoDetailPriceLabel;
    private JLabel                cryptoDetailChangeLabel;
    private JLabel                cryptoDetailRangeLabel;
    private StockChartPanel       cryptoChartPanel;
    private JButton               selectedCryptoIntervalBtn;
    private JButton[]             cryptoIntervalButtons;
    private JToggleButton         cryptoMa20Toggle;
    private JToggleButton         cryptoMa50Toggle;
    private JToggleButton         cryptoRsiToggle;
    private JToggleButton         cryptoMacdToggle;
    private final Map<String, CommoditySnapshot> cryptoSnapshots = new LinkedHashMap<>();
    private String                selectedCryptoTicker;
    private String                selectedCryptoName;
    private String                currentCryptoBarInterval =
            CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][1];
    private String                currentCryptoTimeRange =
            CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][2];
    private int                   currentCryptoMaxBars = 0;

    // --- Earnings Calendar tab -----------------------------------------------

    private JTable              earningsTable;
    private DefaultTableModel   earningsTableModel;
    private JLabel              earningsStatusLabel;

    // --- Sector Heatmap tab --------------------------------------------------
    private JPanel                heatmapGrid;
    private JPanel                heatmapDetailCard;
    private JLabel                heatmapLastUpdated;
    private javax.swing.Timer     heatmapRefreshTimer;
    private final Map<String, SectorSnapshot> sectorSnapshots = new LinkedHashMap<>();

    // --- Economic Calendar tab -----------------------------------------------
    private JTable            econTable;
    private DefaultTableModel econTableModel;
    private JLabel            econStatusLabel;
    private List<EconEvent>   allEconEvents;

    // --- Dividend Calendar tab -----------------------------------------------
    private JTable            dividendTable;
    private DefaultTableModel dividendTableModel;
    private JLabel            dividendStatusLabel;

    // --- Custom Screener -----------------------------------------------------
    private JPanel             customFiltersCard;

    // --- Records for new features --------------------------------------------
    private record SectorSnapshot(String etfTicker, String sectorName,
            double changePercent, double price) {}
    private record EconEvent(long eventDate, String eventTime, String eventName,
            String importance, String previous, String forecast, String actual) {}
    private enum FilterMetric { PE_RATIO, MARKET_CAP, VOLUME, CHANGE_PERCENT, RSI }
    private enum FilterOperator { GT, LT, BETWEEN }
    private record CustomFilterRow(FilterMetric metric, FilterOperator operator, double value1, double value2) {}

    // --- Stock Notes ---------------------------------------------------------

    private JTextArea   stockNotesArea;
    private JLabel      stockNotesStatusLabel;
    /** Notes persisted across sessions: ticker → note text. */
    private final java.util.Properties stockNotes = new java.util.Properties();
    private static final String NOTES_FILE = "notes.properties";

    // --- Active chart state ---------------------------------------------------

    /**
     * Ticker symbol currently displayed, or {@code null} when no results are shown.
     * Used to re-fetch the chart when the user switches interval or comparison.
     */
    private String currentTicker;

    /**
     * Yahoo Finance bar-size code for the currently selected interval,
     * e.g. {@code "1d"}.  Updated whenever the user clicks an interval button.
     */
    private String currentBarInterval = CHART_INTERVALS[DEFAULT_INTERVAL_INDEX][1];

    /**
     * Yahoo Finance range code for the currently selected interval,
     * e.g. {@code "1mo"}.  Updated whenever the user clicks an interval button.
     */
    private String currentTimeRange   = CHART_INTERVALS[DEFAULT_INTERVAL_INDEX][2];

    /**
     * Maximum number of data points to display for the current interval.
     * {@code 0} means no limit (show all fetched bars).
     * Set from the 4th column of {@link #CHART_INTERVALS} for short intraday windows.
     */
    private int currentMaxBars = 0;

    // =========================================================================
    // Application entry point
    // =========================================================================

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}
        updateUIManagerForTheme(DARK_THEME);
        SwingUtilities.invokeLater(() -> new Main().buildUI());
    }

    private static void updateUIManagerForTheme(ThemeColors tc) {
        Color bg     = tc.btnBg();
        Color cardBg = tc.card();
        Color text   = tc.primaryText();
        Color muted  = tc.mutedText();
        Color sel    = tc.selectionBg();
        Color border = tc.btnBorder();
        Color accent = tc.accent();
        Color tblBg  = tc.background();

        UIManager.put("ComboBox.background",           bg);
        UIManager.put("ComboBox.foreground",           text);
        UIManager.put("ComboBox.selectionBackground",  sel);
        UIManager.put("ComboBox.selectionForeground",  text);
        UIManager.put("ComboBox.buttonBackground",     bg);
        UIManager.put("ComboBox.disabledBackground",   cardBg);
        UIManager.put("ComboBox.disabledForeground",   muted);
        UIManager.put("List.background",               bg);
        UIManager.put("List.foreground",               text);
        UIManager.put("List.selectionBackground",      sel);
        UIManager.put("List.selectionForeground",      text);
        UIManager.put("Table.background",              tblBg);
        UIManager.put("Table.foreground",              text);
        UIManager.put("Table.selectionBackground",     sel);
        UIManager.put("Table.selectionForeground",     text);
        UIManager.put("Table.gridColor",               tc.border());
        UIManager.put("Table.focusCellBackground",     sel);
        UIManager.put("Table.focusCellForeground",     text);
        Color headerBg = new Color(
            Math.max(tblBg.getRed()-4,0), Math.max(tblBg.getGreen()-4,0), Math.max(tblBg.getBlue()-4,0));
        UIManager.put("TableHeader.background",        headerBg);
        UIManager.put("TableHeader.foreground",        accent);
        Color thumbColor = new Color(
            Math.min(tblBg.getRed()+37,255), Math.min(tblBg.getGreen()+37,255), Math.min(tblBg.getBlue()+47,255));
        Color thumbHi = new Color(
            Math.min(thumbColor.getRed()+20,255), Math.min(thumbColor.getGreen()+20,255), Math.min(thumbColor.getBlue()+25,255));
        Color thumbSh = new Color(
            Math.max(thumbColor.getRed()-20,0), Math.max(thumbColor.getGreen()-20,0), Math.max(thumbColor.getBlue()-20,0));
        UIManager.put("ScrollBar.background",          tblBg);
        UIManager.put("ScrollBar.thumb",               thumbColor);
        UIManager.put("ScrollBar.thumbHighlight",      thumbHi);
        UIManager.put("ScrollBar.thumbShadow",         thumbSh);
        UIManager.put("ScrollBar.track",               tblBg);
        UIManager.put("ScrollBar.trackHighlight",      new Color(
            Math.min(tblBg.getRed()+7,255), Math.min(tblBg.getGreen()+7,255), Math.min(tblBg.getBlue()+12,255)));
        UIManager.put("TextField.background",          cardBg);
        UIManager.put("TextField.foreground",          text);
        UIManager.put("TextField.caretForeground",     accent);
        UIManager.put("TextField.selectionBackground", sel);
        UIManager.put("TextField.selectionForeground", text);
        UIManager.put("TextField.inactiveForeground",  muted);
        UIManager.put("TextArea.background",           cardBg);
        UIManager.put("TextArea.foreground",           text);
        UIManager.put("TextArea.selectionBackground",  sel);
        UIManager.put("TextArea.selectionForeground",  text);
        UIManager.put("Panel.background",              cardBg);
        UIManager.put("Label.foreground",              text);
        UIManager.put("Separator.background",          border);
        UIManager.put("Separator.foreground",          border);
        UIManager.put("ToolTip.background",            cardBg);
        UIManager.put("ToolTip.foreground",            text);
        UIManager.put("ToolTip.border",                BorderFactory.createLineBorder(border));
        UIManager.put("OptionPane.background",         cardBg);
        UIManager.put("OptionPane.messageForeground",  text);
        UIManager.put("Button.background",             bg);
        UIManager.put("Button.foreground",             text);
        UIManager.put("Button.select",                 sel);
        UIManager.put("FileChooser.background",        cardBg);
        UIManager.put("Tree.background",               tblBg);
        UIManager.put("Tree.foreground",               text);
        UIManager.put("Tree.selectionBackground",      sel);
        UIManager.put("Tree.selectionForeground",      text);
        UIManager.put("Tree.textBackground",           tblBg);
        UIManager.put("Tree.textForeground",           text);
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    /**
     * Builds the complete window hierarchy and makes the window visible.
     * Called once on the EDT at startup.
     */
    private void buildUI() {
        loadThemePreference();
        // Load persisted data before building the UI so sidebars are populated immediately.
        watchlistManager = new WatchlistManager();
        watchlistManager.load();
        portfolioManager = new PortfolioManager();
        portfolioManager.load();
        portfolioHistoryManager = new PortfolioHistoryManager();
        portfolioHistoryManager.load();
        loadNotes();

        mainWindow = new JFrame("Stock Analyzer \u2014 NASDAQ & NYSE");
        mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainWindow.setMinimumSize(new Dimension(760, 600));

        // Root panel: NORTH = top bar, CENTER = scrollable content, EAST = watchlist
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(theme.background());

        // --- Alert banner (slides in at the very top when a price alert fires) ---
        alertBannerPanel = new JPanel(new BorderLayout());
        alertBannerPanel.setBackground(theme.gain().darker());
        alertBannerPanel.setVisible(false);
        alertBannerPanel.setPreferredSize(new Dimension(0, 0));
        alertBannerLabel = new JLabel("", SwingConstants.CENTER);
        alertBannerLabel.setFont(BODY_FONT);
        alertBannerLabel.setForeground(Color.WHITE);
        alertBannerPanel.add(alertBannerLabel, BorderLayout.CENTER);

        // Top bar: alert banner + header + search bar stacked
        topBar = new JPanel(new BorderLayout());
        topBar.setBackground(theme.background());
        topBar.add(alertBannerPanel, BorderLayout.NORTH);

        JPanel topBarContent = new JPanel(new BorderLayout());
        topBarContent.setBackground(theme.background());
        topBarContent.setBorder(new EmptyBorder(24, 28, 0, 8));
        topBarContent.add(buildHeaderPanel(),    BorderLayout.NORTH);
        topBarContent.add(buildSearchBarPanel(), BorderLayout.CENTER);
        topBar.add(topBarContent, BorderLayout.CENTER);
        rootPanel.add(topBar, BorderLayout.NORTH);

        // Results panel sits inside a scroll pane so all content is reachable.
        resultsPanel = buildResultsPanel();
        resultsPanel.setVisible(false);

        statusSpinner = new SpinnerLabel();
        statusSpinner.setForeground(theme.accent());
        statusSpinner.setVisible(false);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(CAPTION_FONT);
        statusLabel.setForeground(theme.mutedText());
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setBorder(new EmptyBorder(6, 28, 10, 8));
        statusLabel.setBackground(theme.background());
        statusLabel.setOpaque(true);

        JScrollPane resultsScrollPane = new JScrollPane(resultsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultsScrollPane.setBorder(null);
        resultsScrollPane.getViewport().setBackground(theme.background());
        resultsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // CardLayout lets the Portfolio view replace the results scroll pane
        centerCardLayout = new CardLayout();
        centerCardPanel  = new JPanel(centerCardLayout);
        centerCardPanel.setBackground(theme.background());
        centerCardPanel.add(wrapInFadePanel(resultsScrollPane), "results");
        centerCardPanel.add(wrapInFadePanel(buildPortfolioViewPanel()), "portfolio");
        centerCardPanel.add(wrapInFadePanel(buildNewsTabPanel()),      "news");
        centerCardPanel.add(wrapInFadePanel(buildScreenerPanel()),     "screener");
        centerCardPanel.add(wrapInFadePanel(buildCommoditiesPanel()),       "commodities");
        centerCardPanel.add(wrapInFadePanel(buildOptionsPanel()),           "options");
        centerCardPanel.add(wrapInFadePanel(buildCryptoPanel()),            "crypto");
        centerCardPanel.add(wrapInFadePanel(buildEarningsCalendarPanel()),  "earnings");
        centerCardPanel.add(wrapInFadePanel(buildHeatmapPanel()),           "heatmap");
        centerCardPanel.add(wrapInFadePanel(buildEconCalendarPanel()),      "econcalendar");
        centerCardPanel.add(wrapInFadePanel(buildDividendCalendarPanel()),  "dividends");
        centerCardPanel.add(wrapInFadePanel(buildComparisonPanel()),          "comparison");

        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.setBackground(theme.background());
        centerContainer.setBorder(new EmptyBorder(0, 28, 0, 8));
        centerContainer.add(centerCardPanel, BorderLayout.CENTER);
        JPanel statusBar = new JPanel(new BorderLayout(6, 0));
        statusBar.setBackground(theme.background());
        statusBar.setOpaque(true);
        statusBar.add(statusSpinner, BorderLayout.WEST);
        statusBar.add(statusLabel,   BorderLayout.CENTER);
        centerContainer.add(statusBar, BorderLayout.SOUTH);
        rootPanel.add(centerContainer,       BorderLayout.CENTER);

        rootPanel.add(buildWatchlistSidebar(), BorderLayout.EAST);

        mainWindow.setContentPane(rootPanel);
        mainWindow.setExtendedState(JFrame.MAXIMIZED_BOTH);
        mainWindow.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                saveCurrentNotes();
                FETCH_POOL.shutdownNow();
            }
        });
        registerKeyboardShortcuts(rootPanel);
        attachAutocomplete(tickerInputField);
        mainWindow.setVisible(true);
        applyActiveTabStyle();

        // Start watchlist price-refresh timer (fires every 60 s).
        watchlistRefreshTimer = new javax.swing.Timer(60_000,
                e -> refreshWatchlistPricesInBackground());
        watchlistRefreshTimer.start();

        if (!watchlistManager.getEntries().isEmpty()) {
            refreshWatchlistPricesInBackground();
        }
    }

    // ---- Header panel --------------------------------------------------------

    /**
     * Builds the title/subtitle header shown at the very top of the window.
     */
    private JPanel buildHeaderPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));

        JLabel titleLabel    = new JLabel("Stock Analyzer");
        titleLabel.setFont(HEADING_FONT);
        titleLabel.setForeground(theme.accent());

        JLabel subtitleLabel = new JLabel("Real-time data \u00B7 NASDAQ & NYSE");
        subtitleLabel.setFont(CAPTION_FONT);
        subtitleLabel.setForeground(theme.mutedText());

        JPanel textStack = new JPanel(new GridLayout(2, 1, 0, 2));
        textStack.setBackground(theme.background());
        textStack.add(titleLabel);
        textStack.add(subtitleLabel);
        panel.add(textStack, BorderLayout.WEST);

        // --- Nav buttons with active tab highlighting ---
        addNavButton("results", "\u2190 Stocks", () -> {});
        addNavButton("portfolio", "Portfolio", this::refreshPortfolioPricesInBackground);
        addNavButton("news", "News", this::refreshTopNewsOfDay);
        addNavButton("screener", "Screener", () -> { if (!screenerHasLoaded) runScreenerInBackground(); });
        addNavButton("commodities", "Commodities", this::refreshCommoditiesInBackground);
        addNavButton("crypto", "Crypto", this::refreshCryptosInBackground);
        addNavButton("options", "Options", () -> {});
        addNavButton("earnings", "Earnings", this::refreshEarningsInBackground);
        addNavButton("heatmap", "Heatmap", this::refreshHeatmapInBackground);
        addNavButton("econcalendar", "Econ Calendar", () -> {});
        addNavButton("dividends", "Dividends", this::refreshDividendsInBackground);
        addNavButton("comparison", "Compare", () -> {});

        JButton themeToggleBtn = makeActionButton(isDarkTheme ? "Light Mode" : "Dark Mode");
        themeToggleBtn.setToolTipText("Toggle light/dark theme");
        themeToggleBtn.addActionListener(e -> {
            ThemeColors prevTheme = theme;
            isDarkTheme = !isDarkTheme;
            theme = isDarkTheme ? DARK_THEME : LIGHT_THEME;
            themeToggleBtn.setText(isDarkTheme ? "Light Mode" : "Dark Mode");
            updateUIManagerForTheme(theme);
            applyThemeToAllPanels(mainWindow.getContentPane(), prevTheme);
            // Re-apply active tab style after theme change
            applyActiveTabStyle();
            // Re-apply selected interval button styles (they use theme-aware colors)
            if (selectedIntervalBtn != null) applySelectedIntervalStyle(selectedIntervalBtn);
            if (selectedCommodityIntervalBtn != null) applySelectedIntervalStyle(selectedCommodityIntervalBtn);
            if (selectedCryptoIntervalBtn != null) applySelectedIntervalStyle(selectedCryptoIntervalBtn);
            SwingUtilities.updateComponentTreeUI(mainWindow);
            mainWindow.repaint();
            saveThemePreference();
        });

        navButtonsPanel = new JPanel(new WrapLayout(FlowLayout.RIGHT, 6, 4));
        navButtonsPanel.setBackground(theme.background());
        for (JButton btn : navButtonMap.values()) navButtonsPanel.add(btn);
        navButtonsPanel.add(themeToggleBtn);
        panel.add(navButtonsPanel, BorderLayout.EAST);

        return panel;
    }

    private static final Map<String, String> NAV_TOOLTIPS = Map.ofEntries(
        Map.entry("results", "Back to stock analysis (Ctrl+1 / Escape)"),
        Map.entry("portfolio", "View portfolio tracker (Ctrl+2)"),
        Map.entry("news", "Browse market news (Ctrl+3)"),
        Map.entry("screener", "Stock screener / filters (Ctrl+4)"),
        Map.entry("commodities", "Commodities dashboard (Ctrl+5)"),
        Map.entry("crypto", "Cryptocurrency tracker (Ctrl+6)"),
        Map.entry("options", "Options chain analysis (Ctrl+7)"),
        Map.entry("earnings", "Earnings calendar (Ctrl+8)"),
        Map.entry("heatmap", "Sector heatmap (Ctrl+9)"),
        Map.entry("econcalendar", "Economic calendar (Ctrl+0)"),
        Map.entry("dividends", "Dividend calendar"),
        Map.entry("comparison", "Compare multiple stocks side-by-side")
    );

    private void addNavButton(String cardKey, String label, Runnable onSwitch) {
        JButton btn = makeActionButton(label);
        btn.setToolTipText(NAV_TOOLTIPS.getOrDefault(cardKey, label));
        btn.addActionListener(e -> { setActiveTab(cardKey); onSwitch.run(); });
        navButtonMap.put(cardKey, btn);
    }

    private JPanel wrapInFadePanel(java.awt.Component content) {
        JPanel fp = new JPanel(new BorderLayout());
        fp.setOpaque(false);
        fp.add(content, BorderLayout.CENTER);
        return fp;
    }

    private void setActiveTab(String cardKey) {
        activeCardKey = cardKey;
        centerCardLayout.show(centerCardPanel, cardKey);
        applyActiveTabStyle();
    }

    private void applyActiveTabStyle() {
        for (var entry : navButtonMap.entrySet()) {
            JButton btn = entry.getValue();
            if (entry.getKey().equals(activeCardKey)) {
                btn.setBackground(theme.btnHoverBg());
                btn.setForeground(theme.accent());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        new MatteBorder(0, 0, 2, 0, theme.accent()),
                        new EmptyBorder(6, 14, 4, 14)));
            } else {
                btn.setBackground(theme.btnBg());
                btn.setForeground(theme.primaryText());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(6, 14, 6, 14)));
            }
        }
    }

    // ---- Keyboard shortcuts ---------------------------------------------------

    private void registerKeyboardShortcuts(JPanel rootPanel) {
        InputMap im  = rootPanel.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap am = rootPanel.getActionMap();

        // Tab navigation: Ctrl+1..0
        String[] tabOrder = {"results","portfolio","news","screener","commodities",
                             "crypto","options","earnings","heatmap","econcalendar"};
        for (int i = 0; i < tabOrder.length; i++) {
            String key = tabOrder[i];
            int digit = (i + 1) % 10;  // 1,2,3,...,9,0
            im.put(KeyStroke.getKeyStroke(KeyEvent.VK_0 + digit, InputEvent.CTRL_DOWN_MASK), "tab_" + key);
            final String cardKey = key;
            am.put("tab_" + key, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) {
                    JButton btn = navButtonMap.get(cardKey);
                    if (btn != null) btn.doClick(0);
                }
            });
        }

        // Escape → back to results
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "back_results");
        am.put("back_results", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { setActiveTab("results"); }
        });

        // Ctrl+Enter → analyze
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "analyze");
        am.put("analyze", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { triggerStockFetch(); }
        });

        // Ctrl+F → focus ticker input
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK), "focus_ticker");
        am.put("focus_ticker", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                tickerInputField.requestFocusInWindow();
                tickerInputField.selectAll();
            }
        });

        // Ctrl+W → toggle sidebar
        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK), "toggle_sidebar");
        am.put("toggle_sidebar", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { toggleSidebar(); }
        });
    }

    // ---- Ticker autocomplete ------------------------------------------------

    private void attachAutocomplete(JTextField field) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(theme.card());
        popup.setBorder(BorderFactory.createLineBorder(theme.border()));
        JList<String> list = new JList<>(new DefaultListModel<>());
        list.setFont(MONOSPACE_FONT);
        list.setBackground(theme.card());
        list.setForeground(theme.primaryText());
        list.setSelectionBackground(theme.selectionBg());
        JScrollPane sp = new JScrollPane(list);
        sp.setPreferredSize(new Dimension(200, 150));
        sp.setBorder(null);
        popup.add(sp);

        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void update() {
                String prefix = field.getText().trim().toUpperCase();
                if (prefix.isEmpty()) { popup.setVisible(false); return; }
                DefaultListModel<String> model = (DefaultListModel<String>) list.getModel();
                model.clear();
                // Combine watchlist + recent tickers, filter by prefix
                Set<String> candidates = new LinkedHashSet<>();
                if (watchlistManager != null) {
                    for (var e : watchlistManager.getEntries())
                        if (e.ticker.toUpperCase().startsWith(prefix)) candidates.add(e.ticker);
                }
                for (String t : recentTickers)
                    if (t.startsWith(prefix)) candidates.add(t);
                if (candidates.isEmpty()) { popup.setVisible(false); return; }
                candidates.forEach(model::addElement);
                popup.show(field, 0, field.getHeight());
                field.requestFocusInWindow();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { SwingUtilities.invokeLater(this::update); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { SwingUtilities.invokeLater(this::update); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) {}
        });

        field.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                if (!popup.isVisible()) return;
                if (e.getKeyCode() == KeyEvent.VK_DOWN) {
                    int idx = list.getSelectedIndex();
                    if (idx < list.getModel().getSize() - 1) list.setSelectedIndex(idx + 1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_UP) {
                    int idx = list.getSelectedIndex();
                    if (idx > 0) list.setSelectedIndex(idx - 1);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER && list.getSelectedIndex() >= 0) {
                    field.setText(list.getSelectedValue());
                    popup.setVisible(false);
                    e.consume();
                } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    popup.setVisible(false);
                    e.consume();
                }
            }
        });

        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (list.getSelectedValue() != null) {
                    field.setText(list.getSelectedValue());
                    popup.setVisible(false);
                }
            }
        });
    }

    // ---- Search bar ----------------------------------------------------------

    /**
     * Builds the search bar: a text field for the ticker, an "Analyze" button
     * that triggers the fetch, and a "← New" button that clears the results.
     */
    private JPanel buildSearchBarPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));

        tickerInputField = new JTextField();
        tickerInputField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        tickerInputField.setFont(MONOSPACE_FONT);
        tickerInputField.setBackground(theme.btnBg());
        tickerInputField.setForeground(theme.primaryText());
        tickerInputField.setOpaque(true);
        tickerInputField.setCaretColor(theme.accent());
        tickerInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        tickerInputField.putClientProperty("JTextField.placeholderText",
                "Enter ticker, e.g. AAPL");
        tickerInputField.addActionListener(e -> triggerStockFetch()); // Enter key triggers fetch

        analyzeButton = new JButton("Analyze");
        analyzeButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        analyzeButton.setFont(BUTTON_BOLD_FONT);
        analyzeButton.setBackground(theme.btnBg());
        analyzeButton.setForeground(theme.primaryText());
        analyzeButton.setOpaque(true);
        analyzeButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(10, 22, 10, 22)));
        analyzeButton.setFocusPainted(false);
        analyzeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        analyzeButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                analyzeButton.setBackground(theme.btnHoverBg());
                analyzeButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(10, 22, 10, 22)));
            }
            @Override public void mouseExited(MouseEvent e)  {
                analyzeButton.setBackground(theme.btnBg());
                analyzeButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(10, 22, 10, 22)));
            }
        });
        analyzeButton.addActionListener(e -> triggerStockFetch());

        panel.add(tickerInputField, BorderLayout.CENTER);
        panel.add(analyzeButton,    BorderLayout.EAST);
        return panel;
    }

    // ---- Results panel -------------------------------------------------------

    /**
     * Builds the full results area: hero card, four rows of stat cards, and the
     * chart section.  This panel is constructed once at startup and its child
     * labels are updated in-place by {@link #populateResultsFromStockData}.
     */
    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(theme.background());

        // --- Back button ------------------------------------------------------
        newSearchButton = new JButton("\u2190 Back to Search");
        newSearchButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        newSearchButton.setFont(CAPTION_FONT);
        newSearchButton.setBackground(theme.btnBg());
        newSearchButton.setForeground(theme.primaryText());
        newSearchButton.setOpaque(true);
        newSearchButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(7, 14, 7, 14)));
        newSearchButton.setFocusPainted(false);
        newSearchButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        newSearchButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        newSearchButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                newSearchButton.setBackground(theme.btnHoverBg());
                newSearchButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(7, 14, 7, 14)));
            }
            @Override public void mouseExited(MouseEvent e) {
                newSearchButton.setBackground(theme.btnBg());
                newSearchButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(7, 14, 7, 14)));
            }
        });
        newSearchButton.addActionListener(e -> clearResultsAndReset());

        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backRow.setBackground(theme.background());
        backRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        backRow.add(newSearchButton);
        panel.add(backRow);
        panel.add(verticalSpacer(CARD_GAP));

        // --- Hero card: company name, price, change, action buttons -----------
        JPanel heroCard = cardPanel(150);
        heroCard.setLayout(new BorderLayout(0, 4));

        JPanel nameRow = new JPanel(new BorderLayout());
        nameRow.setBackground(theme.card());
        companyNameLabel  = makeLabel("\u2014", STAT_VALUE_FONT, theme.primaryText());
        exchangeNameLabel = makeLabel("\u2014", CAPTION_FONT, theme.mutedText());
        nameRow.add(companyNameLabel,  BorderLayout.CENTER);
        nameRow.add(exchangeNameLabel, BorderLayout.EAST);

        JPanel priceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        priceRow.setBackground(theme.card());
        heroPriceLabel  = makeLabel("\u2014", HERO_PRICE_FONT, theme.primaryText());
        dailyChangeLabel = makeLabel("", BODY_FONT, theme.gain());
        dailyChangeLabel.setBorder(new EmptyBorder(10, 12, 0, 0));
        priceRow.add(heroPriceLabel);
        priceRow.add(dailyChangeLabel);

        // Action buttons are hidden until a stock is loaded
        JPanel actionButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionButtonRow.setBackground(theme.card());
        actionButtonRow.setBorder(new EmptyBorder(6, 0, 0, 0));

        addToWatchlistButton = makeActionButton("+ Watchlist");
        exportCsvButton      = makeActionButton("\u2193 Export CSV");
        addToWatchlistButton.setVisible(false);
        exportCsvButton.setVisible(false);
        exportCsvButton.addActionListener(e -> exportChartDataToCsv());

        actionButtonRow.add(addToWatchlistButton);
        actionButtonRow.add(exportCsvButton);

        heroCard.add(nameRow,        BorderLayout.NORTH);
        heroCard.add(priceRow,       BorderLayout.CENTER);
        heroCard.add(actionButtonRow, BorderLayout.SOUTH);
        panel.add(heroCard);
        panel.add(verticalSpacer(SECTION_GAP));

        // --- Stat cards row 1: market data ------------------------------------
        marketCapLabel      = statValueLabel();
        currentVolumeLabel  = statValueLabel();
        averageVolumeLabel  = statValueLabel();
        panel.add(statsRow(
            statCard("Market Cap",  marketCapLabel),
            statCard("Volume",      currentVolumeLabel),
            statCard("Avg Volume",  averageVolumeLabel)
        ));
        panel.add(verticalSpacer(CARD_GAP));

        // --- Stat cards row 2: valuation --------------------------------------
        peRatioLabel   = statValueLabel();
        forwardPELabel = statValueLabel();
        epsLabel       = statValueLabel();
        panel.add(statsRow(
            statCard("P/E (TTM)", peRatioLabel),
            statCard("Fwd P/E",   forwardPELabel),
            statCard("EPS (TTM)", epsLabel)
        ));
        panel.add(verticalSpacer(CARD_GAP));

        // --- Stat cards row 3: risk & income ----------------------------------
        betaLabel          = statValueLabel();
        dividendYieldLabel = statValueLabel();
        priceToBookLabel   = statValueLabel();
        panel.add(statsRow(
            statCard("Beta",       betaLabel),
            statCard("Div Yield",  dividendYieldLabel),
            statCard("Price/Book", priceToBookLabel)
        ));
        panel.add(verticalSpacer(CARD_GAP));

        // --- Stat cards row 4: technicals ------------------------------------
        weekHighLabel         = statValueLabel(theme.gain());
        weekLowLabel          = statValueLabel(theme.loss());
        fiftyDayAvgLabel      = statValueLabel();
        twoHundredDayAvgLabel = statValueLabel();
        panel.add(statsRow(
            statCard("52W High",  weekHighLabel),
            statCard("52W Low",   weekLowLabel),
            statCard("50D Avg",   fiftyDayAvgLabel),
            statCard("200D Avg",  twoHundredDayAvgLabel)
        ));
        panel.add(verticalSpacer(SECTION_GAP));

        // --- Chart card -------------------------------------------------------
        panel.add(buildChartCard());
        panel.add(verticalSpacer(SECTION_GAP));

        // --- Stock Notes card -------------------------------------------------
        panel.add(buildStockNotesCard());
        panel.add(verticalSpacer(SECTION_GAP));

        // --- News section (populated asynchronously after each stock fetch) ---
        panel.add(buildNewsSection());
        panel.add(verticalSpacer(SECTION_GAP));

        return panel;
    }

    /** Builds the stock notes card — a text area where the user jots notes per ticker. */
    private JPanel buildStockNotesCard() {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel headerRow = new JPanel(new BorderLayout());
        headerRow.setBackground(theme.card());
        JLabel titleLabel = makeLabel("My Notes", STAT_VALUE_FONT, theme.accent());
        stockNotesStatusLabel = makeLabel("", CAPTION_FONT, theme.mutedText());
        stockNotesStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        headerRow.add(titleLabel, BorderLayout.WEST);
        headerRow.add(stockNotesStatusLabel, BorderLayout.EAST);

        stockNotesArea = new JTextArea(5, 0);
        stockNotesArea.setFont(BODY_FONT);
        stockNotesArea.setBackground(theme.background());
        stockNotesArea.setForeground(theme.primaryText());
        stockNotesArea.setCaretColor(theme.accent());
        stockNotesArea.setLineWrap(true);
        stockNotesArea.setWrapStyleWord(true);
        stockNotesArea.setBorder(new EmptyBorder(CARD_GAP, CARD_PADDING_H, CARD_GAP, CARD_PADDING_H));
        stockNotesArea.addFocusListener(new java.awt.event.FocusAdapter() {
            @Override public void focusLost(java.awt.event.FocusEvent e) {
                saveCurrentNotes();
            }
        });
        stockNotesArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void markUnsaved() {
                if (stockNotesStatusLabel != null) stockNotesStatusLabel.setText("unsaved");
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { markUnsaved(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { markUnsaved(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { markUnsaved(); }
        });

        JScrollPane notesScroll = new JScrollPane(stockNotesArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        notesScroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        notesScroll.getViewport().setBackground(theme.background());

        card.add(headerRow,   BorderLayout.NORTH);
        card.add(notesScroll, BorderLayout.CENTER);
        return card;
    }

    /**
     * Builds the chart card: comparison input, interval selector, indicator
     * toggles, and the custom chart panel.
     */
    private JPanel buildChartCard() {
        JPanel chartCard = new JPanel(new BorderLayout(0, 6));
        chartCard.setBackground(theme.card());
        chartCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        chartCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- Comparison ticker row -------------------------------------------
        // The user types a second ticker here to overlay a normalised % chart.
        JPanel comparisonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        comparisonRow.setBackground(theme.card());

        comparisonTickerField = new JTextField(8);
        comparisonTickerField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        comparisonTickerField.setFont(MONOSPACE_FONT);
        comparisonTickerField.setBackground(theme.btnBg());
        comparisonTickerField.setForeground(theme.primaryText());
        comparisonTickerField.setOpaque(true);
        comparisonTickerField.setCaretColor(theme.accent());
        comparisonTickerField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        comparisonTickerField.putClientProperty("JTextField.placeholderText", "Compare: TSLA");
        comparisonTickerField.addActionListener(e -> triggerComparisonChartFetch());

        JButton clearComparisonButton = new JButton("Clear");
        clearComparisonButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        clearComparisonButton.setFont(CAPTION_FONT);
        clearComparisonButton.setBackground(theme.btnBg());
        clearComparisonButton.setForeground(theme.primaryText());
        clearComparisonButton.setOpaque(true);
        clearComparisonButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        clearComparisonButton.setFocusPainted(false);
        clearComparisonButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearComparisonButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                clearComparisonButton.setBackground(theme.btnHoverBg());
                clearComparisonButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(4, 10, 4, 10)));
            }
            @Override public void mouseExited(MouseEvent e) {
                clearComparisonButton.setBackground(theme.btnBg());
                clearComparisonButton.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(4, 10, 4, 10)));
            }
        });
        clearComparisonButton.addActionListener(e -> {
            comparisonTickerField.setText("");
            chartPanel.clearComparison();
            // Re-fetch the main chart to restore normal price scale
            if (currentTicker != null) {
                triggerChartFetch(currentTicker, currentBarInterval, currentTimeRange);
            }
        });

        comparisonRow.add(makeLabel("vs", CAPTION_FONT, theme.mutedText()));
        comparisonRow.add(comparisonTickerField);
        comparisonRow.add(clearComparisonButton);

        // --- Controls row: interval buttons + indicator toggles --------------
        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.setBackground(theme.card());

        // Interval buttons (1D, 5D, 1M, 3M, 6M, 1Y)
        JPanel intervalButtonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        intervalButtonBar.setBackground(theme.card());
        intervalButtons = new JButton[CHART_INTERVALS.length];
        for (int i = 0; i < CHART_INTERVALS.length; i++) {
            final String[] intervalConfig = CHART_INTERVALS[i];
            JButton btn = makeIntervalButton(intervalConfig[0]);
            intervalButtons[i] = btn;
            if (i == DEFAULT_INTERVAL_INDEX) {
                applySelectedIntervalStyle(btn);
                selectedIntervalBtn = btn;
            }
            btn.addActionListener(e -> {
                setActiveIntervalButton(btn);
                currentBarInterval = intervalConfig[1];
                currentTimeRange   = intervalConfig[2];
                currentMaxBars     = intervalConfig.length > 3 && intervalConfig[3] != null
                        ? Integer.parseInt(intervalConfig[3]) : 0;
                if (currentTicker != null) {
                    triggerChartFetch(currentTicker, intervalConfig[1], intervalConfig[2]);
                }
            });
            intervalButtonBar.add(btn);
        }

        // Indicator toggles (MA 20, MA 50, RSI) — right-aligned
        JPanel indicatorToggleBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        indicatorToggleBar.setBackground(theme.card());

        JToggleButton ma20Toggle  = makeIndicatorToggle("MA 20");
        JToggleButton ma50Toggle  = makeIndicatorToggle("MA 50");
        JToggleButton rsiToggle   = makeIndicatorToggle("RSI");
        JToggleButton macdToggle  = makeIndicatorToggle("MACD");
        ma20Toggle.addActionListener(e  -> chartPanel.setShowMovingAverage20(ma20Toggle.isSelected()));
        ma50Toggle.addActionListener(e  -> chartPanel.setShowMovingAverage50(ma50Toggle.isSelected()));
        rsiToggle.addActionListener(e   -> chartPanel.setShowRSI(rsiToggle.isSelected()));
        macdToggle.addActionListener(e  -> chartPanel.setShowMACD(macdToggle.isSelected()));

        indicatorToggleBar.add(ma20Toggle);
        indicatorToggleBar.add(ma50Toggle);
        indicatorToggleBar.add(rsiToggle);
        indicatorToggleBar.add(macdToggle);

        controlsRow.add(intervalButtonBar,  BorderLayout.WEST);
        controlsRow.add(indicatorToggleBar, BorderLayout.EAST);

        chartPanel = new StockChartPanel();
        chartCard.add(comparisonRow, BorderLayout.NORTH);
        chartCard.add(controlsRow,   BorderLayout.CENTER);
        chartCard.add(chartPanel,    BorderLayout.SOUTH);
        return chartCard;
    }

    // ---- Watchlist sidebar ---------------------------------------------------

    /**
     * Builds the right-side watchlist sidebar: a header label and a scrollable
     * list of ticker rows populated by {@link #rebuildWatchlistRows()}.
     */
    private JPanel buildWatchlistSidebar() {
        watchlistSidebar = new JPanel(new BorderLayout(0, 0));
        watchlistSidebar.setBackground(theme.background());
        watchlistSidebar.setPreferredSize(new Dimension(210, 0));

        // Toggle button — lives permanently in NORTH of watchlistSidebar
        sidebarToggleBtn = new JButton(">");
        sidebarToggleBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        sidebarToggleBtn.setFont(CAPTION_FONT);
        sidebarToggleBtn.setBackground(theme.btnBg());
        sidebarToggleBtn.setForeground(theme.primaryText());
        sidebarToggleBtn.setOpaque(true);
        sidebarToggleBtn.setBorder(new EmptyBorder(4, 4, 4, 4));
        sidebarToggleBtn.setFocusPainted(false);
        sidebarToggleBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        sidebarToggleBtn.setToolTipText("Collapse/expand watchlist (Ctrl+W)");
        sidebarToggleBtn.addActionListener(e -> toggleSidebar());

        // Permanent north strip — always visible regardless of collapse state
        JPanel collapseRow = new JPanel(new BorderLayout());
        collapseRow.setBackground(theme.background());
        collapseRow.add(sidebarToggleBtn, BorderLayout.EAST);
        watchlistSidebar.add(collapseRow, BorderLayout.NORTH);

        // Sidebar content
        sidebarContent = new JPanel(new BorderLayout(0, CARD_GAP));
        sidebarContent.setBackground(theme.background());
        sidebarContent.setBorder(new EmptyBorder(0, CARD_PADDING_H, 0, CARD_GAP));

        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setBackground(theme.background());
        sidebarHeader.setBorder(new EmptyBorder(0, 0, CARD_GAP, 0));
        sidebarHeader.add(makeLabel("Watchlist", STAT_VALUE_FONT, theme.accent()), BorderLayout.WEST);
        sidebarContent.add(sidebarHeader, BorderLayout.NORTH);

        watchlistItemsContainer = new JPanel();
        watchlistItemsContainer.setLayout(new BoxLayout(watchlistItemsContainer, BoxLayout.Y_AXIS));
        watchlistItemsContainer.setBackground(theme.background());

        JScrollPane scrollPane = new JScrollPane(watchlistItemsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBackground(theme.background());
        scrollPane.getViewport().setBackground(theme.background());
        scrollPane.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        sidebarContent.add(scrollPane, BorderLayout.CENTER);

        watchlistSidebar.add(sidebarContent, BorderLayout.CENTER);
        rebuildWatchlistRows();
        return watchlistSidebar;
    }

    private void toggleSidebar() {
        sidebarCollapsed = !sidebarCollapsed;
        if (sidebarCollapsed) {
            sidebarContent.setVisible(false);
            watchlistSidebar.setPreferredSize(new Dimension(32, 0));
            sidebarToggleBtn.setText("<");
            sidebarToggleBtn.setToolTipText("Expand watchlist");
        } else {
            sidebarContent.setVisible(true);
            watchlistSidebar.setPreferredSize(new Dimension(210, 0));
            sidebarToggleBtn.setText(">");
            sidebarToggleBtn.setToolTipText("Collapse watchlist");
        }
        watchlistSidebar.revalidate();
        watchlistSidebar.repaint();
        mainWindow.revalidate();
    }

    /**
     * Clears and recreates all rows in the watchlist sidebar from the current
     * {@link WatchlistManager} state.  Must be called on the EDT.
     */
    private void rebuildWatchlistRows() {
        if (watchlistItemsContainer == null) return;
        watchlistItemsContainer.removeAll();

        List<WatchlistManager.WatchlistEntry> entries = watchlistManager.getEntries();
        for (WatchlistManager.WatchlistEntry entry : entries) {
            watchlistItemsContainer.add(buildWatchlistRow(entry));
            watchlistItemsContainer.add(Box.createVerticalStrut(4));
        }

        if (entries.isEmpty()) {
            JLabel emptyHint = makeLabel("No tickers saved", CAPTION_FONT, theme.mutedText());
            emptyHint.setBorder(new EmptyBorder(8, 8, 8, 8));
            watchlistItemsContainer.add(emptyHint);
        }

        watchlistItemsContainer.revalidate();
        watchlistItemsContainer.repaint();
    }

    /**
     * Builds a single row in the watchlist sidebar for {@code entry}.
     * The row shows: ticker symbol | price + change % | remove button.
     * Clicking anywhere on the row (except the remove button) loads that ticker.
     */
    private JPanel buildWatchlistRow(WatchlistManager.WatchlistEntry entry) {
        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setBackground(theme.card());
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_GAP, CARD_PADDING_H, CARD_GAP, CARD_GAP)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel tickerLabel = makeLabel(entry.ticker, STAT_VALUE_FONT, theme.accent());

        // Right side: price on top row, change % on bottom row
        JPanel priceChangeStack = new JPanel(new GridLayout(2, 1, 0, 1));
        priceChangeStack.setBackground(theme.card());

        String priceText = entry.lastKnownPrice == 0
                ? "\u2014"
                : String.format("%.2f", entry.lastKnownPrice);
        JLabel priceLabel = makeLabel(priceText, CAPTION_FONT, theme.primaryText());
        priceLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        String changeText;
        Color  changeColor;
        if (Double.isNaN(entry.lastKnownChangePercent) || entry.lastKnownPrice == 0) {
            changeText  = "";
            changeColor = theme.mutedText();
        } else {
            String sign = entry.lastKnownChangePercent >= 0 ? "+" : "";
            changeText  = String.format("%s%.2f%%", sign, entry.lastKnownChangePercent);
            changeColor = entry.lastKnownChangePercent >= 0 ? theme.gain() : theme.loss();
        }
        JLabel changePercentLabel = makeLabel(changeText, CAPTION_FONT, changeColor);
        changePercentLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        priceChangeStack.add(priceLabel);
        priceChangeStack.add(changePercentLabel);

        // Bell button — set / clear a price alert for this ticker
        boolean hasAlert = !Double.isNaN(entry.alertPrice);
        JButton bellButton = new JButton(hasAlert ? "!" : "o");
        bellButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        bellButton.setFont(CAPTION_FONT);
        bellButton.setForeground(hasAlert ? theme.gain() : theme.mutedText());
        bellButton.setBackground(theme.card());
        bellButton.setOpaque(true);
        bellButton.setBorder(new EmptyBorder(2, 4, 2, 2));
        bellButton.setFocusPainted(false);
        bellButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bellButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { bellButton.setForeground(theme.accent()); }
            @Override public void mouseExited(MouseEvent e)  { bellButton.setForeground(!Double.isNaN(entry.alertPrice) ? theme.gain() : theme.mutedText()); }
        });
        bellButton.setToolTipText(hasAlert
                ? "Alert set at " + String.format("%.2f", entry.alertPrice) + " \u2014 click to clear"
                : "Set price alert");
        bellButton.addActionListener(e -> {
            if (!Double.isNaN(entry.alertPrice)) {
                // Clear existing alert
                watchlistManager.clearAlert(entry.ticker);
                rebuildWatchlistRows();
            } else {
                // Set new alert
                String input = JOptionPane.showInputDialog(mainWindow,
                        "Set alert price for " + entry.ticker + ":",
                        "Price Alert", JOptionPane.PLAIN_MESSAGE);
                if (input == null || input.isBlank()) return;
                try {
                    double alertPrice = Double.parseDouble(input.trim());
                    watchlistManager.setAlert(entry.ticker, alertPrice);
                    rebuildWatchlistRows();
                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(mainWindow,
                            "Please enter a valid price.", "Invalid Input",
                            JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        // Remove button ("×") — removes this ticker from the watchlist
        JButton removeButton = new JButton("\u00D7");
        removeButton.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        removeButton.setFont(CAPTION_FONT);
        removeButton.setForeground(theme.mutedText());
        removeButton.setBackground(theme.card());
        removeButton.setOpaque(true);
        removeButton.setBorder(new EmptyBorder(2, 4, 2, 2));
        removeButton.setFocusPainted(false);
        removeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { removeButton.setForeground(theme.loss()); }
            @Override public void mouseExited(MouseEvent e)  { removeButton.setForeground(theme.mutedText()); }
        });
        removeButton.addActionListener(e -> {
            watchlistManager.remove(entry.ticker);
            rebuildWatchlistRows();
        });

        JPanel buttonStack = new JPanel(new GridLayout(1, 2, 2, 0));
        buttonStack.setBackground(theme.card());
        buttonStack.add(bellButton);
        buttonStack.add(removeButton);

        row.add(tickerLabel,      BorderLayout.WEST);
        row.add(priceChangeStack, BorderLayout.CENTER);
        row.add(buttonStack,      BorderLayout.EAST);

        // Hover highlight and click-to-load behaviour on the row panel itself.
        // Mouse events on child components (the remove button) do NOT bubble up
        // to this listener in Swing, so the remove button works independently.
        row.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                tickerInputField.setText(entry.ticker);
                triggerStockFetch();
            }
            @Override public void mouseEntered(MouseEvent e) {
                row.setBackground(theme.rowHoverBg());
                priceChangeStack.setBackground(theme.rowHoverBg());
            }
            @Override public void mouseExited(MouseEvent e) {
                row.setBackground(theme.card());
                priceChangeStack.setBackground(theme.card());
            }
        });

        return row;
    }

    /**
     * Fetches fresh prices for every entry in the watchlist on a background
     * thread, publishing updates to the sidebar incrementally as each price
     * arrives.  Called at startup and every 60 seconds by {@link #watchlistRefreshTimer}.
     */
    private void refreshWatchlistPricesInBackground() {
        List<WatchlistManager.WatchlistEntry> entriesToRefresh = watchlistManager.getEntries();
        if (entriesToRefresh.isEmpty()) return;

        SwingWorker<Void, Integer> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (WatchlistManager.WatchlistEntry entry : entriesToRefresh) {
                    futures.add(FETCH_POOL.submit(() -> {
                        try {
                            StockData freshData = YahooFinanceFetcher.fetch(entry.ticker);
                            watchlistManager.updateEntry(
                                    entry.ticker,
                                    freshData.currentPrice,
                                    freshData.priceChangePercent,
                                    freshData.currency);
                        } catch (Exception ignored) {}
                        publish(0);
                    }));
                }
                for (var f : futures) { try { f.get(); } catch (Exception ignored) {} }
                return null;
            }

            @Override
            protected void process(List<Integer> chunks) {
                // Check alerts on each batch
                for (WatchlistManager.WatchlistEntry entry : watchlistManager.getEntries()) {
                    if (!Double.isNaN(entry.alertPrice)
                            && entry.lastKnownPrice > 0
                            && entry.lastKnownPrice >= entry.alertPrice) {
                        fireAlert(entry);
                        entry.alertPrice = Double.NaN;
                        watchlistManager.save();
                    }
                }
                rebuildWatchlistRows();
            }

            @Override
            protected void done() {
                watchlistManager.save();
                rebuildWatchlistRows();
            }
        };
        worker.execute();
    }

    // =========================================================================
    // Widget factory helpers
    // =========================================================================

    /** Creates a row of stat cards with equal widths, capped at 68 px tall. */
    private JPanel statsRow(JPanel... cards) {
        JPanel row = new JPanel(new GridLayout(1, cards.length, CARD_GAP, 0));
        row.setBackground(theme.background());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        for (JPanel card : cards) row.add(card);
        return row;
    }

    /** Creates a labelled metric card containing a label above a value label. */
    private JPanel statCard(String metricName, JLabel valueLabel) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 2));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        JLabel nameLabel = makeLabel(metricName, CAPTION_FONT, theme.mutedText());
        card.add(nameLabel);
        card.add(valueLabel);

        // Hover highlight — background brightens when the mouse is over the card.
        // The exit check uses the card's screen rectangle to avoid false exits
        // when the cursor moves between the card panel and its child labels.
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { card.setBackground(theme.rowHoverBg()); }
            @Override public void mouseExited(MouseEvent e) {
                // Only reset if the cursor actually left the card's bounding box
                Point p = e.getPoint();
                SwingUtilities.convertPointToScreen(p, e.getComponent());
                SwingUtilities.convertPointFromScreen(p, card);
                if (!card.contains(p)) card.setBackground(theme.card());
            }
        };
        card.addMouseListener(hover);
        nameLabel.addMouseListener(hover);
        valueLabel.addMouseListener(hover);
        return card;
    }

    /** Default stat value label (white text, em-dash placeholder). */
    private JLabel statValueLabel() {
        return makeLabel("\u2014", STAT_VALUE_FONT, theme.primaryText());
    }

    /** Coloured stat value label (used for 52W High in green and 52W Low in red). */
    private JLabel statValueLabel(Color textColor) {
        return makeLabel("\u2014", STAT_VALUE_FONT, textColor);
    }

    /** Transparent spacer with a fixed height, used to add vertical gaps in BoxLayout. */
    private Component verticalSpacer(int heightPx) {
        JPanel spacer = new JPanel();
        spacer.setBackground(theme.background());
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, heightPx));
        spacer.setPreferredSize(new Dimension(0, heightPx));
        return spacer;
    }

    /** Creates a plain interval button (e.g. "1M") in the unselected state. */
    private JButton makeIntervalButton(String label) {
        JButton btn = new JButton(label);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setFont(CAPTION_FONT);
        btn.setBackground(theme.btnBg());
        btn.setForeground(theme.primaryText());
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnFlatBorder(), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setBackground(theme.btnHoverBg());
                btn.setForeground(theme.primaryText());
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setBackground(theme.btnBg());
                btn.setForeground(theme.primaryText());
            }
        });
        return btn;
    }

    /**
     * Creates an indicator toggle button (MA 20 / MA 50 / RSI).
     * The button changes colour when toggled on/off via an ItemListener.
     */
    private JToggleButton makeIndicatorToggle(String label) {
        JToggleButton btn = new JToggleButton(label);
        btn.setUI(new javax.swing.plaf.basic.BasicToggleButtonUI());
        btn.setFont(CAPTION_FONT);
        btn.setBackground(theme.btnBg());
        btn.setForeground(theme.primaryText());
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnFlatBorder(), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addItemListener(e -> {
            if (btn.isSelected()) {
                btn.setBackground(theme.btnBg());
                btn.setForeground(theme.primaryText());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(4, 10, 4, 10)));
            } else {
                btn.setBackground(theme.btnBg());
                btn.setForeground(theme.primaryText());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnFlatBorder(), 1, true),
                        new EmptyBorder(4, 10, 4, 10)));
            }
        });
        return btn;
    }

    /** Creates an outlined action button with clear text and a visible border. */
    private JButton makeActionButton(String label) {
        JButton btn = new JButton(label);
        btn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        btn.setFont(BUTTON_FONT);
        btn.setBackground(theme.btnBg());
        btn.setForeground(theme.primaryText());
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(6, 14, 6, 14)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                // Active nav button: keep accent styling, just brighten slightly
                if (navButtonMap.containsValue(btn) && navButtonMap.get(activeCardKey) == btn) {
                    btn.setBackground(theme.btnHoverBg());
                    return;
                }
                btn.setBackground(theme.btnHoverBg());
                btn.setForeground(theme.primaryText());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(6, 14, 6, 14)));
            }
            @Override public void mouseExited(MouseEvent e) {
                if (navButtonMap.containsValue(btn) && navButtonMap.get(activeCardKey) == btn) {
                    applyActiveTabStyle();
                    return;
                }
                btn.setBackground(theme.btnBg());
                btn.setForeground(theme.primaryText());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(6, 14, 6, 14)));
            }
        });
        return btn;
    }

    /** Highlights an interval button as the currently selected one. */
    private void applySelectedIntervalStyle(JButton btn) {
        btn.setBackground(theme.btnBg());
        btn.setForeground(theme.primaryText());
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(4, 10, 4, 10)));
    }

    /** Switches the highlighted interval button and resets the previous one. */
    private void setActiveIntervalButton(JButton btn) {
        if (selectedIntervalBtn != null) {
            selectedIntervalBtn.setBackground(theme.btnBg());
            selectedIntervalBtn.setForeground(theme.primaryText());
            selectedIntervalBtn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(theme.btnFlatBorder(), 1, true),
                    new EmptyBorder(4, 10, 4, 10)));
        }
        selectedIntervalBtn = btn;
        applySelectedIntervalStyle(btn);
    }

    /**
     * Resets the interval button selection back to the default (1M) and
     * updates {@link #currentBarInterval} / {@link #currentTimeRange} accordingly.
     */
    private void resetIntervalSelectionToDefault() {
        if (intervalButtons == null) return;
        if (selectedIntervalBtn != null) {
            selectedIntervalBtn.setBackground(theme.btnBg());
            selectedIntervalBtn.setForeground(theme.primaryText());
            selectedIntervalBtn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(theme.btnFlatBorder(), 1, true),
                    new EmptyBorder(4, 10, 4, 10)));
        }
        selectedIntervalBtn = intervalButtons[DEFAULT_INTERVAL_INDEX];
        applySelectedIntervalStyle(selectedIntervalBtn);
        currentBarInterval = CHART_INTERVALS[DEFAULT_INTERVAL_INDEX][1];
        currentTimeRange   = CHART_INTERVALS[DEFAULT_INTERVAL_INDEX][2];
        currentMaxBars     = 0; // default interval (1M) has no bar limit
    }

    /**
     * Creates a card-styled panel with a rounded border and fixed maximum height.
     * Used for the hero card and other block-level cards in the results layout.
     */
    private JPanel cardPanel(int maxHeightPx) {
        JPanel panel = new JPanel();
        panel.setBackground(theme.card());
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeightPx));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** Creates a standard card panel with consistent padding. */
    private JPanel makeCard(LayoutManager layout) {
        JPanel card = new JPanel(layout);
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    /** Creates a section header row with title on left and consistent bottom gap. */
    private JPanel makeSectionHeader(String title) {
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel(title, HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        return header;
    }

    /**
     * Adds a standard hover effect to a button: brighten background on enter,
     * revert on exit.  The padding insets are preserved from the button's
     * existing compound border.
     */
    private void addButtonHover(JButton btn, Insets padding) {
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setBackground(theme.btnHoverBg());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(padding.top, padding.left, padding.bottom, padding.right)));
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setBackground(theme.btnBg());
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                        new EmptyBorder(padding.top, padding.left, padding.bottom, padding.right)));
            }
        });
    }

    /** Convenience factory for a styled {@link JLabel}. */
    private JLabel makeLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
    }

    // ---- Table styling: hover + alternating rows ----------------------------

    private void applyTableStyling(JTable table) {
        table.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                table.putClientProperty("hoveredRow", row);
                table.repaint();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                table.putClientProperty("hoveredRow", -1);
                table.repaint();
            }
        });
        table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object value, boolean selected, boolean focused, int row, int col) {
                super.getTableCellRendererComponent(t, value, selected, focused, row, col);
                if (selected) return this;
                Object hovered = t.getClientProperty("hoveredRow");
                int hRow = hovered instanceof Integer ? (Integer) hovered : -1;
                if (row == hRow) {
                    setBackground(new Color(
                        theme.card().getRed() + 15, theme.card().getGreen() + 15,
                        Math.min(255, theme.card().getBlue() + 25)));
                } else {
                    setBackground(row % 2 == 0 ? theme.background() : theme.card());
                }
                setForeground(theme.primaryText());
                return this;
            }
        });
    }

    // ---- Loading spinner label ----------------------------------------------

    private static class SpinnerLabel extends JLabel {
        private int angle = 0;
        private final javax.swing.Timer spinTimer;

        SpinnerLabel() {
            setPreferredSize(new Dimension(18, 18));
            spinTimer = new javax.swing.Timer(50, e -> { angle = (angle + 30) % 360; repaint(); });
        }

        void startSpinning() { setVisible(true); spinTimer.start(); }
        void stopSpinning()  { spinTimer.stop(); setVisible(false); }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cx = getWidth() / 2, cy = getHeight() / 2, r = Math.min(cx, cy) - 2;
            g2.setColor(getForeground() != null ? getForeground() : Color.WHITE);
            g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawArc(cx - r, cy - r, r * 2, r * 2, angle, 270);
            g2.dispose();
        }
    }

    // ---- Tooltip helpers for table headers -----------------------------------

    private void applyHeaderTooltips(JTable table, String[] tooltips) {
        table.getTableHeader().addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int col = table.columnAtPoint(e.getPoint());
                if (col >= 0 && col < tooltips.length && tooltips[col] != null) {
                    table.getTableHeader().setToolTipText(tooltips[col]);
                } else {
                    table.getTableHeader().setToolTipText(null);
                }
            }
        });
    }

    // ---- Right-click context menu helper ------------------------------------

    private void showContextMenu(Component invoker, MouseEvent e, JMenuItem... items) {
        JPopupMenu menu = new JPopupMenu();
        menu.setBackground(theme.card());
        menu.setBorder(BorderFactory.createLineBorder(theme.border()));
        for (JMenuItem item : items) {
            if (item == null) { menu.addSeparator(); continue; }
            item.setBackground(theme.card());
            item.setForeground(theme.primaryText());
            item.setFont(BODY_FONT);
            menu.add(item);
        }
        menu.show(invoker, e.getX(), e.getY());
    }

    // =========================================================================
    // Fetch / async logic
    // =========================================================================

    /**
     * Reads the ticker from the input field and starts a background fetch for
     * stock data.  Disables the input controls while the request is in flight
     * and re-enables them in {@code done()}, whether the request succeeds or fails.
     */
    private void triggerStockFetch() {
        String ticker = tickerInputField.getText().trim().toUpperCase();
        if (ticker.isEmpty()) {
            showStatus("Please enter a ticker symbol.", theme.loss());
            return;
        }

        // Disable controls while the request is in flight
        analyzeButton.setEnabled(false);
        tickerInputField.setEnabled(false);
        showStatusLoading("Fetching data for " + ticker + "\u2026");
        resultsPanel.setVisible(false);
        recentTickers.add(ticker);

        SwingWorker<StockData, Void> worker = new SwingWorker<>() {
            @Override
            protected StockData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetch(ticker);
            }

            @Override
            protected void done() {
                analyzeButton.setEnabled(true);
                tickerInputField.setEnabled(true);
                try {
                    populateResultsFromStockData(get());
                    hideStatusLoading("Last updated: " + new Date());
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    statusSpinner.stopSpinning();
                    showStatus("Error: " + cause.getMessage(), theme.loss());
                }
            }
        };
        worker.execute();
    }

    /**
     * Fetches chart data for {@code ticker} at the given interval/range on a
     * background thread and hands the result to {@link StockChartPanel}.
     * If a comparison ticker is already set, it is re-fetched afterwards so
     * both series always use the same interval.
     */
    private void triggerChartFetch(String ticker, String barInterval, String timeRange) {
        chartPanel.showLoadingMessage();

        SwingWorker<ChartData, Void> worker = new SwingWorker<>() {
            @Override
            protected ChartData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchChart(ticker, barInterval, timeRange);
            }

            @Override
            protected void done() {
                try {
                    ChartData data = get();
                    if (currentMaxBars > 0) data = trimChartData(data, currentMaxBars);
                    chartPanel.setChartData(data);
                    // If a comparison is active, refresh it with the new interval
                    String activatedComparisonTicker = chartPanel.getComparisonTicker();
                    if (activatedComparisonTicker != null) {
                        triggerComparisonChartFetchFor(activatedComparisonTicker);
                    }
                } catch (Exception ex) {
                    chartPanel.showError("Chart unavailable");
                }
            }
        };
        worker.execute();
    }

    /**
     * Reads the comparison ticker field and starts a chart fetch for it.
     * Called when the user presses Enter in the comparison input field.
     */
    private void triggerComparisonChartFetch() {
        String compTicker = comparisonTickerField.getText().trim().toUpperCase();
        if (compTicker.isEmpty()) return;
        triggerComparisonChartFetchFor(compTicker);
    }

    /**
     * Fetches chart data for the comparison ticker using the currently active
     * interval and range, then hands it to the chart panel.
     */
    private void triggerComparisonChartFetchFor(String compTicker) {
        SwingWorker<ChartData, Void> worker = new SwingWorker<>() {
            @Override
            protected ChartData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchChart(compTicker, currentBarInterval, currentTimeRange);
            }

            @Override
            protected void done() {
                try {
                    chartPanel.setComparisonData(get(), compTicker);
                } catch (Exception ex) {
                    showStatus("Comparison error: " + ex.getMessage(), theme.loss());
                }
            }
        };
        worker.execute();
    }

    // =========================================================================
    // Results population
    // =========================================================================

    /**
     * Populates every label in the results panel from {@code stockData}, wires
     * the action buttons, resets the interval to default, and makes the results
     * panel visible.
     */
    private void populateResultsFromStockData(StockData stockData) {
        currentTicker = stockData.symbol;
        mainWindow.setTitle("Stock Analyzer \u2014 " + stockData.symbol);

        companyNameLabel.setText(stockData.companyName);
        exchangeNameLabel.setText(stockData.exchange != null ? "  " + stockData.exchange : "");

        String currency = stockData.currency != null ? stockData.currency : "USD";
        heroPriceLabel.setText(formatPrice(stockData.currentPrice, currency));

        String changeSign = stockData.priceChange >= 0 ? "+" : "";
        dailyChangeLabel.setText(String.format("%s%.2f  (%s%.2f%%)",
                changeSign, stockData.priceChange,
                changeSign, stockData.priceChangePercent));
        dailyChangeLabel.setForeground(stockData.priceChange >= 0 ? theme.gain() : theme.loss());

        marketCapLabel.setText(formatLargeNumber(stockData.marketCap));
        currentVolumeLabel.setText(formatVolume(stockData.tradingVolume));
        averageVolumeLabel.setText(formatVolume(stockData.averageDailyVolume));

        peRatioLabel.setText(formatDecimal(stockData.peRatio,   "%.2f"));
        forwardPELabel.setText(formatDecimal(stockData.forwardPE, "%.2f"));
        epsLabel.setText(formatDecimal(stockData.earningsPerShare, "%.2f"));
        betaLabel.setText(formatDecimal(stockData.beta,          "%.2f"));
        dividendYieldLabel.setText(Double.isNaN(stockData.dividendYield) ? "N/A"
                : String.format("%.2f%%", stockData.dividendYield * 100));
        priceToBookLabel.setText(formatDecimal(stockData.priceToBook, "%.2f"));
        weekHighLabel.setText(formatPrice(stockData.fiftyTwoWeekHigh,          currency));
        weekLowLabel.setText(formatPrice(stockData.fiftyTwoWeekLow,            currency));
        fiftyDayAvgLabel.setText(formatPrice(stockData.fiftyDayMovingAverage,  currency));
        twoHundredDayAvgLabel.setText(formatPrice(stockData.twoHundredDayMovingAverage, currency));

        // Re-wire the "Add to Watchlist" button for the new ticker (remove any
        // listener from the previous stock to avoid double-adds).
        for (ActionListener al : addToWatchlistButton.getActionListeners()) {
            addToWatchlistButton.removeActionListener(al);
        }
        final String ticker = stockData.symbol;
        addToWatchlistButton.addActionListener(e -> {
            watchlistManager.add(ticker);
            rebuildWatchlistRows();
            refreshWatchlistPricesInBackground();
        });
        addToWatchlistButton.setVisible(true);
        exportCsvButton.setVisible(true);
        newSearchButton.setVisible(true);

        // Reset chart to the default interval for the new ticker
        resetIntervalSelectionToDefault();
        triggerChartFetch(stockData.symbol, currentBarInterval, currentTimeRange);

        // Reset news section to loading state, then kick off a parallel fetch
        if (newsSectionContainer != null) {
            newsSectionContainer.removeAll();
            JLabel loading = makeLabel("Loading news\u2026", CAPTION_FONT, theme.mutedText());
            loading.setBorder(new EmptyBorder(6, 0, 0, 0));
            newsSectionContainer.add(loading);
            newsSectionContainer.revalidate();
        }
        final String newsTickerToFetch = stockData.symbol;
        new SwingWorker<List<NewsItem>, Void>() {
            @Override protected List<NewsItem> doInBackground() {
                return YahooFinanceFetcher.fetchNews(newsTickerToFetch);
            }
            @Override protected void done() {
                try { populateNewsSection(get()); }
                catch (Exception ignored) { populateNewsSection(new ArrayList<>()); }
            }
        }.execute();

        // Load notes for this ticker into the notes area
        if (stockNotesArea != null) {
            String savedNote = stockNotes.getProperty(stockData.symbol, "");
            stockNotesArea.setText(savedNote);
            if (stockNotesStatusLabel != null) {
                stockNotesStatusLabel.setText(savedNote.isEmpty() ? "" : "saved");
            }
        }

        // Switch to the results card (in case portfolio was showing)
        if (centerCardLayout != null) setActiveTab("results");

        resultsPanel.setVisible(true);

        mainWindow.revalidate();
        mainWindow.repaint();
    }

    // =========================================================================
    // "New Search" — clear and reset
    // =========================================================================

    /**
     * Hides the results panel and resets all state so the user can search for
     * a different ticker.  Triggered by the "← New" button.
     */
    private void clearResultsAndReset() {
        resultsPanel.setVisible(false);
        tickerInputField.setText("");
        tickerInputField.requestFocus();
        comparisonTickerField.setText("");
        chartPanel.clearComparison();
        currentTicker = null;
        newSearchButton.setVisible(false);
        mainWindow.setTitle("Stock Analyzer \u2014 NASDAQ & NYSE");
        showStatus(" ", theme.mutedText());
        resetIntervalSelectionToDefault();
        mainWindow.revalidate();
        mainWindow.repaint();
    }

    // =========================================================================
    // CSV export
    // =========================================================================

    /**
     * Opens a save-file dialog and writes the current chart's data to a CSV file.
     *
     * <p>The output format is:
     * <pre>
     * # Stock Analyzer Export
     * # Ticker: AAPL
     * # Interval: 1d / 1mo
     * # Export Date: 2026-03-19 14:30:00
     * #
     * timestamp,date,price,volume
     * 1700000000,2023-11-14 09:30:00,173.4500,2500000
     * ...
     * </pre>
     * Lines starting with {@code #} are metadata comments; most CSV tools ignore them.
     */
    private void exportChartDataToCsv() {
        ChartData chartData = chartPanel.getChartData();
        if (chartData == null || currentTicker == null) return;

        JFileChooser saveDialog = new JFileChooser();
        saveDialog.setSelectedFile(new File(currentTicker + "_" + currentTimeRange + ".csv"));
        if (saveDialog.showSaveDialog(mainWindow) != JFileChooser.APPROVE_OPTION) return;

        File outputFile = saveDialog.getSelectedFile();
        SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile))) {
            writer.write("# Stock Analyzer Export"); writer.newLine();
            writer.write("# Ticker: "   + currentTicker); writer.newLine();
            writer.write("# Interval: " + currentBarInterval + " / " + currentTimeRange); writer.newLine();
            writer.write("# Export Date: " + dateFormatter.format(new Date())); writer.newLine();
            writer.write("#"); writer.newLine();
            writer.write("timestamp,date,price,volume"); writer.newLine();

            for (int i = 0; i < chartData.timestamps.length; i++) {
                String humanDate = dateFormatter.format(new Date(chartData.timestamps[i] * 1000L));
                writer.write(chartData.timestamps[i] + "," + humanDate + ","
                        + String.format("%.4f", chartData.prices[i]) + ","
                        + chartData.volumes[i]);
                writer.newLine();
            }
            showStatus("Exported to " + outputFile.getName(), theme.gain());
        } catch (IOException e) {
            showStatus("Export failed: " + e.getMessage(), theme.loss());
        }
    }

    // =========================================================================
    // News feed
    // =========================================================================

    /**
     * Builds the news section card that sits below the chart in the results panel.
     * The inner container ({@link #newsSectionContainer}) starts with a "Loading..."
     * placeholder and is replaced by actual news cards in {@link #populateNewsSection}.
     */
    private JPanel buildNewsSection() {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(makeLabel("Latest News", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);

        newsSectionContainer = new JPanel();
        newsSectionContainer.setLayout(new BoxLayout(newsSectionContainer, BoxLayout.Y_AXIS));
        newsSectionContainer.setBackground(theme.card());

        JLabel placeholder = makeLabel("Loading news\u2026", CAPTION_FONT, theme.mutedText());
        placeholder.setBorder(new EmptyBorder(6, 0, 0, 0));
        newsSectionContainer.add(placeholder);

        card.add(newsSectionContainer, BorderLayout.CENTER);
        return card;
    }

    /** Builds the standalone News tab with keyword search and daily top stories. */
    private JPanel buildNewsTabPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(theme.background());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(theme.background());
        content.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("News", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        newsTabStatusLabel = makeLabel("Top stories update automatically when you open this tab.",
                CAPTION_FONT, theme.mutedText());
        newsTabStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(newsTabStatusLabel, BorderLayout.CENTER);
        content.add(header);

        JPanel searchCard = cardPanel(98);
        searchCard.setLayout(new BorderLayout(10, 8));
        searchCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 98));
        searchCard.add(makeLabel("Search By Keywords", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);

        newsSearchField = new JTextField();
        newsSearchField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        newsSearchField.setFont(BODY_FONT);
        newsSearchField.setBackground(theme.btnBg());
        newsSearchField.setForeground(theme.primaryText());
        newsSearchField.setOpaque(true);
        newsSearchField.setCaretColor(theme.accent());
        newsSearchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_GAP, CARD_PADDING_H, CARD_GAP, CARD_PADDING_H)));
        newsSearchField.putClientProperty("JTextField.placeholderText",
                "Try keywords like interest rates, oil, AI, semiconductors");

        JButton searchButton = makeActionButton("Search News");
        searchButton.addActionListener(e -> triggerKeywordNewsSearch());
        newsSearchField.addActionListener(e -> triggerKeywordNewsSearch());

        JPanel searchRow = new JPanel(new BorderLayout(8, 0));
        searchRow.setBackground(theme.card());
        searchRow.add(newsSearchField, BorderLayout.CENTER);
        searchRow.add(searchButton, BorderLayout.EAST);
        searchCard.add(searchRow, BorderLayout.CENTER);

        content.add(searchCard);
        content.add(verticalSpacer(SECTION_GAP));

        newsTopStoriesContainer = new JPanel();
        newsTopStoriesContainer.setLayout(new BoxLayout(newsTopStoriesContainer, BoxLayout.Y_AXIS));
        newsTopStoriesContainer.setBackground(theme.card());
        JPanel topStoriesCard = buildNewsListCard("Top Relevant News Today", newsTopStoriesContainer);
        content.add(topStoriesCard);
        content.add(verticalSpacer(SECTION_GAP));

        newsSearchResultsContainer = new JPanel();
        newsSearchResultsContainer.setLayout(new BoxLayout(newsSearchResultsContainer, BoxLayout.Y_AXIS));
        newsSearchResultsContainer.setBackground(theme.card());
        JPanel searchResultsCard = buildNewsListCard("Keyword Search Results", newsSearchResultsContainer);
        content.add(searchResultsCard);

        populateNewsContainer(newsTopStoriesContainer, new ArrayList<>(),
                "Loading today's top finance news\u2026");
        populateNewsContainer(newsSearchResultsContainer, new ArrayList<>(),
                "Search for keywords to load relevant articles.");

        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        refreshTopNewsOfDay();
        return panel;
    }

    /** Creates a generic news list card used by the stock view and News tab. */
    private JPanel buildNewsListCard(String title, JPanel container) {
        JPanel card = new JPanel(new BorderLayout(0, 8));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.add(makeLabel(title, STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);
        card.add(container, BorderLayout.CENTER);
        return card;
    }

    /**
     * Builds a single news article card showing the title (clickable), publisher,
     * and relative publish time.
     */
    private JPanel buildNewsCard(NewsItem item) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                new MatteBorder(0, 0, 1, 0, theme.border()),
                new EmptyBorder(CARD_GAP, CARD_PADDING_H, CARD_GAP, CARD_PADDING_H)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        // Title — rendered as a read-only multi-line text area so long headlines wrap
        JTextArea titleArea = new JTextArea(item.title());
        titleArea.setFont(BODY_FONT);
        titleArea.setForeground(theme.primaryText());
        titleArea.setBackground(theme.card());
        titleArea.setEditable(false);
        titleArea.setFocusable(false);
        titleArea.setLineWrap(true);
        titleArea.setWrapStyleWord(true);
        titleArea.setOpaque(false);
        titleArea.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        titleArea.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                try { Desktop.getDesktop().browse(new URI(item.url())); }
                catch (Exception ignored) {}
            }
            @Override public void mouseEntered(MouseEvent e) { titleArea.setForeground(theme.accent()); }
            @Override public void mouseExited(MouseEvent e)  { titleArea.setForeground(theme.primaryText()); }
        });

        // Publisher + relative time (e.g. "Reuters · 3h ago")
        String relTime = formatRelativeTime(item.publishedAt());
        JLabel metaLabel = makeLabel(item.publisher() + " \u00B7 " + relTime, CAPTION_FONT, theme.mutedText());

        card.add(titleArea,  BorderLayout.CENTER);
        card.add(metaLabel,  BorderLayout.SOUTH);
        return card;
    }

    /** Executes a keyword-based news search for the News tab. */
    private void triggerKeywordNewsSearch() {
        if (newsSearchField == null || newsSearchResultsContainer == null) return;
        String query = newsSearchField.getText().trim();
        if (query.isEmpty()) {
            populateNewsContainer(newsSearchResultsContainer, new ArrayList<>(),
                    "Enter one or more keywords to search for relevant articles.");
            if (newsTabStatusLabel != null) {
                newsTabStatusLabel.setText("News search needs at least one keyword.");
                newsTabStatusLabel.setForeground(theme.loss());
            }
            return;
        }

        currentNewsQuery = query;
        populateNewsContainer(newsSearchResultsContainer, new ArrayList<>(),
                "Searching news for \"" + query + "\"\u2026");
        if (newsTabStatusLabel != null) {
            newsTabStatusLabel.setText("Searching for \"" + query + "\"\u2026");
            newsTabStatusLabel.setForeground(theme.mutedText());
        }

        new SwingWorker<List<NewsItem>, Void>() {
            @Override protected List<NewsItem> doInBackground() {
                return YahooFinanceFetcher.fetchNews(query, 12);
            }

            @Override protected void done() {
                if (!query.equals(currentNewsQuery)) return;
                try {
                    List<NewsItem> items = get();
                    populateNewsContainer(newsSearchResultsContainer, items,
                            "No news found for \"" + query + "\".");
                    if (newsTabStatusLabel != null) {
                        newsTabStatusLabel.setText(items.isEmpty()
                                ? "No results for \"" + query + "\"."
                                : "Showing relevant news for \"" + query + "\".");
                        newsTabStatusLabel.setForeground(items.isEmpty() ? theme.mutedText() : theme.gain());
                    }
                } catch (Exception ignored) {
                    populateNewsContainer(newsSearchResultsContainer, new ArrayList<>(),
                            "Could not load search results right now.");
                    if (newsTabStatusLabel != null) {
                        newsTabStatusLabel.setText("News search failed.");
                        newsTabStatusLabel.setForeground(theme.loss());
                    }
                }
            }
        }.execute();
    }

    /** Refreshes the News tab's top relevant stories for the current day. */
    private void refreshTopNewsOfDay() {
        if (newsTopStoriesContainer == null) return;
        populateNewsContainer(newsTopStoriesContainer, new ArrayList<>(),
                "Loading today's top finance news\u2026");

        new SwingWorker<List<NewsItem>, Void>() {
            @Override protected List<NewsItem> doInBackground() {
                return YahooFinanceFetcher.fetchTopNewsOfDay();
            }

            @Override protected void done() {
                try {
                    List<NewsItem> items = get();
                    populateNewsContainer(newsTopStoriesContainer, items,
                            "No top finance stories were available today.");
                } catch (Exception ignored) {
                    populateNewsContainer(newsTopStoriesContainer, new ArrayList<>(),
                            "Could not load today's top stories.");
                }
            }
        }.execute();
    }

    /**
     * Clears the news section container and populates it with fresh news cards.
     * Shows a "No news available" hint when the list is empty.
     * Must be called on the EDT.
     */
    private void populateNewsSection(List<NewsItem> items) {
        populateNewsContainer(newsSectionContainer, items, "No news available.");
    }

    /** Populates an arbitrary news container with cards or an empty-state message. */
    private void populateNewsContainer(JPanel container, List<NewsItem> items, String emptyMessage) {
        if (container == null) return;
        container.removeAll();
        if (items.isEmpty()) {
            JLabel empty = makeLabel(emptyMessage, CAPTION_FONT, theme.mutedText());
            empty.setBorder(new EmptyBorder(6, 0, 0, 0));
            container.add(empty);
        } else {
            for (int i = 0; i < items.size(); i++) {
                container.add(buildNewsCard(items.get(i)));
                if (i < items.size() - 1) {
                    JSeparator sep = new JSeparator();
                    sep.setForeground(theme.border());
                    sep.setBackground(theme.border());
                    container.add(sep);
                }
            }
        }
        container.revalidate();
        container.repaint();
    }

    /** Formats a Unix timestamp (seconds) as a human-readable relative string, e.g. "3h ago". */
    private String formatRelativeTime(long publishedAtSeconds) {
        if (publishedAtSeconds == 0) return "";
        long diff = System.currentTimeMillis() / 1000 - publishedAtSeconds;
        if (diff < 60)     return diff + "s ago";
        if (diff < 3600)   return (diff / 60) + "m ago";
        if (diff < 86400)  return (diff / 3600) + "h ago";
        return (diff / 86400) + "d ago";
    }

    // =========================================================================
    // Screener tab
    // =========================================================================

    /** Immutable filter set read from the Screener tab input fields. */
    private record ScreenerCriteria(double minMarketCap, double maxPe,
            double minVolume, double minChangePercent,
            double maxBeta, double minDividendYieldPercent) {}

    /** Builds the standalone Screener tab. */
    private JPanel buildScreenerPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(theme.background());

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(theme.background());
        content.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Screener", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        screenerStatusLabel = makeLabel(
                "Filter active US stocks by change, market cap, volume, valuation, beta, and yield.",
                CAPTION_FONT, theme.mutedText());
        screenerStatusLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        header.add(screenerStatusLabel, BorderLayout.CENTER);
        content.add(header);

        JPanel filtersCard = cardPanel(154);
        filtersCard.setLayout(new BorderLayout(0, 10));
        filtersCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, 154));
        filtersCard.add(makeLabel("Filters", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);

        JPanel labelsRow = new JPanel(new GridLayout(1, 7, 8, 0));
        labelsRow.setBackground(theme.card());
        labelsRow.add(makeLabel("Source", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min Mkt Cap", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Max P/E", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min Volume", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min Change %", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Max Beta", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min Div Yield %", CAPTION_FONT, theme.mutedText()));

        JPanel inputsRow = new JPanel(new GridLayout(1, 7, 8, 0));
        inputsRow.setBackground(theme.card());
        screenerSourceCombo = new JComboBox<>();
        for (String[] source : SCREENER_SOURCES) screenerSourceCombo.addItem(source[0]);
        styleComboBox(screenerSourceCombo);
        screenerSourceCombo.addActionListener(e -> {
            boolean custom = "custom".equals(getSelectedScreenerSourceId());
            if (customFiltersCard != null) customFiltersCard.setVisible(custom);
        });

        screenerMinMarketCapField = makeFilterField("10B / 500M");
        screenerMaxPeField = makeFilterField("25");
        screenerMinVolumeField = makeFilterField("5M / 500K");
        screenerMinChangeField = makeFilterField("2.5");
        screenerMaxBetaField = makeFilterField("1.5");
        screenerMinDividendField = makeFilterField("1.2");

        inputsRow.add(screenerSourceCombo);
        inputsRow.add(screenerMinMarketCapField);
        inputsRow.add(screenerMaxPeField);
        inputsRow.add(screenerMinVolumeField);
        inputsRow.add(screenerMinChangeField);
        inputsRow.add(screenerMaxBetaField);
        inputsRow.add(screenerMinDividendField);

        JPanel actionsRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actionsRow.setBackground(theme.card());
        JButton runBtn = makeActionButton("Run Screener");
        JButton resetBtn = makeActionButton("Reset");
        JButton openBtn = makeActionButton("Open Selected");
        runBtn.addActionListener(e -> runScreenerInBackground());
        resetBtn.addActionListener(e -> resetScreenerFilters());
        openBtn.addActionListener(e -> openSelectedScreenerResult());
        actionsRow.add(resetBtn);
        actionsRow.add(openBtn);
        actionsRow.add(runBtn);

        JPanel filtersBody = new JPanel(new BorderLayout(0, 8));
        filtersBody.setBackground(theme.card());
        filtersBody.add(labelsRow, BorderLayout.NORTH);
        filtersBody.add(inputsRow, BorderLayout.CENTER);
        filtersBody.add(actionsRow, BorderLayout.SOUTH);
        filtersCard.add(filtersBody, BorderLayout.CENTER);
        content.add(filtersCard);

        customFiltersCard = new JPanel();
        customFiltersCard.setLayout(new BoxLayout(customFiltersCard, BoxLayout.Y_AXIS));
        customFiltersCard.setBackground(theme.card());
        customFiltersCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        customFiltersCard.setVisible(false);
        customFiltersCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        customFiltersCard.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));

        JButton addFilterBtn = makeActionButton("+ Add Filter");
        addFilterBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        addFilterBtn.addActionListener(e -> addCustomFilterRow());
        customFiltersCard.add(addFilterBtn);
        content.add(customFiltersCard);
        content.add(verticalSpacer(SECTION_GAP));

        JPanel resultsCard = new JPanel(new BorderLayout(0, 8));
        resultsCard.setBackground(theme.card());
        resultsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        resultsCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        resultsCard.add(makeLabel("Matches", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);

        screenerTableModel = new ScreenerTableModel();
        screenerTable = new JTable(screenerTableModel);
        screenerTable.setUI(new javax.swing.plaf.basic.BasicTableUI());
        screenerTable.setFont(BODY_FONT);
        screenerTable.setForeground(theme.primaryText());
        screenerTable.setBackground(theme.background());
        screenerTable.setGridColor(theme.border());
        screenerTable.setRowHeight(26);
        screenerTable.setSelectionBackground(theme.selectionBg());
        screenerTable.setSelectionForeground(theme.primaryText());
        applyTableStyling(screenerTable);
        screenerTable.setShowVerticalLines(true);
        screenerTable.setAutoCreateRowSorter(true);
        screenerTable.setFillsViewportHeight(true);
        screenerTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelectedScreenerResult();
                }
            }
            @Override public void mousePressed(MouseEvent e) { handleScreenerContextMenu(e); }
            @Override public void mouseReleased(MouseEvent e) { handleScreenerContextMenu(e); }
        });

        JTableHeader tableHeader = screenerTable.getTableHeader();
        tableHeader.setUI(new javax.swing.plaf.basic.BasicTableHeaderUI());
        tableHeader.setFont(CAPTION_FONT);
        tableHeader.setBackground(theme.card());
        tableHeader.setForeground(theme.accent());
        tableHeader.setBorder(BorderFactory.createLineBorder(theme.border()));
        tableHeader.setReorderingAllowed(false);

        configureScreenerTableRenderers();

        JScrollPane tableScroll = new JScrollPane(screenerTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        tableScroll.getViewport().setBackground(theme.background());
        tableScroll.setBackground(theme.background());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        styleScrollBar(tableScroll.getVerticalScrollBar());
        styleScrollBar(tableScroll.getHorizontalScrollBar());
        resultsCard.add(tableScroll, BorderLayout.CENTER);

        content.add(resultsCard);

        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /** Shared styling for filter text fields. */
    private JTextField makeFilterField(String placeholder) {
        return makeFilterField(placeholder, this::runScreenerInBackground);
    }

    /** Shared styling for filter text fields with a custom Enter handler. */
    private JTextField makeFilterField(String placeholder, Runnable onEnter) {
        JTextField field = new JTextField();
        field.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        field.setFont(BODY_FONT);
        field.setBackground(theme.btnBg());
        field.setForeground(theme.primaryText());
        field.setCaretColor(theme.accent());
        field.setOpaque(true);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.btnBorder(), 1, true),
                new EmptyBorder(6, 8, 6, 8)));
        field.putClientProperty("JTextField.placeholderText", placeholder);
        if (onEnter != null) field.addActionListener(e -> onEnter.run());
        return field;
    }

    /** Forces a scroll bar to render with theme colors by bypassing the system L&F. */
    private void styleScrollBar(JScrollBar bar) {
        bar.setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor          = theme.btnBorder();
                thumbHighlightColor = theme.btnHoverBorder();
                thumbDarkShadowColor = theme.border();
                thumbLightShadowColor = theme.btnFlatBorder();
                trackColor          = theme.background();
                trackHighlightColor = theme.card();
            }
            @Override protected JButton createDecreaseButton(int orientation) {
                return makeScrollArrowButton();
            }
            @Override protected JButton createIncreaseButton(int orientation) {
                return makeScrollArrowButton();
            }
            private JButton makeScrollArrowButton() {
                JButton btn = new JButton();
                btn.setPreferredSize(new Dimension(0, 0));
                btn.setMinimumSize(new Dimension(0, 0));
                btn.setMaximumSize(new Dimension(0, 0));
                return btn;
            }
        });
        bar.setBackground(theme.background());
        bar.setForeground(theme.btnBorder());
    }

    /** Shared styling for combo boxes used in dashboard tabs. */
    private void styleComboBox(JComboBox<?> comboBox) {
        comboBox.setUI(new javax.swing.plaf.basic.BasicComboBoxUI());
        comboBox.setFont(BODY_FONT);
        comboBox.setBackground(theme.btnBg());
        comboBox.setForeground(theme.primaryText());
        comboBox.setOpaque(true);
        comboBox.setBorder(BorderFactory.createLineBorder(theme.btnBorder(), 1, true));
        // Force every sub-component (arrow button, editor) to match the button background
        for (Component c : comboBox.getComponents()) {
            c.setBackground(theme.btnBg());
            c.setForeground(theme.primaryText());
        }
        comboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                setBackground(isSelected ? theme.selectionBg() : theme.btnBg());
                setForeground(theme.primaryText());
                setBorder(new EmptyBorder(4, 8, 4, 8));
                setFont(BODY_FONT);
                list.setBackground(theme.btnBg());
                return this;
            }
        });
    }

    /** Applies custom renderers so numeric screener columns are readable. */
    private void configureScreenerTableRenderers() {
        if (screenerTable == null) return;

        DefaultTableCellRenderer marketCapRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || n.doubleValue() <= 0) setText("N/A");
                else setText(formatLargeNumber(n.doubleValue()));
            }
        };
        marketCapRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer volumeRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || n.longValue() <= 0) setText("N/A");
                else setText(formatVolume(n.longValue()));
            }
        };
        volumeRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer priceRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || n.doubleValue() <= 0) setText("N/A");
                else setText(String.format("$%.2f", n.doubleValue()));
            }
        };
        priceRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer percentRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue())) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    double pct = n.doubleValue();
                    setText(String.format("%+.2f%%", pct));
                    setForeground(pct >= 0 ? theme.gain() : theme.loss());
                }
            }
        };
        percentRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer decimalRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() == 0) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    setText(String.format("%.2f", n.doubleValue()));
                    setForeground(theme.primaryText());
                }
            }
        };
        decimalRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer dividendRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue()) || n.doubleValue() == 0) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    setText(String.format("%.2f%%", n.doubleValue() * 100.0));
                    setForeground(theme.primaryText());
                }
            }
        };
        dividendRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        screenerTable.getColumnModel().getColumn(3).setCellRenderer(priceRenderer);
        screenerTable.getColumnModel().getColumn(4).setCellRenderer(percentRenderer);
        screenerTable.getColumnModel().getColumn(5).setCellRenderer(marketCapRenderer);
        screenerTable.getColumnModel().getColumn(6).setCellRenderer(volumeRenderer);
        screenerTable.getColumnModel().getColumn(7).setCellRenderer(decimalRenderer);
        screenerTable.getColumnModel().getColumn(8).setCellRenderer(decimalRenderer);
        screenerTable.getColumnModel().getColumn(9).setCellRenderer(dividendRenderer);
        screenerTable.getColumnModel().getColumn(0).setPreferredWidth(72);
        screenerTable.getColumnModel().getColumn(1).setPreferredWidth(210);
        screenerTable.getColumnModel().getColumn(2).setPreferredWidth(110);
    }

    /** Launches the screener request and applies user-entered filters. */
    private void runScreenerInBackground() {
        if (screenerTableModel == null || screenerStatusLabel == null) return;

        final ScreenerCriteria criteria;
        try {
            criteria = readScreenerCriteria();
        } catch (IllegalArgumentException ex) {
            screenerStatusLabel.setText(ex.getMessage());
            screenerStatusLabel.setForeground(theme.loss());
            return;
        }

        final String sourceId = getSelectedScreenerSourceId();
        final String sourceLabel = (String) screenerSourceCombo.getSelectedItem();
        final List<CustomFilterRow> capturedCustomFilters = "custom".equals(sourceId) ? readCustomFilters() : new ArrayList<>();
        screenerHasLoaded = true;
        screenerTableModel.setRows(new ArrayList<>());
        screenerStatusLabel.setText("Running " + sourceLabel + " screener\u2026");
        screenerStatusLabel.setForeground(theme.mutedText());

        new SwingWorker<List<ScreenerStock>, Void>() {
            private int candidateCount = 0;

            @Override protected List<ScreenerStock> doInBackground() {
                List<ScreenerStock> candidates = fetchScreenerCandidates(sourceId);
                if ("custom".equals(sourceId) && !capturedCustomFilters.isEmpty()) {
                    candidates = candidates.stream()
                            .filter(s -> matchesCustomFilterCriteria(s, capturedCustomFilters))
                            .collect(java.util.stream.Collectors.toList());
                }
                candidateCount = candidates.size();
                return applyScreenerFilters(candidates, criteria);
            }

            @Override protected void done() {
                try {
                    List<ScreenerStock> rows = get();
                    screenerTableModel.setRows(rows);
                    applyDefaultScreenerSort();
                    screenerStatusLabel.setText(String.format(
                            "Showing %d matches from %d candidates in %s.",
                            rows.size(), candidateCount, sourceLabel));
                    screenerStatusLabel.setForeground(rows.isEmpty() ? theme.mutedText() : theme.gain());
                } catch (Exception ex) {
                    screenerTableModel.setRows(new ArrayList<>());
                    screenerStatusLabel.setText("Screener request failed.");
                    screenerStatusLabel.setForeground(theme.loss());
                }
            }
        }.execute();
    }

    /** Clears all screener filters and reruns the default source. */
    private void resetScreenerFilters() {
        if (screenerSourceCombo != null) screenerSourceCombo.setSelectedIndex(0);
        if (screenerMinMarketCapField != null) screenerMinMarketCapField.setText("");
        if (screenerMaxPeField != null) screenerMaxPeField.setText("");
        if (screenerMinVolumeField != null) screenerMinVolumeField.setText("");
        if (screenerMinChangeField != null) screenerMinChangeField.setText("");
        if (screenerMaxBetaField != null) screenerMaxBetaField.setText("");
        if (screenerMinDividendField != null) screenerMinDividendField.setText("");
        runScreenerInBackground();
    }

    /** Loads the currently selected screener row into the Stocks view. */
    private void openSelectedScreenerResult() {
        if (screenerTable == null || screenerTable.getSelectedRow() < 0) {
            if (screenerStatusLabel != null) {
                screenerStatusLabel.setText("Select a row to open that stock.");
                screenerStatusLabel.setForeground(theme.mutedText());
            }
            return;
        }

        int modelRow = screenerTable.convertRowIndexToModel(screenerTable.getSelectedRow());
        ScreenerStock stock = screenerTableModel.getRow(modelRow);
        if (stock == null) return;
        tickerInputField.setText(stock.symbol());
        setActiveTab("results");
        triggerStockFetch();
    }

    private void handleScreenerContextMenu(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int row = screenerTable.rowAtPoint(e.getPoint());
        if (row < 0) return;
        screenerTable.setRowSelectionInterval(row, row);
        int modelRow = screenerTable.convertRowIndexToModel(row);
        ScreenerStock stock = screenerTableModel.getRow(modelRow);
        if (stock == null) return;
        String ticker = stock.symbol();

        JMenuItem analyzeItem = new JMenuItem("Analyze " + ticker);
        analyzeItem.addActionListener(ev -> { tickerInputField.setText(ticker); setActiveTab("results"); triggerStockFetch(); });

        JMenuItem watchlistItem = new JMenuItem("Add to Watchlist");
        watchlistItem.addActionListener(ev -> { watchlistManager.add(ticker); watchlistManager.save(); rebuildWatchlistRows(); });

        JMenuItem copyItem = new JMenuItem("Copy Ticker");
        copyItem.addActionListener(ev -> {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(ticker);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, null);
        });

        showContextMenu(screenerTable, e, analyzeItem, watchlistItem, null, copyItem);
    }

    /** Reads and validates the current screener filter inputs. */
    private ScreenerCriteria readScreenerCriteria() {
        return new ScreenerCriteria(
                parseOptionalMagnitude(screenerMinMarketCapField, "Min market cap"),
                parseOptionalDouble(screenerMaxPeField, "Max P/E"),
                parseOptionalMagnitude(screenerMinVolumeField, "Min volume"),
                parseOptionalDouble(screenerMinChangeField, "Min change %"),
                parseOptionalDouble(screenerMaxBetaField, "Max beta"),
                parseOptionalDouble(screenerMinDividendField, "Min dividend yield %")
        );
    }

    /** Returns the active screener source id from the combo box. */
    private String getSelectedScreenerSourceId() {
        int idx = screenerSourceCombo != null ? screenerSourceCombo.getSelectedIndex() : 0;
        if (idx < 0 || idx >= SCREENER_SOURCES.length) idx = 0;
        return SCREENER_SOURCES[idx][1];
    }

    /** Fetches candidate rows for the chosen screener source. */
    private List<ScreenerStock> fetchScreenerCandidates(String sourceId) {
        if ("custom".equals(sourceId)) return fetchWatchlistAsScreenerCandidates();
        if ("blend".equalsIgnoreCase(sourceId)) {
            LinkedHashMap<String, ScreenerStock> merged = new LinkedHashMap<>();
            for (String scrId : DEFAULT_SCREENER_BLEND) {
                for (ScreenerStock stock : YahooFinanceFetcher.fetchPredefinedScreener(scrId, 40)) {
                    merged.putIfAbsent(stock.symbol(), stock);
                }
            }
            return new ArrayList<>(merged.values());
        }
        return YahooFinanceFetcher.fetchPredefinedScreener(sourceId, 50);
    }

    private void addCustomFilterRow() {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setBackground(theme.card());
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));

        String[] metricLabels = {"P/E Ratio", "Market Cap (B)", "Volume (M)", "Change %", "RSI"};
        JComboBox<String> metricCombo = new JComboBox<>(metricLabels);
        styleComboBox(metricCombo);

        String[] opLabels = {">", "<", "between"};
        JComboBox<String> opCombo = new JComboBox<>(opLabels);
        styleComboBox(opCombo);

        JTextField val1 = new JTextField(5);
        val1.setFont(BODY_FONT);
        val1.setBackground(theme.btnBg());
        val1.setForeground(theme.primaryText());
        val1.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(),1,true), new EmptyBorder(3,6,3,6)));

        JLabel andLabel = makeLabel("and", CAPTION_FONT, theme.mutedText());
        andLabel.setVisible(false);

        JTextField val2 = new JTextField(5);
        val2.setFont(BODY_FONT);
        val2.setBackground(theme.btnBg());
        val2.setForeground(theme.primaryText());
        val2.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(),1,true), new EmptyBorder(3,6,3,6)));
        val2.setVisible(false);

        opCombo.addActionListener(e -> {
            boolean between = opCombo.getSelectedIndex() == 2;
            andLabel.setVisible(between);
            val2.setVisible(between);
        });

        JButton removeBtn = new JButton("\u00D7");
        removeBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        removeBtn.setFont(CAPTION_FONT);
        removeBtn.setForeground(theme.mutedText());
        removeBtn.setBackground(theme.card());
        removeBtn.setOpaque(true);
        removeBtn.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        removeBtn.setFocusPainted(false);
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { removeBtn.setForeground(theme.loss()); }
            @Override public void mouseExited(MouseEvent e)  { removeBtn.setForeground(theme.mutedText()); }
        });
        removeBtn.addActionListener(e -> {
            customFiltersCard.remove(row);
            customFiltersCard.revalidate();
            customFiltersCard.repaint();
        });

        row.add(metricCombo); row.add(opCombo); row.add(val1); row.add(andLabel); row.add(val2); row.add(removeBtn);
        customFiltersCard.add(row, customFiltersCard.getComponentCount() - 1);
        customFiltersCard.revalidate();
        customFiltersCard.repaint();
    }

    private List<CustomFilterRow> readCustomFilters() {
        List<CustomFilterRow> filters = new ArrayList<>();
        if (customFiltersCard == null) return filters;
        for (Component comp : customFiltersCard.getComponents()) {
            if (!(comp instanceof JPanel row)) continue;
            JComboBox<?> metricCombo = null, opCombo = null;
            List<JTextField> fields = new ArrayList<>();
            for (Component c : ((JPanel)comp).getComponents()) {
                if (c instanceof JComboBox<?> cb) {
                    if (metricCombo == null) metricCombo = cb; else opCombo = cb;
                } else if (c instanceof JTextField tf) { fields.add(tf); }
            }
            if (metricCombo == null || opCombo == null || fields.isEmpty()) continue;
            FilterMetric metric = FilterMetric.values()[metricCombo.getSelectedIndex()];
            FilterOperator op = switch(opCombo.getSelectedIndex()) {
                case 0 -> FilterOperator.GT; case 1 -> FilterOperator.LT; default -> FilterOperator.BETWEEN;
            };
            try {
                double v1 = Double.parseDouble(fields.get(0).getText().trim());
                double v2 = (op == FilterOperator.BETWEEN && fields.size() > 1) ?
                        Double.parseDouble(fields.get(1).getText().trim()) : v1;
                filters.add(new CustomFilterRow(metric, op, v1, v2));
            } catch (NumberFormatException ignored) {}
        }
        return filters;
    }

    private List<ScreenerStock> fetchWatchlistAsScreenerCandidates() {
        List<ScreenerStock> result = new ArrayList<>();
        for (WatchlistManager.WatchlistEntry entry : watchlistManager.getEntries()) {
            try {
                StockData d = YahooFinanceFetcher.fetch(entry.ticker);
                result.add(new ScreenerStock(
                        d.symbol, d.companyName, d.exchange, d.currency,
                        d.currentPrice, d.priceChangePercent,
                        d.marketCap, d.tradingVolume, d.peRatio, d.beta, d.dividendYield));
            } catch (Exception ignored) {}
        }
        return result;
    }

    private boolean matchesCustomFilterCriteria(ScreenerStock stock, List<CustomFilterRow> filters) {
        for (CustomFilterRow f : filters) {
            double val = switch (f.metric()) {
                case PE_RATIO      -> stock.peRatio();
                case MARKET_CAP    -> stock.marketCap() / 1e9;
                case VOLUME        -> stock.volume() / 1e6;
                case CHANGE_PERCENT -> stock.changePercent();
                case RSI           -> Double.NaN;
            };
            if (Double.isNaN(val)) continue;
            boolean pass = switch (f.operator()) {
                case GT      -> val > f.value1();
                case LT      -> val < f.value1();
                case BETWEEN -> val >= f.value1() && val <= f.value2();
            };
            if (!pass) return false;
        }
        return true;
    }

    /** Applies all user-entered screener filters and returns sorted rows. */
    private List<ScreenerStock> applyScreenerFilters(List<ScreenerStock> candidates,
                                                     ScreenerCriteria criteria) {
        List<ScreenerStock> filtered = new ArrayList<>();
        for (ScreenerStock stock : candidates) {
            if (!matchesScreenerCriteria(stock, criteria)) continue;
            filtered.add(stock);
        }
        filtered.sort((a, b) -> {
            double aChange = Double.isNaN(a.changePercent()) ? Double.NEGATIVE_INFINITY : a.changePercent();
            double bChange = Double.isNaN(b.changePercent()) ? Double.NEGATIVE_INFINITY : b.changePercent();
            int cmp = Double.compare(bChange, aChange);
            if (cmp != 0) return cmp;
            return Long.compare(b.volume(), a.volume());
        });
        return filtered;
    }

    /** Returns whether a screener row satisfies the current filter set. */
    private boolean matchesScreenerCriteria(ScreenerStock stock, ScreenerCriteria criteria) {
        if (!Double.isNaN(criteria.minMarketCap())
                && stock.marketCap() < criteria.minMarketCap()) return false;
        if (!Double.isNaN(criteria.maxPe())
                && (Double.isNaN(stock.peRatio()) || stock.peRatio() > criteria.maxPe())) return false;
        if (!Double.isNaN(criteria.minVolume())
                && stock.volume() < criteria.minVolume()) return false;
        if (!Double.isNaN(criteria.minChangePercent())
                && (Double.isNaN(stock.changePercent())
                || stock.changePercent() < criteria.minChangePercent())) return false;
        if (!Double.isNaN(criteria.maxBeta())
                && (Double.isNaN(stock.beta()) || stock.beta() > criteria.maxBeta())) return false;
        if (!Double.isNaN(criteria.minDividendYieldPercent())) {
            double yieldPct = Double.isNaN(stock.dividendYield()) ? Double.NaN : stock.dividendYield() * 100.0;
            if (Double.isNaN(yieldPct) || yieldPct < criteria.minDividendYieldPercent()) return false;
        }
        return true;
    }

    /** Parses an optional plain decimal field; blank values mean "no filter". */
    private double parseOptionalDouble(JTextField field, String label) {
        if (field == null) return Double.NaN;
        String raw = field.getText().trim();
        if (raw.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a valid number.");
        }
    }

    /** Parses values like 10B, 500M, 2.5T, or plain numbers into raw magnitudes. */
    private double parseOptionalMagnitude(JTextField field, String label) {
        if (field == null) return Double.NaN;
        String raw = field.getText().trim().toUpperCase(Locale.US);
        if (raw.isEmpty()) return Double.NaN;

        double multiplier = 1.0;
        if (raw.endsWith("K")) { multiplier = 1_000.0; raw = raw.substring(0, raw.length() - 1); }
        else if (raw.endsWith("M")) { multiplier = 1_000_000.0; raw = raw.substring(0, raw.length() - 1); }
        else if (raw.endsWith("B")) { multiplier = 1_000_000_000.0; raw = raw.substring(0, raw.length() - 1); }
        else if (raw.endsWith("T")) { multiplier = 1_000_000_000_000.0; raw = raw.substring(0, raw.length() - 1); }

        try {
            return Double.parseDouble(raw) * multiplier;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a number like 10B, 500M, or 2500000.");
        }
    }

    /** Applies the default sort order after a fresh screener run. */
    private void applyDefaultScreenerSort() {
        if (screenerTable == null || screenerTable.getRowSorter() == null) return;
        List<RowSorter.SortKey> sortKeys = new ArrayList<>();
        sortKeys.add(new RowSorter.SortKey(4, SortOrder.DESCENDING));
        sortKeys.add(new RowSorter.SortKey(6, SortOrder.DESCENDING));
        screenerTable.getRowSorter().setSortKeys(sortKeys);
    }

    // =========================================================================
    // Price alerts
    // =========================================================================

    /**
     * Fires a price alert for {@code entry}.  Shows an in-app sliding banner
     * when the window is focused, or a Windows system-tray notification otherwise.
     */
    private void fireAlert(WatchlistManager.WatchlistEntry entry) {
        String msg = "[!]  " + entry.ticker + " reached "
                + String.format("%.2f", entry.lastKnownPrice)
                + " \u2014 alert triggered";

        if (mainWindow.isFocused()) {
            showAlertBanner(msg);
        } else {
            showTrayNotification(entry.ticker + " price alert",
                    entry.ticker + " reached " + String.format("%.2f", entry.lastKnownPrice));
        }
    }

    /**
     * Animates the alert banner into view from the top of the window, then
     * auto-dismisses it after 5 seconds.
     */
    private void showAlertBanner(String message) {
        if (alertDismissTimer != null) alertDismissTimer.stop();
        alertBannerLabel.setText(message);
        alertBannerPanel.setVisible(true);

        // Animate height from 0 → 40 px
        final int[] h = {0};
        javax.swing.Timer growTimer = new javax.swing.Timer(16, null);
        growTimer.addActionListener(e -> {
            h[0] = Math.min(40, h[0] + 4);
            alertBannerPanel.setPreferredSize(new Dimension(0, h[0]));
            topBar.revalidate();
            if (h[0] >= 40) {
                growTimer.stop();
                alertDismissTimer = new javax.swing.Timer(5000, ev -> hideAlertBanner());
                alertDismissTimer.setRepeats(false);
                alertDismissTimer.start();
            }
        });
        growTimer.start();
    }

    /** Hides the alert banner with a slide-out animation mirroring the slide-in. */
    private void hideAlertBanner() {
        if (alertDismissTimer != null) alertDismissTimer.stop();
        final int[] h = {40};
        javax.swing.Timer shrinkTimer = new javax.swing.Timer(16, null);
        shrinkTimer.addActionListener(e -> {
            h[0] = Math.max(0, h[0] - 4);
            alertBannerPanel.setPreferredSize(new Dimension(0, h[0]));
            topBar.revalidate();
            if (h[0] <= 0) {
                shrinkTimer.stop();
                alertBannerPanel.setVisible(false);
                alertBannerPanel.setPreferredSize(new Dimension(0, 0));
            }
        });
        shrinkTimer.start();
    }

    /**
     * Shows a Windows system-tray notification.  Creates the tray icon on first
     * use.  Does nothing if {@link SystemTray#isSupported()} is {@code false}.
     */
    private void showTrayNotification(String title, String text) {
        if (!SystemTray.isSupported()) return;
        if (systemTrayIcon == null) {
            // Create a simple 16×16 coloured icon for the tray
            BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setColor(theme.accent());
            g.fillRect(0, 0, 16, 16);
            g.dispose();
            systemTrayIcon = new TrayIcon(img, "Stock Analyzer");
            systemTrayIcon.setImageAutoSize(true);
            try {
                SystemTray.getSystemTray().add(systemTrayIcon);
            } catch (AWTException e) {
                systemTrayIcon = null;
                return;
            }
        }
        systemTrayIcon.displayMessage(title, text, TrayIcon.MessageType.INFO);
    }

    // =========================================================================
    // Portfolio view
    // =========================================================================

    /**
     * Builds the full portfolio view panel: header row, scrollable position list,
     * and footer totals row.
     */
    private JPanel buildPortfolioViewPanel() {
        JPanel view = new JPanel(new BorderLayout(0, 0));
        view.setBackground(theme.background());

        // --- Header ---
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, HEADER_BOTTOM_GAP, 0));

        JLabel titleLbl = makeLabel("Portfolio", HEADING_FONT, theme.primaryText());

        JButton addBtn = makeActionButton("+ Add Position");
        addBtn.addActionListener(e -> showAddPositionDialog());

        portfolioTotalLabel = makeLabel("", BODY_FONT, theme.mutedText());
        portfolioTotalLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(titleLbl,            BorderLayout.WEST);
        header.add(portfolioTotalLabel, BorderLayout.CENTER);
        header.add(addBtn,              BorderLayout.EAST);
        view.add(header, BorderLayout.NORTH);

        // --- Rows container ---
        portfolioRowsContainer = new JPanel();
        portfolioRowsContainer.setLayout(new BoxLayout(portfolioRowsContainer, BoxLayout.Y_AXIS));
        portfolioRowsContainer.setBackground(theme.background());

        JScrollPane scroll = new JScrollPane(portfolioRowsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        // --- Portfolio Performance Chart ---
        JPanel chartSection = new JPanel(new BorderLayout(0, CARD_GAP));
        chartSection.setBackground(theme.background());
        chartSection.setBorder(new EmptyBorder(SECTION_GAP, 0, 0, 0));

        JPanel chartHeader = new JPanel(new BorderLayout());
        chartHeader.setBackground(theme.background());
        chartHeader.add(makeLabel("Portfolio Performance", BODY_FONT, theme.mutedText()), BorderLayout.WEST);

        JPanel rangeButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        rangeButtons.setBackground(theme.background());
        String[][] ranges = {{"1W","7"},{"1M","30"},{"3M","90"},{"6M","180"},{"1Y","365"}};
        for (String[] r : ranges) {
            JButton rb = makeIntervalButton(r[0]);
            int days = Integer.parseInt(r[1]);
            rb.addActionListener(e -> { portfolioChartRangeDays = days; refreshPortfolioChart(); });
            rangeButtons.add(rb);
        }
        chartHeader.add(rangeButtons, BorderLayout.EAST);
        chartSection.add(chartHeader, BorderLayout.NORTH);

        portfolioChartPanel = new PortfolioChartPanel();
        portfolioChartPanel.setPreferredSize(new Dimension(0, 160));
        portfolioChartPanel.setBackground(theme.card());
        portfolioChartPanel.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        chartSection.add(portfolioChartPanel, BorderLayout.CENTER);

        // --- Sector pie chart ---
        sectorPieChart = new SectorPieChartPanel();
        sectorPieChart.setBackground(theme.card());
        sectorPieChart.setPreferredSize(new Dimension(0, 180));
        sectorPieChart.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(8, 8, 8, 8)));

        JPanel chartsRow = new JPanel(new GridLayout(1, 2, 8, 0));
        chartsRow.setBackground(theme.background());
        chartsRow.add(chartSection);
        chartsRow.add(sectorPieChart);

        JPanel south = new JPanel(new BorderLayout(0, 0));
        south.setBackground(theme.background());
        south.add(scroll, BorderLayout.CENTER);
        south.add(chartsRow, BorderLayout.SOUTH);
        view.add(south, BorderLayout.CENTER);

        refreshPortfolioView();
        return view;
    }

    private void refreshPortfolioChart() {
        if (portfolioChartPanel == null || portfolioHistoryManager == null) return;
        List<PortfolioHistoryManager.DailySnapshot> data =
                portfolioHistoryManager.getSnapshotsForRange(portfolioChartRangeDays);
        portfolioChartPanel.setData(data);
    }

    /**
     * Builds a single portfolio position row.
     * Open positions show: Ticker | Shares | Avg Buy | Price | Value | Unrealized | Realized | [Sell][×]
     * Closed positions (sharesOwned == 0) show the realized gain and only the remove button.
     */
    private JPanel buildPortfolioRow(PortfolioManager.PortfolioPosition pos) {
        boolean closed = pos.sharesOwned <= 0;

        Color rowBg = closed ? theme.background() : theme.card();
        JPanel row = new JPanel(new GridLayout(1, 8, 6, 0));
        row.setBackground(rowBg);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

        // Col 1: Ticker (with CLOSED badge for fully-sold positions)
        String tickerText = closed ? pos.ticker + "  [CLOSED]" : pos.ticker;
        row.add(makeLabel(tickerText, STAT_VALUE_FONT, closed ? theme.mutedText() : theme.accent()));

        // Col 2: Shares
        row.add(makeLabel(closed ? "\u2014" : String.format("%.4f shs", pos.sharesOwned),
                BODY_FONT, theme.primaryText()));

        // Col 3: Avg buy price
        row.add(makeLabel(String.format("$%.2f avg", pos.averageBuyPrice), BODY_FONT, theme.mutedText()));

        // Col 4: Current price
        row.add(makeLabel(closed || pos.currentPrice == 0 ? "\u2014"
                : String.format("$%.2f", pos.currentPrice), BODY_FONT, theme.primaryText()));

        // Col 5: Market value
        row.add(makeLabel(closed || pos.currentPrice == 0 ? "\u2014"
                : String.format("$%.2f", pos.marketValue()), BODY_FONT, theme.primaryText()));

        // Col 6: Unrealized P&L ($ + %)
        if (closed || pos.currentPrice == 0) {
            row.add(makeLabel("\u2014", BODY_FONT, theme.mutedText()));
        } else {
            double ug   = pos.unrealizedGain();
            String sign = ug >= 0 ? "+" : "";
            Color  col  = ug >= 0 ? theme.gain() : theme.loss();
            row.add(makeLabel(String.format("%s$%.2f (%s%.1f%%)",
                    sign, ug, sign, pos.unrealizedGainPercent()), BODY_FONT, col));
        }

        // Col 7: Realized P&L (from sells)
        if (pos.realizedGain == 0) {
            row.add(makeLabel("\u2014", BODY_FONT, theme.mutedText()));
        } else {
            String sign = pos.realizedGain >= 0 ? "+" : "";
            Color  col  = pos.realizedGain >= 0 ? theme.gain() : theme.loss();
            row.add(makeLabel(String.format("%s$%.2f", sign, pos.realizedGain), BODY_FONT, col));
        }

        // Col 8: Action buttons — Sell (open positions only) + Remove
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setBackground(rowBg);

        if (!closed) {
            JButton sellBtn = new JButton("Sell");
            sellBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
            sellBtn.setFont(CAPTION_FONT);
            sellBtn.setForeground(new Color(255, 190, 60));
            sellBtn.setBackground(new Color(50, 38, 20));
            sellBtn.setOpaque(true);
            sellBtn.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(120, 80, 20), 1, true),
                    new EmptyBorder(3, 8, 3, 8)));
            sellBtn.setFocusPainted(false);
            sellBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            sellBtn.addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { sellBtn.setBackground(new Color(80, 55, 15)); }
                @Override public void mouseExited(MouseEvent e)  { sellBtn.setBackground(new Color(50, 38, 20)); }
            });
            sellBtn.addActionListener(e -> showSellDialog(pos));
            actions.add(sellBtn);
        }

        JButton histBtn = new JButton("History");
        histBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        histBtn.setFont(CAPTION_FONT);
        histBtn.setForeground(theme.accent());
        histBtn.setBackground(theme.card());
        histBtn.setOpaque(true);
        histBtn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(3, 6, 3, 6)));
        histBtn.setFocusPainted(false);
        histBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        histBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                histBtn.setBackground(theme.btnHoverBg());
                histBtn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.btnHoverBorder(), 1, true),
                        new EmptyBorder(3, 6, 3, 6)));
            }
            @Override public void mouseExited(MouseEvent e) {
                histBtn.setBackground(theme.card());
                histBtn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(theme.border(), 1, true),
                        new EmptyBorder(3, 6, 3, 6)));
            }
        });
        histBtn.addActionListener(e -> showTransactionHistory(pos.ticker));
        actions.add(histBtn);

        JButton removeBtn = new JButton("\u00D7");
        removeBtn.setUI(new javax.swing.plaf.basic.BasicButtonUI());
        removeBtn.setFont(CAPTION_FONT);
        removeBtn.setForeground(theme.mutedText());
        removeBtn.setBackground(rowBg);
        removeBtn.setOpaque(true);
        removeBtn.setBorder(new EmptyBorder(3, 6, 3, 2));
        removeBtn.setFocusPainted(false);
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { removeBtn.setForeground(theme.loss()); }
            @Override public void mouseExited(MouseEvent e)  { removeBtn.setForeground(theme.mutedText()); }
        });
        removeBtn.addActionListener(e -> {
            portfolioManager.remove(pos.ticker);
            refreshPortfolioView();
        });
        actions.add(removeBtn);

        row.add(actions);
        return row;
    }

    /** Clears and rebuilds the portfolio rows container from current data. Must be on EDT. */
    private void refreshPortfolioView() {
        if (portfolioRowsContainer == null) return;
        portfolioRowsContainer.removeAll();

        // Column headers
        JPanel headers = new JPanel(new GridLayout(1, 8, 6, 0));
        headers.setBackground(theme.background());
        headers.setBorder(new EmptyBorder(0, 12, 4, 12));
        headers.setAlignmentX(Component.LEFT_ALIGNMENT);
        headers.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        for (String col : new String[]{"Ticker", "Shares", "Avg Buy", "Price", "Value", "Unrealized", "Realized", ""}) {
            headers.add(makeLabel(col, CAPTION_FONT, theme.mutedText()));
        }
        portfolioRowsContainer.add(headers);
        portfolioRowsContainer.add(Box.createVerticalStrut(2));

        List<PortfolioManager.PortfolioPosition> positions = portfolioManager.getPositions();
        if (positions.isEmpty()) {
            JLabel empty = makeLabel("No positions yet. Click \"+ Add Position\" to get started.",
                    BODY_FONT, theme.mutedText());
            empty.setBorder(new EmptyBorder(20, 12, 8, 12));
            empty.setAlignmentX(Component.LEFT_ALIGNMENT);
            portfolioRowsContainer.add(empty);
        } else {
            double totalValue = 0, totalGain = 0, totalCost = 0;
            for (PortfolioManager.PortfolioPosition pos : positions) {
                portfolioRowsContainer.add(buildPortfolioRow(pos));
                portfolioRowsContainer.add(Box.createVerticalStrut(4));
                totalValue += pos.marketValue();
                totalGain  += pos.unrealizedGain();
                totalCost  += pos.sharesOwned * pos.averageBuyPrice;
            }
            double totalRealized = portfolioManager.getTotalRealizedGain();
            double pct  = totalCost > 0 ? totalGain / totalCost * 100 : 0;
            String sign = totalGain >= 0 ? "+" : "";
            String rSign = totalRealized >= 0 ? "+" : "";
            if (portfolioTotalLabel != null) {
                portfolioTotalLabel.setText(String.format(
                        "Value: $%.2f  |  Unrealized: %s$%.2f (%s%.1f%%)  |  Realized: %s$%.2f",
                        totalValue, sign, totalGain, sign, pct, rSign, totalRealized));
                portfolioTotalLabel.setForeground(totalGain >= 0 ? theme.gain() : theme.loss());
            }
        }
        portfolioRowsContainer.revalidate();
        portfolioRowsContainer.repaint();
    }

    /**
     * Opens a dialog for the user to add a new portfolio position.
     * Validates input and calls {@link PortfolioManager#add} on success.
     */
    private void showAddPositionDialog() {
        JTextField tickerField   = new JTextField(8);
        JTextField sharesField   = new JTextField(8);
        JTextField buyPriceField = new JTextField(8);
        for (JTextField f : new JTextField[]{tickerField, sharesField, buyPriceField}) {
            f.setFont(BODY_FONT);
            f.setBackground(theme.btnBg());
            f.setForeground(theme.primaryText());
            f.setCaretColor(theme.accent());
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(theme.border(), 1, true),
                    new EmptyBorder(4, 8, 4, 8)));
        }

        JPanel form = new JPanel(new GridLayout(3, 2, 8, 6));
        form.setBackground(theme.card());
        JLabel lbl1 = new JLabel("Ticker:");       lbl1.setFont(BODY_FONT); lbl1.setForeground(theme.primaryText());
        JLabel lbl2 = new JLabel("Shares:");       lbl2.setFont(BODY_FONT); lbl2.setForeground(theme.primaryText());
        JLabel lbl3 = new JLabel("Avg Buy Price:"); lbl3.setFont(BODY_FONT); lbl3.setForeground(theme.primaryText());
        form.add(lbl1);  form.add(tickerField);
        form.add(lbl2);  form.add(sharesField);
        form.add(lbl3);  form.add(buyPriceField);

        int result = JOptionPane.showConfirmDialog(mainWindow, form,
                "Add Portfolio Position", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            String ticker = tickerField.getText().trim().toUpperCase();
            double shares = Double.parseDouble(sharesField.getText().trim());
            double price  = Double.parseDouble(buyPriceField.getText().trim());
            if (ticker.isEmpty() || shares <= 0 || price < 0) throw new NumberFormatException();
            portfolioManager.add(ticker, shares, price, "USD");
            refreshPortfolioView();
            refreshPortfolioPricesInBackground();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(mainWindow,
                    "Please enter a valid ticker, share count, and price.",
                    "Invalid Input", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Opens a sell dialog for the given position.  Pre-fills the sell price
     * with the latest fetched price if available.
     */
    private void showSellDialog(PortfolioManager.PortfolioPosition pos) {
        JTextField sharesField = new JTextField(8);
        String preFillPrice = pos.currentPrice > 0 ? String.format("%.2f", pos.currentPrice) : "";
        JTextField priceField  = new JTextField(preFillPrice, 8);
        for (JTextField f : new JTextField[]{sharesField, priceField}) {
            f.setFont(BODY_FONT);
            f.setBackground(theme.btnBg());
            f.setForeground(theme.primaryText());
            f.setCaretColor(theme.accent());
            f.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(theme.border(), 1, true),
                    new EmptyBorder(4, 8, 4, 8)));
        }

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 6));
        form.setBackground(theme.card());
        JLabel lbl1 = new JLabel("Ticker:");        lbl1.setFont(BODY_FONT); lbl1.setForeground(theme.primaryText());
        JLabel lbl2 = new JLabel("Shares owned:");   lbl2.setFont(BODY_FONT); lbl2.setForeground(theme.primaryText());
        JLabel lbl3 = new JLabel("Shares to sell:"); lbl3.setFont(BODY_FONT); lbl3.setForeground(theme.primaryText());
        JLabel lbl4 = new JLabel("Sell price ($):"); lbl4.setFont(BODY_FONT); lbl4.setForeground(theme.primaryText());
        form.add(lbl1);
        form.add(makeLabel(pos.ticker, BODY_FONT, theme.accent()));
        form.add(lbl2);
        form.add(makeLabel(String.format("%.4f", pos.sharesOwned), BODY_FONT, theme.primaryText()));
        form.add(lbl3);
        form.add(sharesField);
        form.add(lbl4);
        form.add(priceField);

        int result = JOptionPane.showConfirmDialog(mainWindow, form,
                "Sell Position \u2014 " + pos.ticker, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return;

        try {
            double shares    = Double.parseDouble(sharesField.getText().trim());
            double sellPrice = Double.parseDouble(priceField.getText().trim());
            if (shares <= 0 || shares > pos.sharesOwned || sellPrice < 0)
                throw new NumberFormatException();
            portfolioManager.sell(pos.ticker, shares, sellPrice);
            refreshPortfolioView();
            refreshPortfolioPricesInBackground();
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(mainWindow,
                    "Please enter a valid share count (\u2264 shares owned) and price.",
                    "Invalid Input", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showTransactionHistory(String ticker) {
        List<PortfolioManager.TransactionRecord> txs = portfolioManager.getTransactionsForTicker(ticker);
        String[] cols = {"Date", "Type", "Shares", "Price", "Total"};
        Object[][] data = new Object[txs.size()][5];
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        for (int i = 0; i < txs.size(); i++) {
            PortfolioManager.TransactionRecord tx = txs.get(i);
            data[i][0] = sdf.format(new Date(tx.timestampMillis));
            data[i][1] = tx.type.name();
            data[i][2] = String.format("%.4f", tx.shares);
            data[i][3] = String.format("$%.2f", tx.pricePerShare);
            data[i][4] = String.format("$%.2f", tx.shares * tx.pricePerShare);
        }
        JTable table = new JTable(data, cols);
        table.setFont(BODY_FONT);
        table.setRowHeight(24);
        table.setBackground(theme.background());
        table.setForeground(theme.primaryText());
        table.setGridColor(theme.border());
        table.getTableHeader().setBackground(theme.card());
        table.getTableHeader().setForeground(theme.accent());
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(500, 250));
        JOptionPane.showMessageDialog(mainWindow, sp,
                "Transaction History \u2014 " + ticker, JOptionPane.PLAIN_MESSAGE);
    }

    /**
     * Fetches the current price for each portfolio position on a background
     * thread, updating {@code currentPrice} on each position, then calls
     * {@link #refreshPortfolioView()} on the EDT.
     */
    private void refreshPortfolioPricesInBackground() {
        List<PortfolioManager.PortfolioPosition> snapshot = portfolioManager.getPositions()
                .stream().filter(p -> p.sharesOwned > 0).collect(java.util.stream.Collectors.toList());
        if (snapshot.isEmpty()) return;
        showStatusLoading("Refreshing portfolio 0/" + snapshot.size() + "...");

        new SwingWorker<Void, Integer>() {
            private final java.util.concurrent.atomic.AtomicInteger progress =
                    new java.util.concurrent.atomic.AtomicInteger(0);
            @Override
            protected Void doInBackground() {
                List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
                for (PortfolioManager.PortfolioPosition pos : snapshot) {
                    futures.add(FETCH_POOL.submit(() -> {
                        try {
                            StockData data = YahooFinanceFetcher.fetch(pos.ticker);
                            pos.currentPrice = data.currentPrice;
                            if (data.currency != null) pos.currency = data.currency;
                        } catch (Exception ignored) {}
                        publish(progress.incrementAndGet());
                    }));
                }
                for (var f : futures) { try { f.get(); } catch (Exception ignored) {} }
                // Fetch sector info for tickers not yet cached
                List<java.util.concurrent.Future<?>> sectorFutures = new ArrayList<>();
                for (PortfolioManager.PortfolioPosition pos : snapshot) {
                    if (!tickerToSector.containsKey(pos.ticker)) {
                        sectorFutures.add(FETCH_POOL.submit(() -> {
                            YahooFinanceFetcher.SectorInfo info = YahooFinanceFetcher.fetchSectorInfo(pos.ticker);
                            synchronized (tickerToSector) {
                                tickerToSector.put(pos.ticker, info.sector());
                            }
                        }));
                    }
                }
                for (var f : sectorFutures) { try { f.get(); } catch (Exception ignored) {} }
                return null;
            }
            @Override
            protected void process(List<Integer> chunks) {
                int done = chunks.get(chunks.size() - 1);
                showStatusLoading("Refreshing portfolio " + done + "/" + snapshot.size() + "...");
                // Propagate prices live
                for (PortfolioManager.PortfolioPosition snap : snapshot) {
                    for (PortfolioManager.PortfolioPosition live : portfolioManager.getPositions()) {
                        if (live.ticker.equals(snap.ticker) && snap.currentPrice > 0) {
                            live.currentPrice = snap.currentPrice;
                            live.currency     = snap.currency;
                        }
                    }
                }
                refreshPortfolioView();
            }
            @Override
            protected void done() {
                for (PortfolioManager.PortfolioPosition snap : snapshot) {
                    for (PortfolioManager.PortfolioPosition live : portfolioManager.getPositions()) {
                        if (live.ticker.equals(snap.ticker) && snap.currentPrice > 0) {
                            live.currentPrice = snap.currentPrice;
                            live.currency     = snap.currency;
                        }
                    }
                }
                refreshPortfolioView();
                double total = portfolioManager.getPositions().stream()
                        .filter(p -> p.sharesOwned > 0 && p.currentPrice > 0)
                        .mapToDouble(p -> p.marketValue()).sum();
                if (total > 0) { portfolioHistoryManager.recordSnapshot(total); refreshPortfolioChart(); }
                // Update sector pie chart
                updateSectorPieChart(snapshot);
                hideStatusLoading("Portfolio updated \u2014 " + snapshot.size() + " positions");
            }
        }.execute();
    }

    // =========================================================================
    // Commodities panel
    // =========================================================================

    /** Builds the full Commodities dashboard panel. */
    private JPanel buildCommoditiesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Commodities", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        commoditiesLastUpdated = makeLabel("", CAPTION_FONT, theme.mutedText());
        commoditiesLastUpdated.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(commoditiesLastUpdated, BorderLayout.CENTER);
        JButton refreshBtn = makeActionButton("Refresh");
        refreshBtn.addActionListener(e -> refreshCommoditiesInBackground());
        header.add(refreshBtn, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // 4×2 grid of commodity cards
        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(theme.background());

        commoditiesGrid = new JPanel(new GridLayout(4, 2, SECTION_GAP, SECTION_GAP));
        commoditiesGrid.setBackground(theme.background());
        commoditiesGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(commoditiesGrid);
        content.add(Box.createVerticalStrut(SECTION_GAP));

        commodityDetailCard = buildCommodityDetailCard();
        commodityDetailCard.setVisible(false);
        content.add(commodityDetailCard);
        rebuildCommodityGrid();

        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        // Start 60-second auto-refresh timer
        commoditiesRefreshTimer = new javax.swing.Timer(60_000,
                e -> refreshCommoditiesInBackground());
        commoditiesRefreshTimer.start();

        return panel;
    }

    /** Builds the inline commodity detail card shown beneath the grid. */
    private JPanel buildCommodityDetailCard() {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(14, 16, 14, 16)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(theme.card());

        JPanel titleStack = new JPanel(new GridLayout(2, 1, 0, 2));
        titleStack.setBackground(theme.card());
        commodityDetailTitleLabel = makeLabel("Commodity Detail", STAT_VALUE_FONT, theme.primaryText());
        commodityDetailMetaLabel = makeLabel("Click a commodity card above to inspect it.",
                CAPTION_FONT, theme.mutedText());
        titleStack.add(commodityDetailTitleLabel);
        titleStack.add(commodityDetailMetaLabel);

        JPanel summaryStack = new JPanel(new GridLayout(3, 1, 0, 2));
        summaryStack.setBackground(theme.card());
        commodityDetailPriceLabel = makeLabel("\u2014", HERO_PRICE_FONT, theme.primaryText());
        commodityDetailPriceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        commodityDetailChangeLabel = makeLabel("", BODY_FONT, theme.mutedText());
        commodityDetailChangeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        commodityDetailRangeLabel = makeLabel("", CAPTION_FONT, theme.mutedText());
        commodityDetailRangeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        summaryStack.add(commodityDetailPriceLabel);
        summaryStack.add(commodityDetailChangeLabel);
        summaryStack.add(commodityDetailRangeLabel);

        header.add(titleStack, BorderLayout.WEST);
        header.add(summaryStack, BorderLayout.EAST);

        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.setBackground(theme.card());

        JPanel intervalBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        intervalBar.setBackground(theme.card());
        commodityIntervalButtons = new JButton[CHART_INTERVALS.length];
        for (int i = 0; i < CHART_INTERVALS.length; i++) {
            final String[] intervalConfig = CHART_INTERVALS[i];
            JButton btn = makeIntervalButton(intervalConfig[0]);
            commodityIntervalButtons[i] = btn;
            if (i == COMMODITY_DEFAULT_INTERVAL_INDEX) {
                applySelectedIntervalStyle(btn);
                selectedCommodityIntervalBtn = btn;
            }
            btn.addActionListener(e -> {
                setActiveCommodityIntervalButton(btn);
                currentCommodityBarInterval = intervalConfig[1];
                currentCommodityTimeRange   = intervalConfig[2];
                currentCommodityMaxBars     = intervalConfig.length > 3 && intervalConfig[3] != null
                        ? Integer.parseInt(intervalConfig[3]) : 0;
                if (selectedCommodityTicker != null) {
                    triggerCommodityDetailFetch(selectedCommodityTicker, selectedCommodityName);
                }
            });
            intervalBar.add(btn);
        }

        JPanel indicatorBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        indicatorBar.setBackground(theme.card());
        commodityMa20Toggle = makeIndicatorToggle("MA 20");
        commodityMa50Toggle = makeIndicatorToggle("MA 50");
        commodityRsiToggle  = makeIndicatorToggle("RSI");
        JToggleButton commodityMacdToggle = makeIndicatorToggle("MACD");
        indicatorBar.add(commodityMa20Toggle);
        indicatorBar.add(commodityMa50Toggle);
        indicatorBar.add(commodityRsiToggle);
        indicatorBar.add(commodityMacdToggle);

        controlsRow.add(intervalBar, BorderLayout.WEST);
        controlsRow.add(indicatorBar, BorderLayout.EAST);

        commodityChartPanel = new StockChartPanel();
        commodityChartPanel.setPreferredSize(new Dimension(0, 340));
        commodityMa20Toggle.addActionListener(
                e -> commodityChartPanel.setShowMovingAverage20(commodityMa20Toggle.isSelected()));
        commodityMa50Toggle.addActionListener(
                e -> commodityChartPanel.setShowMovingAverage50(commodityMa50Toggle.isSelected()));
        commodityRsiToggle.addActionListener(
                e -> commodityChartPanel.setShowRSI(commodityRsiToggle.isSelected()));
        commodityMacdToggle.addActionListener(
                e -> commodityChartPanel.setShowMACD(commodityMacdToggle.isSelected()));

        card.add(header, BorderLayout.NORTH);
        card.add(controlsRow, BorderLayout.CENTER);
        card.add(commodityChartPanel, BorderLayout.SOUTH);
        return card;
    }

    /** Rebuilds the commodity grid in the configured display order. */
    private void rebuildCommodityGrid() {
        if (commoditiesGrid == null) return;
        commoditiesGrid.removeAll();
        for (String[] commodity : COMMODITIES) {
            commoditiesGrid.add(buildCommodityCard(commoditySnapshots.get(commodity[0]), commodity[1]));
        }
        commoditiesGrid.revalidate();
        commoditiesGrid.repaint();
    }

    /** Builds a single commodity card. Pass {@code null} snap for a loading placeholder. */
    private JPanel buildCommodityCard(CommoditySnapshot snap, String displayName) {
        boolean selected = snap != null && snap.ticker().equalsIgnoreCase(selectedCommodityTicker);
        Color baseCardBackground = selected ? theme.selectionBg() : theme.card();
        Color hoverCardBackground = selected ? theme.selectionBg().brighter() : theme.rowHoverBg();
        Color borderColor = selected ? theme.accent() : theme.border();

        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(baseCardBackground);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, selected ? 2 : 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setCursor(snap != null
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());

        JPanel header = new JPanel(new GridLayout(2, 1, 0, 2));
        header.setBackground(baseCardBackground);
        String name = snap != null ? snap.name() : displayName;
        JLabel nameLabel = makeLabel(name, BODY_FONT, theme.primaryText());
        JLabel tickerLabel = makeLabel(snap != null ? snap.ticker() : "Awaiting live data",
                CAPTION_FONT, selected ? theme.accent() : theme.mutedText());
        header.add(nameLabel);
        header.add(tickerLabel);
        card.add(header, BorderLayout.NORTH);

        JPanel centerRow = new JPanel(new BorderLayout(10, 0));
        centerRow.setBackground(baseCardBackground);
        JPanel priceStack = new JPanel(new GridLayout(2, 1, 0, 2));
        priceStack.setBackground(baseCardBackground);
        String priceText = snap != null
                ? formatPrice(snap.price(), snap.currency() != null ? snap.currency() : "USD")
                : "\u2014";
        JLabel priceLabel = makeLabel(priceText, STAT_VALUE_FONT, theme.primaryText());
        JLabel detailHintLabel = makeLabel(
                snap != null ? "Click to open interactive chart" : "Loading\u2026",
                CAPTION_FONT, theme.mutedText());
        priceStack.add(priceLabel);
        priceStack.add(detailHintLabel);
        centerRow.add(priceStack, BorderLayout.CENTER);

        SparklinePanel sparkline = null;
        if (snap != null && snap.sparkPrices() != null && snap.sparkPrices().length >= 2) {
            sparkline = new SparklinePanel();
            sparkline.setData(snap.sparkPrices(),
                    snap.sparkPrices()[snap.sparkPrices().length - 1] >= snap.sparkPrices()[0]
                            ? theme.gain() : theme.loss());
            sparkline.setPreferredSize(new Dimension(112, 58));
            centerRow.add(sparkline, BorderLayout.EAST);
        }
        card.add(centerRow, BorderLayout.CENTER);

        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        southRow.setBackground(baseCardBackground);
        if (snap != null) {
            String sign = snap.changePercent() >= 0 ? "+" : "";
            Color chgColor = snap.changePercent() >= 0 ? theme.gain() : theme.loss();
            southRow.add(makeLabel(String.format("%s%.2f%%", sign, snap.changePercent()),
                    CAPTION_FONT, chgColor));
            southRow.add(makeLabel(String.format("H: %.2f", snap.dayHigh()),
                    CAPTION_FONT, theme.mutedText()));
            southRow.add(makeLabel(String.format("L: %.2f", snap.dayLow()),
                    CAPTION_FONT, theme.mutedText()));
        } else {
            southRow.add(makeLabel("Loading\u2026", CAPTION_FONT, theme.mutedText()));
        }
        card.add(southRow, BorderLayout.SOUTH);

        if (snap != null) {
            MouseAdapter interaction = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { showCommodityDetail(snap); }
                @Override public void mouseEntered(MouseEvent e) {
                    card.setBackground(hoverCardBackground);
                    header.setBackground(hoverCardBackground);
                    centerRow.setBackground(hoverCardBackground);
                    priceStack.setBackground(hoverCardBackground);
                    southRow.setBackground(hoverCardBackground);
                }
                @Override public void mouseExited(MouseEvent e) {
                    card.setBackground(baseCardBackground);
                    header.setBackground(baseCardBackground);
                    centerRow.setBackground(baseCardBackground);
                    priceStack.setBackground(baseCardBackground);
                    southRow.setBackground(baseCardBackground);
                }
            };
            for (JComponent component : new JComponent[]{
                    card, header, centerRow, priceStack, southRow, nameLabel,
                    tickerLabel, priceLabel, detailHintLabel }) {
                component.addMouseListener(interaction);
                component.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            if (sparkline != null) {
                sparkline.addMouseListener(interaction);
                sparkline.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
        }

        return card;
    }

    /** Resets the commodity interval buttons to the 1D default. */
    private void resetCommodityIntervalSelectionToDefault() {
        if (commodityIntervalButtons == null) return;
        if (selectedCommodityIntervalBtn != null) {
            selectedCommodityIntervalBtn.setBackground(theme.btnBg());
            selectedCommodityIntervalBtn.setForeground(theme.primaryText());
        }
        selectedCommodityIntervalBtn = commodityIntervalButtons[COMMODITY_DEFAULT_INTERVAL_INDEX];
        applySelectedIntervalStyle(selectedCommodityIntervalBtn);
        currentCommodityBarInterval = CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][1];
        currentCommodityTimeRange   = CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][2];
        currentCommodityMaxBars     = 0;
    }

    /** Switches the highlighted commodity interval button. */
    private void setActiveCommodityIntervalButton(JButton btn) {
        if (selectedCommodityIntervalBtn != null) {
            selectedCommodityIntervalBtn.setBackground(theme.btnBg());
            selectedCommodityIntervalBtn.setForeground(theme.primaryText());
        }
        selectedCommodityIntervalBtn = btn;
        applySelectedIntervalStyle(btn);
    }

    /** Opens the inline detail card for the selected commodity. */
    private void showCommodityDetail(CommoditySnapshot snap) {
        if (snap == null || commodityDetailCard == null || commodityChartPanel == null) return;

        selectedCommodityTicker = snap.ticker();
        selectedCommodityName   = snap.name();
        resetCommodityIntervalSelectionToDefault();
        if (commodityMa20Toggle != null) commodityMa20Toggle.setSelected(false);
        if (commodityMa50Toggle != null) commodityMa50Toggle.setSelected(false);
        if (commodityRsiToggle  != null) commodityRsiToggle.setSelected(false);
        commodityChartPanel.clearComparison();

        commodityDetailTitleLabel.setText(snap.name() + " (" + snap.ticker() + ")");
        commodityDetailMetaLabel.setText("Loading detailed chart\u2026");
        commodityDetailPriceLabel.setText(
                formatPrice(snap.price(), snap.currency() != null ? snap.currency() : "USD"));
        String changeSign = snap.changePercent() >= 0 ? "+" : "";
        commodityDetailChangeLabel.setText(
                String.format("%s%.2f%% today", changeSign, snap.changePercent()));
        commodityDetailChangeLabel.setForeground(snap.changePercent() >= 0 ? theme.gain() : theme.loss());
        commodityDetailRangeLabel.setText(String.format("Day high %.2f  \u00B7  Day low %.2f",
                snap.dayHigh(), snap.dayLow()));
        commodityDetailCard.setVisible(true);
        rebuildCommodityGrid();
        triggerCommodityDetailFetch(snap.ticker(), snap.name());

        SwingUtilities.invokeLater(() -> commodityDetailCard.scrollRectToVisible(
                new Rectangle(0, 0, commodityDetailCard.getWidth(), commodityDetailCard.getHeight())));
    }

    /** Fetches the selected commodity's chart for the active interval. */
    private void triggerCommodityDetailFetch(String ticker, String displayName) {
        if (commodityChartPanel == null || ticker == null) return;

        final String requestedTicker = ticker;
        final String requestedName = displayName != null ? displayName : ticker;
        final String requestedBarInterval = currentCommodityBarInterval;
        final String requestedTimeRange = currentCommodityTimeRange;
        final int requestedMaxBars = currentCommodityMaxBars;

        commodityChartPanel.showLoadingMessage();
        commodityDetailMetaLabel.setText("Loading " + findCommodityIntervalLabel() + " chart\u2026");

        new SwingWorker<ChartData, Void>() {
            @Override
            protected ChartData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchChart(
                        requestedTicker, requestedBarInterval, requestedTimeRange);
            }

            @Override
            protected void done() {
                if (!requestedTicker.equals(selectedCommodityTicker)
                        || !requestedBarInterval.equals(currentCommodityBarInterval)
                        || !requestedTimeRange.equals(currentCommodityTimeRange)) {
                    return;
                }

                try {
                    ChartData data = get();
                    if (requestedMaxBars > 0) data = trimChartData(data, requestedMaxBars);
                    commodityChartPanel.setChartData(data);
                    populateCommodityDetailSummary(requestedTicker, requestedName, data);
                } catch (Exception ex) {
                    commodityChartPanel.showError("Commodity chart unavailable");
                    commodityDetailMetaLabel.setText("Unable to load " + requestedName + " chart");
                    commodityDetailChangeLabel.setText("");
                    commodityDetailRangeLabel.setText("");
                }
            }
        }.execute();
    }

    /** Updates the commodity detail labels from the active chart view. */
    private void populateCommodityDetailSummary(String ticker, String displayName, ChartData data) {
        if (data == null || data.prices.length == 0) return;

        CommoditySnapshot snap = commoditySnapshots.get(ticker);
        String currency = snap != null && snap.currency() != null ? snap.currency() : "USD";
        double currentPrice = data.prices[data.prices.length - 1];
        double firstPrice = data.prices[0];
        double change = currentPrice - firstPrice;
        double changePercent = firstPrice == 0 ? 0 : (currentPrice - firstPrice) / firstPrice * 100.0;
        double viewHigh = Arrays.stream(data.prices).max().orElse(currentPrice);
        double viewLow  = Arrays.stream(data.prices).min().orElse(currentPrice);
        String changeSign = change >= 0 ? "+" : "";

        commodityDetailTitleLabel.setText(displayName + " (" + ticker + ")");
        commodityDetailMetaLabel.setText("Interactive " + findCommodityIntervalLabel()
                + " chart \u00B7 hover for price and volume");
        commodityDetailPriceLabel.setText(formatPrice(currentPrice, currency));
        commodityDetailChangeLabel.setText(String.format("%s%.2f  (%s%.2f%%)",
                changeSign, change, changeSign, changePercent));
        commodityDetailChangeLabel.setForeground(change >= 0 ? theme.gain() : theme.loss());
        commodityDetailRangeLabel.setText(String.format("View high %.2f  \u00B7  View low %.2f",
                viewHigh, viewLow));
    }

    /** Returns the label of the currently active commodity interval button. */
    private String findCommodityIntervalLabel() {
        return selectedCommodityIntervalBtn != null ? selectedCommodityIntervalBtn.getText() : "1D";
    }

    /** Fetches live data for all 8 commodities in the background and rebuilds cards. */
    private void refreshCommoditiesInBackground() {
        new SwingWorker<Void, CommoditySnapshot>() {
            @Override
            protected Void doInBackground() {
                for (String[] comm : COMMODITIES) {
                    String ticker = comm[0];
                    String name   = comm[1];
                    try {
                        ChartData cd = YahooFinanceFetcher.fetchChart(ticker, "5m", "1d");
                        if (cd == null || cd.prices.length < 2) continue;
                        double[] prices = cd.prices;
                        double price  = prices[prices.length - 1];
                        double first  = prices[0];
                        double changePct = (price - first) / first * 100.0;
                        double dayHigh  = Arrays.stream(prices).max().orElse(price);
                        double dayLow   = Arrays.stream(prices).min().orElse(price);
                        double[] spark  = prices.length > 78
                                ? Arrays.copyOfRange(prices, prices.length - 78, prices.length)
                                : prices;
                        publish(new CommoditySnapshot(ticker, name, price, changePct,
                                dayHigh, dayLow, "USD", spark));
                    } catch (Exception ignored) {}
                }
                return null;
            }

            @Override
            protected void process(List<CommoditySnapshot> snaps) {
                for (CommoditySnapshot snap : snaps) {
                    commoditySnapshots.put(snap.ticker(), snap);
                }
                rebuildCommodityGrid();
                if (commoditiesLastUpdated != null) {
                    commoditiesLastUpdated.setText("Updated " +
                            new SimpleDateFormat("HH:mm:ss").format(new Date()));
                }
            }

            @Override
            protected void done() {
                if (commoditiesLastUpdated != null) {
                    commoditiesLastUpdated.setText("Updated " +
                            new SimpleDateFormat("HH:mm:ss").format(new Date()));
                }
                if (selectedCommodityTicker != null && commodityDetailCard != null
                        && commodityDetailCard.isVisible()) {
                    triggerCommodityDetailFetch(selectedCommodityTicker, selectedCommodityName);
                }
            }
        }.execute();
    }

    // =========================================================================
    // Notes persistence
    // =========================================================================

    /** Loads the notes properties file from disk (silently ignored on first run). */
    private void loadNotes() {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(NOTES_FILE)) {
            stockNotes.load(fis);
        } catch (IOException ignored) { /* fresh start */ }
    }

    /**
     * Saves the current note for the active ticker, then flushes all notes to disk.
     * Safe to call even when no ticker is loaded.
     */
    private void saveCurrentNotes() {
        if (stockNotesArea == null) return;
        String text = stockNotesArea.getText();
        if (currentTicker != null) {
            if (text.isBlank()) {
                stockNotes.remove(currentTicker);
            } else {
                stockNotes.setProperty(currentTicker, text);
            }
        }
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(NOTES_FILE)) {
            stockNotes.store(fos, "Stock Analyzer \u2014 Per-Ticker Notes");
            if (stockNotesStatusLabel != null) stockNotesStatusLabel.setText("saved");
        } catch (IOException ignored) {}
    }

    // =========================================================================
    // Crypto tab
    // =========================================================================

    /** Builds the full Crypto dashboard panel (mirrors Commodities tab). */
    private JPanel buildCryptoPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Crypto", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        cryptoLastUpdated = makeLabel("", CAPTION_FONT, theme.mutedText());
        cryptoLastUpdated.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(cryptoLastUpdated, BorderLayout.CENTER);
        JButton refreshBtn = makeActionButton("Refresh");
        refreshBtn.addActionListener(e -> refreshCryptosInBackground());
        header.add(refreshBtn, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(theme.background());

        cryptoGrid = new JPanel(new GridLayout(4, 2, SECTION_GAP, SECTION_GAP));
        cryptoGrid.setBackground(theme.background());
        cryptoGrid.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(cryptoGrid);
        content.add(Box.createVerticalStrut(SECTION_GAP));

        cryptoDetailCard = buildCryptoDetailCard();
        cryptoDetailCard.setVisible(false);
        content.add(cryptoDetailCard);
        rebuildCryptoGrid();

        JScrollPane scroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        cryptoRefreshTimer = new javax.swing.Timer(60_000, e -> refreshCryptosInBackground());
        cryptoRefreshTimer.start();

        return panel;
    }

    /** Builds the inline crypto detail card (mirrors buildCommodityDetailCard). */
    private JPanel buildCryptoDetailCard() {
        JPanel card = new JPanel(new BorderLayout(0, 10));
        card.setBackground(theme.card());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(14, 16, 14, 16)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel header = new JPanel(new BorderLayout(12, 0));
        header.setBackground(theme.card());

        JPanel titleStack = new JPanel(new GridLayout(2, 1, 0, 2));
        titleStack.setBackground(theme.card());
        cryptoDetailTitleLabel = makeLabel("Crypto Detail", STAT_VALUE_FONT, theme.primaryText());
        cryptoDetailMetaLabel  = makeLabel("Click a crypto card above to inspect it.",
                CAPTION_FONT, theme.mutedText());
        titleStack.add(cryptoDetailTitleLabel);
        titleStack.add(cryptoDetailMetaLabel);

        JPanel summaryStack = new JPanel(new GridLayout(3, 1, 0, 2));
        summaryStack.setBackground(theme.card());
        cryptoDetailPriceLabel = makeLabel("\u2014", HERO_PRICE_FONT, theme.primaryText());
        cryptoDetailPriceLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        cryptoDetailChangeLabel = makeLabel("", BODY_FONT, theme.mutedText());
        cryptoDetailChangeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        cryptoDetailRangeLabel = makeLabel("", CAPTION_FONT, theme.mutedText());
        cryptoDetailRangeLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        summaryStack.add(cryptoDetailPriceLabel);
        summaryStack.add(cryptoDetailChangeLabel);
        summaryStack.add(cryptoDetailRangeLabel);

        header.add(titleStack,   BorderLayout.WEST);
        header.add(summaryStack, BorderLayout.EAST);

        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.setBackground(theme.card());

        JPanel intervalBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        intervalBar.setBackground(theme.card());
        cryptoIntervalButtons = new JButton[CHART_INTERVALS.length];
        for (int i = 0; i < CHART_INTERVALS.length; i++) {
            final String[] cfg = CHART_INTERVALS[i];
            JButton btn = makeIntervalButton(cfg[0]);
            cryptoIntervalButtons[i] = btn;
            if (i == COMMODITY_DEFAULT_INTERVAL_INDEX) {
                applySelectedIntervalStyle(btn);
                selectedCryptoIntervalBtn = btn;
            }
            btn.addActionListener(e -> {
                setActiveCryptoIntervalButton(btn);
                currentCryptoBarInterval = cfg[1];
                currentCryptoTimeRange   = cfg[2];
                currentCryptoMaxBars     = cfg.length > 3 && cfg[3] != null
                        ? Integer.parseInt(cfg[3]) : 0;
                if (selectedCryptoTicker != null) {
                    triggerCryptoDetailFetch(selectedCryptoTicker, selectedCryptoName);
                }
            });
            intervalBar.add(btn);
        }

        JPanel indicatorBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        indicatorBar.setBackground(theme.card());
        cryptoMa20Toggle  = makeIndicatorToggle("MA 20");
        cryptoMa50Toggle  = makeIndicatorToggle("MA 50");
        cryptoRsiToggle   = makeIndicatorToggle("RSI");
        cryptoMacdToggle  = makeIndicatorToggle("MACD");
        indicatorBar.add(cryptoMa20Toggle);
        indicatorBar.add(cryptoMa50Toggle);
        indicatorBar.add(cryptoRsiToggle);
        indicatorBar.add(cryptoMacdToggle);

        controlsRow.add(intervalBar,  BorderLayout.WEST);
        controlsRow.add(indicatorBar, BorderLayout.EAST);

        cryptoChartPanel = new StockChartPanel();
        cryptoChartPanel.setPreferredSize(new Dimension(0, 340));
        cryptoMa20Toggle.addActionListener(
                e -> cryptoChartPanel.setShowMovingAverage20(cryptoMa20Toggle.isSelected()));
        cryptoMa50Toggle.addActionListener(
                e -> cryptoChartPanel.setShowMovingAverage50(cryptoMa50Toggle.isSelected()));
        cryptoRsiToggle.addActionListener(
                e -> cryptoChartPanel.setShowRSI(cryptoRsiToggle.isSelected()));
        cryptoMacdToggle.addActionListener(
                e -> cryptoChartPanel.setShowMACD(cryptoMacdToggle.isSelected()));

        card.add(header,       BorderLayout.NORTH);
        card.add(controlsRow,  BorderLayout.CENTER);
        card.add(cryptoChartPanel, BorderLayout.SOUTH);
        return card;
    }

    /** Rebuilds the crypto card grid from cached snapshots. */
    private void rebuildCryptoGrid() {
        if (cryptoGrid == null) return;
        cryptoGrid.removeAll();
        for (String[] crypto : CRYPTOS) {
            cryptoGrid.add(buildCryptoCard(cryptoSnapshots.get(crypto[0]), crypto[1]));
        }
        cryptoGrid.revalidate();
        cryptoGrid.repaint();
    }

    /** Builds one crypto card. Accepts {@code null} snap for a loading placeholder. */
    private JPanel buildCryptoCard(CommoditySnapshot snap, String displayName) {
        boolean selected  = snap != null && snap.ticker().equalsIgnoreCase(selectedCryptoTicker);
        Color baseBg      = selected ? theme.selectionBg() : theme.card();
        Color hoverBg     = selected ? theme.selectionBg().brighter() : theme.rowHoverBg();
        Color borderColor = selected ? theme.accent() : theme.border();

        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(baseBg);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderColor, selected ? 2 : 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.setCursor(snap != null
                ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                : Cursor.getDefaultCursor());

        JPanel headerPanel = new JPanel(new GridLayout(2, 1, 0, 2));
        headerPanel.setBackground(baseBg);
        String name = snap != null ? snap.name() : displayName;
        JLabel nameLabel   = makeLabel(name, BODY_FONT, theme.primaryText());
        JLabel tickerLabel = makeLabel(snap != null ? snap.ticker() : "Awaiting live data",
                CAPTION_FONT, selected ? theme.accent() : theme.mutedText());
        headerPanel.add(nameLabel);
        headerPanel.add(tickerLabel);
        card.add(headerPanel, BorderLayout.NORTH);

        JPanel centerRow = new JPanel(new BorderLayout(10, 0));
        centerRow.setBackground(baseBg);
        JPanel priceStack = new JPanel(new GridLayout(2, 1, 0, 2));
        priceStack.setBackground(baseBg);
        String priceText = snap != null ? formatPrice(snap.price(), "USD") : "\u2014";
        JLabel priceLabel     = makeLabel(priceText, STAT_VALUE_FONT, theme.primaryText());
        JLabel hintLabel      = makeLabel(snap != null ? "Click to open chart" : "Loading\u2026",
                CAPTION_FONT, theme.mutedText());
        priceStack.add(priceLabel);
        priceStack.add(hintLabel);
        centerRow.add(priceStack, BorderLayout.CENTER);

        SparklinePanel sparkline = null;
        if (snap != null && snap.sparkPrices() != null && snap.sparkPrices().length >= 2) {
            sparkline = new SparklinePanel();
            sparkline.setData(snap.sparkPrices(),
                    snap.sparkPrices()[snap.sparkPrices().length - 1] >= snap.sparkPrices()[0]
                            ? theme.gain() : theme.loss());
            sparkline.setPreferredSize(new Dimension(112, 58));
            centerRow.add(sparkline, BorderLayout.EAST);
        }
        card.add(centerRow, BorderLayout.CENTER);

        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        southRow.setBackground(baseBg);
        if (snap != null) {
            String sign      = snap.changePercent() >= 0 ? "+" : "";
            Color  chgColor  = snap.changePercent() >= 0 ? theme.gain() : theme.loss();
            southRow.add(makeLabel(String.format("%s%.2f%%", sign, snap.changePercent()),
                    CAPTION_FONT, chgColor));
            southRow.add(makeLabel(String.format("H: %.4g", snap.dayHigh()),
                    CAPTION_FONT, theme.mutedText()));
            southRow.add(makeLabel(String.format("L: %.4g", snap.dayLow()),
                    CAPTION_FONT, theme.mutedText()));
        } else {
            southRow.add(makeLabel("Loading\u2026", CAPTION_FONT, theme.mutedText()));
        }
        card.add(southRow, BorderLayout.SOUTH);

        if (snap != null) {
            final SparklinePanel sl = sparkline;
            MouseAdapter interaction = new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { showCryptoDetail(snap); }
                @Override public void mouseEntered(MouseEvent e) {
                    card.setBackground(hoverBg);
                    headerPanel.setBackground(hoverBg);
                    centerRow.setBackground(hoverBg);
                    priceStack.setBackground(hoverBg);
                    southRow.setBackground(hoverBg);
                }
                @Override public void mouseExited(MouseEvent e) {
                    card.setBackground(baseBg);
                    headerPanel.setBackground(baseBg);
                    centerRow.setBackground(baseBg);
                    priceStack.setBackground(baseBg);
                    southRow.setBackground(baseBg);
                }
            };
            for (JComponent c : new JComponent[]{card, headerPanel, centerRow, priceStack,
                    southRow, nameLabel, tickerLabel, priceLabel, hintLabel}) {
                c.addMouseListener(interaction);
                c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
            if (sl != null) {
                sl.addMouseListener(interaction);
                sl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            }
        }
        return card;
    }

    /** Opens the crypto inline detail card for the selected crypto. */
    private void showCryptoDetail(CommoditySnapshot snap) {
        if (snap == null || cryptoDetailCard == null || cryptoChartPanel == null) return;

        selectedCryptoTicker = snap.ticker();
        selectedCryptoName   = snap.name();
        resetCryptoIntervalSelectionToDefault();
        if (cryptoMa20Toggle  != null) cryptoMa20Toggle.setSelected(false);
        if (cryptoMa50Toggle  != null) cryptoMa50Toggle.setSelected(false);
        if (cryptoRsiToggle   != null) cryptoRsiToggle.setSelected(false);
        if (cryptoMacdToggle  != null) cryptoMacdToggle.setSelected(false);
        cryptoChartPanel.clearComparison();

        cryptoDetailTitleLabel.setText(snap.name() + " (" + snap.ticker() + ")");
        cryptoDetailMetaLabel.setText("Loading detailed chart\u2026");
        cryptoDetailPriceLabel.setText(formatPrice(snap.price(), "USD"));
        String sign = snap.changePercent() >= 0 ? "+" : "";
        cryptoDetailChangeLabel.setText(String.format("%s%.2f%% today", sign, snap.changePercent()));
        cryptoDetailChangeLabel.setForeground(snap.changePercent() >= 0 ? theme.gain() : theme.loss());
        cryptoDetailRangeLabel.setText(String.format("Day high %.4g  \u00B7  Day low %.4g",
                snap.dayHigh(), snap.dayLow()));
        cryptoDetailCard.setVisible(true);
        rebuildCryptoGrid();
        triggerCryptoDetailFetch(snap.ticker(), snap.name());

        SwingUtilities.invokeLater(() -> cryptoDetailCard.scrollRectToVisible(
                new Rectangle(0, 0, cryptoDetailCard.getWidth(), cryptoDetailCard.getHeight())));
    }

    /** Fetches the selected crypto's chart for the active interval. */
    private void triggerCryptoDetailFetch(String ticker, String displayName) {
        if (cryptoChartPanel == null || ticker == null) return;

        final String reqTicker    = ticker;
        final String reqName      = displayName != null ? displayName : ticker;
        final String reqInterval  = currentCryptoBarInterval;
        final String reqRange     = currentCryptoTimeRange;
        final int    reqMaxBars   = currentCryptoMaxBars;

        cryptoChartPanel.showLoadingMessage();
        cryptoDetailMetaLabel.setText("Loading " + findCryptoIntervalLabel() + " chart\u2026");

        new SwingWorker<ChartData, Void>() {
            @Override protected ChartData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchChart(reqTicker, reqInterval, reqRange);
            }
            @Override protected void done() {
                if (!reqTicker.equals(selectedCryptoTicker)
                        || !reqInterval.equals(currentCryptoBarInterval)
                        || !reqRange.equals(currentCryptoTimeRange)) return;
                try {
                    ChartData data = get();
                    if (reqMaxBars > 0) data = trimChartData(data, reqMaxBars);
                    cryptoChartPanel.setChartData(data);
                    populateCryptoDetailSummary(reqTicker, reqName, data);
                } catch (Exception ex) {
                    cryptoChartPanel.showError("Crypto chart unavailable");
                    cryptoDetailMetaLabel.setText("Unable to load " + reqName + " chart");
                    cryptoDetailChangeLabel.setText("");
                    cryptoDetailRangeLabel.setText("");
                }
            }
        }.execute();
    }

    /** Updates the crypto detail header labels from the fetched chart data. */
    private void populateCryptoDetailSummary(String ticker, String name, ChartData data) {
        if (data == null || data.prices.length == 0) return;
        double current   = data.prices[data.prices.length - 1];
        double first     = data.prices[0];
        double change    = current - first;
        double changePct = first == 0 ? 0 : change / first * 100.0;
        double viewHigh  = Arrays.stream(data.prices).max().orElse(current);
        double viewLow   = Arrays.stream(data.prices).min().orElse(current);
        String sign      = change >= 0 ? "+" : "";
        cryptoDetailTitleLabel.setText(name + " (" + ticker + ")");
        cryptoDetailMetaLabel.setText("Interactive " + findCryptoIntervalLabel()
                + " chart \u00B7 hover for price and volume");
        cryptoDetailPriceLabel.setText(formatPrice(current, "USD"));
        cryptoDetailChangeLabel.setText(String.format("%s%.4g  (%s%.2f%%)", sign, change, sign, changePct));
        cryptoDetailChangeLabel.setForeground(change >= 0 ? theme.gain() : theme.loss());
        cryptoDetailRangeLabel.setText(String.format("View high %.4g  \u00B7  View low %.4g",
                viewHigh, viewLow));
    }

    /** Returns the label of the currently active crypto interval button. */
    private String findCryptoIntervalLabel() {
        return selectedCryptoIntervalBtn != null ? selectedCryptoIntervalBtn.getText() : "1D";
    }

    /** Resets the crypto interval buttons to the 1D default. */
    private void resetCryptoIntervalSelectionToDefault() {
        if (cryptoIntervalButtons == null) return;
        if (selectedCryptoIntervalBtn != null) {
            selectedCryptoIntervalBtn.setBackground(theme.btnBg());
            selectedCryptoIntervalBtn.setForeground(theme.primaryText());
        }
        selectedCryptoIntervalBtn = cryptoIntervalButtons[COMMODITY_DEFAULT_INTERVAL_INDEX];
        applySelectedIntervalStyle(selectedCryptoIntervalBtn);
        currentCryptoBarInterval = CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][1];
        currentCryptoTimeRange   = CHART_INTERVALS[COMMODITY_DEFAULT_INTERVAL_INDEX][2];
        currentCryptoMaxBars     = 0;
    }

    /** Switches the highlighted crypto interval button. */
    private void setActiveCryptoIntervalButton(JButton btn) {
        if (selectedCryptoIntervalBtn != null) {
            selectedCryptoIntervalBtn.setBackground(theme.btnBg());
            selectedCryptoIntervalBtn.setForeground(theme.primaryText());
        }
        selectedCryptoIntervalBtn = btn;
        applySelectedIntervalStyle(btn);
    }

    /** Fetches live data for all crypto assets in the background and rebuilds cards. */
    private void refreshCryptosInBackground() {
        new SwingWorker<Void, CommoditySnapshot>() {
            @Override
            protected Void doInBackground() {
                for (String[] crypto : CRYPTOS) {
                    String ticker = crypto[0];
                    String name   = crypto[1];
                    try {
                        ChartData cd = YahooFinanceFetcher.fetchChart(ticker, "5m", "1d");
                        if (cd == null || cd.prices.length < 2) continue;
                        double[] prices   = cd.prices;
                        double price      = prices[prices.length - 1];
                        double first      = prices[0];
                        double changePct  = (price - first) / first * 100.0;
                        double dayHigh    = Arrays.stream(prices).max().orElse(price);
                        double dayLow     = Arrays.stream(prices).min().orElse(price);
                        double[] spark    = prices.length > 78
                                ? Arrays.copyOfRange(prices, prices.length - 78, prices.length)
                                : prices;
                        publish(new CommoditySnapshot(ticker, name, price, changePct,
                                dayHigh, dayLow, "USD", spark));
                    } catch (Exception ignored) {}
                }
                return null;
            }

            @Override
            protected void process(List<CommoditySnapshot> snaps) {
                for (CommoditySnapshot snap : snaps) cryptoSnapshots.put(snap.ticker(), snap);
                rebuildCryptoGrid();
                if (cryptoLastUpdated != null) {
                    cryptoLastUpdated.setText("Updated " +
                            new SimpleDateFormat("HH:mm:ss").format(new Date()));
                }
            }

            @Override
            protected void done() {
                if (cryptoLastUpdated != null) {
                    cryptoLastUpdated.setText("Updated " +
                            new SimpleDateFormat("HH:mm:ss").format(new Date()));
                }
                if (selectedCryptoTicker != null && cryptoDetailCard != null
                        && cryptoDetailCard.isVisible()) {
                    triggerCryptoDetailFetch(selectedCryptoTicker, selectedCryptoName);
                }
            }
        }.execute();
    }

    // =========================================================================
    // Sector Heatmap, Econ Calendar, Dividend Calendar, and Theme methods
    // =========================================================================

    private JPanel buildHeatmapPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Sector Heatmap", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        heatmapLastUpdated = makeLabel("", CAPTION_FONT, theme.mutedText());
        JButton refreshBtn = makeActionButton("Refresh");
        refreshBtn.addActionListener(e -> refreshHeatmapInBackground());
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setBackground(theme.background());
        right.add(heatmapLastUpdated);
        right.add(refreshBtn);
        header.add(right, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        heatmapGrid = new JPanel(new GridLayout(2, 6, SECTION_GAP, SECTION_GAP));
        heatmapGrid.setBackground(theme.background());
        rebuildHeatmapGrid();

        JScrollPane scroll = new JScrollPane(heatmapGrid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        heatmapRefreshTimer = new javax.swing.Timer(60_000, e -> refreshHeatmapInBackground());
        heatmapRefreshTimer.start();
        return panel;
    }

    private void rebuildHeatmapGrid() {
        heatmapGrid.removeAll();
        if (sectorSnapshots.isEmpty()) {
            for (String[] s : SECTOR_ETFS) {
                JPanel cell = new JPanel(new BorderLayout());
                cell.setBackground(theme.card());
                cell.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
                JLabel lbl = makeLabel(s[1], CAPTION_FONT, theme.mutedText());
                lbl.setHorizontalAlignment(SwingConstants.CENTER);
                cell.add(lbl, BorderLayout.CENTER);
                heatmapGrid.add(cell);
            }
        } else {
            for (SectorSnapshot snap : sectorSnapshots.values()) {
                heatmapGrid.add(buildSectorCell(snap));
            }
        }
        heatmapGrid.revalidate();
        heatmapGrid.repaint();
    }

    private JPanel buildSectorCell(SectorSnapshot snap) {
        Color bg = interpolateHeatmapColor(snap.changePercent());
        JPanel cell = new JPanel(new GridLayout(3, 1, 0, 2));
        cell.setBackground(bg);
        cell.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(8, 8, 8, 8)));
        cell.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel nameLabel = makeLabel(snap.sectorName(), CAPTION_FONT, Color.WHITE);
        JLabel tickerLabel = makeLabel(snap.etfTicker(), CAPTION_BOLD_FONT, Color.WHITE);
        String changeStr = (snap.changePercent() >= 0 ? "+" : "") + String.format("%.2f%%", snap.changePercent());
        JLabel changeLabel = makeLabel(changeStr, STAT_VALUE_FONT,
                snap.changePercent() >= 0 ? new Color(100, 230, 160) : new Color(255, 130, 130));
        nameLabel.setHorizontalAlignment(SwingConstants.CENTER);
        tickerLabel.setHorizontalAlignment(SwingConstants.CENTER);
        changeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        cell.add(nameLabel);
        cell.add(changeLabel);
        cell.add(tickerLabel);
        return cell;
    }

    private Color interpolateHeatmapColor(double pct) {
        double clamped = Math.max(-3.0, Math.min(3.0, pct));
        Color neutral = new Color(40, 40, 58);
        if (clamped >= 0) {
            double t = clamped / 3.0;
            int r = (int)(neutral.getRed()   * (1-t) + 20  * t);
            int g = (int)(neutral.getGreen() * (1-t) + 100 * t);
            int b = (int)(neutral.getBlue()  * (1-t) + 50  * t);
            return new Color(r, g, b);
        } else {
            double t = -clamped / 3.0;
            int r = (int)(neutral.getRed()   * (1-t) + 120 * t);
            int g = (int)(neutral.getGreen() * (1-t) + 20  * t);
            int b = (int)(neutral.getBlue()  * (1-t) + 30  * t);
            return new Color(r, g, b);
        }
    }

    private void refreshHeatmapInBackground() {
        if (heatmapLastUpdated != null) heatmapLastUpdated.setText("Loading\u2026");
        new SwingWorker<List<SectorSnapshot>, SectorSnapshot>() {
            @Override protected List<SectorSnapshot> doInBackground() {
                List<SectorSnapshot> results = new ArrayList<>();
                for (String[] etf : SECTOR_ETFS) {
                    try {
                        StockData d = YahooFinanceFetcher.fetch(etf[0]);
                        SectorSnapshot snap = new SectorSnapshot(etf[0], etf[1], d.priceChangePercent, d.currentPrice);
                        publish(snap);
                        results.add(snap);
                    } catch (Exception ignored) {}
                }
                return results;
            }
            @Override protected void process(List<SectorSnapshot> chunks) {
                for (SectorSnapshot s : chunks) sectorSnapshots.put(s.etfTicker(), s);
                rebuildHeatmapGrid();
            }
            @Override protected void done() {
                if (heatmapLastUpdated != null)
                    heatmapLastUpdated.setText("Updated " + new SimpleDateFormat("HH:mm:ss").format(new Date()));
            }
        }.execute();
    }

    private JPanel buildEconCalendarPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Economic Calendar 2026", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setBackground(theme.background());
        econStatusLabel = makeLabel("Showing 2026 macro events.", CAPTION_FONT, theme.mutedText());

        JButton allBtn   = makeActionButton("All");
        JButton highBtn  = makeActionButton("High Only");
        JButton upcomBtn = makeActionButton("Upcoming");
        allBtn.addActionListener(e   -> populateEconCalendarTable(allEconEvents, "all"));
        highBtn.addActionListener(e  -> populateEconCalendarTable(allEconEvents, "high"));
        upcomBtn.addActionListener(e -> populateEconCalendarTable(allEconEvents, "upcoming"));
        headerRight.add(econStatusLabel); headerRight.add(allBtn); headerRight.add(highBtn); headerRight.add(upcomBtn);
        header.add(headerRight, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        String[] cols = {"Date", "Time (ET)", "Event", "Importance", "Previous", "Forecast", "Actual"};
        econTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };
        econTable = new JTable(econTableModel);
        econTable.setBackground(theme.background());
        econTable.setForeground(theme.primaryText());
        econTable.setFont(BODY_FONT);
        econTable.setRowHeight(26);
        applyTableStyling(econTable);
        econTable.setShowGrid(true);
        econTable.setGridColor(theme.border());
        econTable.setSelectionBackground(theme.selectionBg());
        econTable.getTableHeader().setFont(CAPTION_FONT);
        econTable.getTableHeader().setBackground(theme.card());
        econTable.getTableHeader().setForeground(theme.accent());

        SimpleDateFormat econSdf = new SimpleDateFormat("MMM dd, yyyy");
        DefaultTableCellRenderer econRenderer = new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    Object imp = table.getModel().getValueAt(row, 3);
                    if ("High".equals(imp))   setBackground(new Color(50, 20, 20));
                    else if ("Med".equals(imp)) setBackground(new Color(40, 38, 15));
                    else setBackground(theme.background());
                    setForeground(theme.primaryText());
                }
                if (col == 0 && value instanceof Long) setText(econSdf.format(new Date((Long)value * 1000L)));
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        };
        for (int i = 0; i < cols.length; i++) econTable.getColumnModel().getColumn(i).setCellRenderer(econRenderer);
        econTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        econTable.getColumnModel().getColumn(1).setPreferredWidth(80);
        econTable.getColumnModel().getColumn(2).setPreferredWidth(220);
        econTable.getColumnModel().getColumn(3).setPreferredWidth(80);

        JScrollPane scroll = new JScrollPane(econTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1));
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        panel.add(scroll, BorderLayout.CENTER);

        allEconEvents = buildEconCalendar();
        populateEconCalendarTable(allEconEvents, "upcoming");
        return panel;
    }

    private void populateEconCalendarTable(List<EconEvent> events, String filter) {
        if (econTableModel == null || events == null) return;
        long nowSec = System.currentTimeMillis() / 1000L;
        econTableModel.setRowCount(0);
        int shown = 0;
        for (EconEvent ev : events) {
            boolean pass = switch(filter) {
                case "high"     -> "High".equals(ev.importance());
                case "upcoming" -> ev.eventDate() >= nowSec - 86400L;
                default         -> true;
            };
            if (!pass) continue;
            econTableModel.addRow(new Object[]{
                    ev.eventDate(), ev.eventTime(), ev.eventName(),
                    ev.importance(), ev.previous(), ev.forecast(), ev.actual()
            });
            shown++;
        }
        if (econStatusLabel != null) econStatusLabel.setText("Showing " + shown + " events.");
    }

    private static List<EconEvent> buildEconCalendar() {
        List<EconEvent> evs = new ArrayList<>();
        java.time.ZoneOffset utc = java.time.ZoneOffset.UTC;
        Function<int[], Long> dateOf = arr ->
                LocalDate.of(arr[0], arr[1], arr[2]).atStartOfDay().toEpochSecond(utc);

        int[][] fomcDates = {{2026,1,28},{2026,3,18},{2026,5,6},{2026,6,17},
                {2026,7,29},{2026,9,16},{2026,10,28},{2026,12,16}};
        for (int[] d : fomcDates)
            evs.add(new EconEvent(dateOf.apply(d), "2:00 PM", "FOMC Rate Decision", "High", "\u2014", "\u2014", "\u2014"));

        int[][] cpiDates = {{2026,1,14},{2026,2,11},{2026,3,11},{2026,4,10},
                {2026,5,13},{2026,6,10},{2026,7,15},{2026,8,12},
                {2026,9,11},{2026,10,14},{2026,11,12},{2026,12,11}};
        for (int[] d : cpiDates)
            evs.add(new EconEvent(dateOf.apply(d), "8:30 AM", "CPI (YoY)", "High", "\u2014", "\u2014", "\u2014"));

        int[][] nfpDates = {{2026,1,9},{2026,2,6},{2026,3,6},{2026,4,3},
                {2026,5,8},{2026,6,5},{2026,7,10},{2026,8,7},
                {2026,9,4},{2026,10,2},{2026,11,6},{2026,12,4}};
        for (int[] d : nfpDates)
            evs.add(new EconEvent(dateOf.apply(d), "8:30 AM", "Non-Farm Payrolls", "High", "\u2014", "\u2014", "\u2014"));

        int[][] ppiDates = {{2026,1,15},{2026,2,12},{2026,3,12},{2026,4,9},
                {2026,5,14},{2026,6,11},{2026,7,16},{2026,8,13},
                {2026,9,10},{2026,10,15},{2026,11,13},{2026,12,10}};
        for (int[] d : ppiDates)
            evs.add(new EconEvent(dateOf.apply(d), "8:30 AM", "PPI (MoM)", "Med", "\u2014", "\u2014", "\u2014"));

        int[][] gdpDates = {{2026,1,29},{2026,4,29},{2026,7,30},{2026,10,29}};
        for (int[] d : gdpDates)
            evs.add(new EconEvent(dateOf.apply(d), "8:30 AM", "GDP (Advance, QoQ)", "High", "\u2014", "\u2014", "\u2014"));

        evs.sort(Comparator.comparingLong(EconEvent::eventDate));
        return evs;
    }

    private JPanel buildDividendCalendarPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Dividend Calendar", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setBackground(theme.background());
        dividendStatusLabel = makeLabel("Load your watchlist tickers to see upcoming dividends.", CAPTION_FONT, theme.mutedText());
        JButton refreshBtn = makeActionButton("Refresh");
        refreshBtn.addActionListener(e -> refreshDividendsInBackground());
        headerRight.add(dividendStatusLabel);
        headerRight.add(refreshBtn);
        header.add(headerRight, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        JPanel infoCard = new JPanel(new BorderLayout());
        infoCard.setBackground(theme.card());
        infoCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(),1,true), new EmptyBorder(10,14,10,14)));
        infoCard.add(makeLabel(
                "Shows upcoming ex-dividend dates for your Watchlist. Color: green \u226470d, yellow \u226430d.",
                CAPTION_FONT, theme.mutedText()), BorderLayout.CENTER);

        String[] cols = {"Ticker", "Company", "Ex-Date", "Amount", "Yield %", "Frequency"};
        dividendTableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        dividendTable = new JTable(dividendTableModel);
        dividendTable.setBackground(theme.background());
        dividendTable.setForeground(theme.primaryText());
        dividendTable.setFont(BODY_FONT);
        dividendTable.setRowHeight(28);
        dividendTable.setShowGrid(true);
        dividendTable.setGridColor(theme.border());
        dividendTable.setSelectionBackground(new Color(42,60,96));
        dividendTable.setSelectionForeground(theme.primaryText());
        applyTableStyling(dividendTable);
        dividendTable.getTableHeader().setFont(CAPTION_FONT);
        dividendTable.getTableHeader().setBackground(new Color(24,24,38));
        dividendTable.getTableHeader().setForeground(theme.accent());

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
        DefaultTableCellRenderer divRenderer = new DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int col) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
                if (!isSelected) {
                    Object dateObj = table.getModel().getValueAt(row, 2);
                    if (dateObj instanceof Long) {
                        long dateSec = (Long) dateObj;
                        long daysAway = (dateSec - System.currentTimeMillis()/1000L) / 86400L;
                        if      (daysAway <= 7)  setBackground(new Color(20,48,20));
                        else if (daysAway <= 30) setBackground(new Color(48,42,10));
                        else                     setBackground(theme.background());
                    } else { setBackground(theme.background()); }
                    setForeground(theme.primaryText());
                }
                if (col == 2 && value instanceof Long) setText(sdf.format(new Date((Long)value * 1000L)));
                if (col == 3 && value instanceof Double) setText(String.format("$%.4f", (Double)value));
                if (col == 4 && value instanceof Double) setText(String.format("%.2f%%", (Double)value));
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        };
        for (int i = 0; i < cols.length; i++) dividendTable.getColumnModel().getColumn(i).setCellRenderer(divRenderer);
        dividendTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        dividendTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        dividendTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        dividendTable.getColumnModel().getColumn(3).setPreferredWidth(80);
        dividendTable.getColumnModel().getColumn(4).setPreferredWidth(80);
        dividendTable.getColumnModel().getColumn(5).setPreferredWidth(100);

        dividendTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    int row = dividendTable.getSelectedRow();
                    if (row >= 0) {
                        String ticker = (String) dividendTableModel.getValueAt(row, 0);
                        setActiveTab("results");
                        tickerInputField.setText(ticker);
                        triggerStockFetch();
                    }
                }
            }
        });

        JScrollPane scroll = new JScrollPane(dividendTable, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1));
        scroll.getViewport().setBackground(theme.background());
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setBackground(theme.card());
        tableCard.setBorder(BorderFactory.createLineBorder(theme.border(),1,true));
        tableCard.add(scroll, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(theme.background());
        content.add(infoCard, BorderLayout.NORTH);
        content.add(tableCard, BorderLayout.CENTER);
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }

    private void refreshDividendsInBackground() {
        List<String> tickers = new ArrayList<>();
        for (WatchlistManager.WatchlistEntry entry : watchlistManager.getEntries()) tickers.add(entry.ticker);
        if (tickers.isEmpty()) {
            if (dividendStatusLabel != null) dividendStatusLabel.setText("Add stocks to your Watchlist first.");
            return;
        }
        if (dividendStatusLabel != null) dividendStatusLabel.setText("Loading dividends for " + tickers.size() + " tickers\u2026");

        new SwingWorker<List<DividendEntry>, Void>() {
            @Override protected List<DividendEntry> doInBackground() {
                return YahooFinanceFetcher.fetchDividendCalendar(tickers);
            }
            @Override protected void done() {
                try {
                    List<DividendEntry> entries = get();
                    entries.sort(Comparator.comparingLong(DividendEntry::exDividendDate));
                    dividendTableModel.setRowCount(0);
                    for (DividendEntry e : entries) {
                        dividendTableModel.addRow(new Object[]{
                                e.ticker(), e.companyName(), e.exDividendDate(),
                                e.dividendAmount(), e.dividendYield(), e.frequency()
                        });
                    }
                    if (dividendStatusLabel != null) {
                        dividendStatusLabel.setText(entries.isEmpty()
                                ? "No upcoming dividends found for watchlist tickers."
                                : "Showing " + entries.size() + " dividend events \u00B7 updated "
                                        + new SimpleDateFormat("HH:mm:ss").format(new Date()));
                    }
                } catch (Exception ex) {
                    if (dividendStatusLabel != null) dividendStatusLabel.setText("Error loading dividend data.");
                }
            }
        }.execute();
    }

    private void applyThemeToAllPanels(Container container, ThemeColors prev) {
        for (Component c : container.getComponents()) {
            if (c instanceof JPanel p) {
                Color bg = p.getBackground();
                if (bg != null) {
                    if (colorMatches(bg, prev.background())) p.setBackground(theme.background());
                    else if (colorMatches(bg, prev.card()))  p.setBackground(theme.card());
                }
            }
            if (c instanceof JLabel lbl) {
                Color fg = lbl.getForeground();
                if (colorMatches(fg, prev.primaryText())) lbl.setForeground(theme.primaryText());
                else if (colorMatches(fg, prev.mutedText())) lbl.setForeground(theme.mutedText());
                else if (colorMatches(fg, prev.accent())) lbl.setForeground(theme.accent());
                else if (colorMatches(fg, prev.gain())) lbl.setForeground(theme.gain());
                else if (colorMatches(fg, prev.loss())) lbl.setForeground(theme.loss());
            }
            if (c instanceof JButton btn) {
                Color prevBg = btn.getBackground();
                if (prevBg != null && colorMatches(prevBg, prev.btnBg())) {
                    btn.setBackground(theme.btnBg());
                } else if (prevBg != null && colorMatches(prevBg, prev.btnHoverBg())) {
                    btn.setBackground(theme.btnHoverBg());
                } else if (prevBg != null && colorMatches(prevBg, prev.card())) {
                    btn.setBackground(theme.card());
                }
                Color prevFg = btn.getForeground();
                if (prevFg != null && colorMatches(prevFg, prev.primaryText())) {
                    btn.setForeground(theme.primaryText());
                } else if (prevFg != null && colorMatches(prevFg, prev.accent())) {
                    btn.setForeground(theme.accent());
                } else if (prevFg != null && colorMatches(prevFg, prev.mutedText())) {
                    btn.setForeground(theme.mutedText());
                }
            }
            if (c instanceof JTable t) {
                t.setBackground(theme.background());
                t.setForeground(theme.primaryText());
                t.getTableHeader().setBackground(theme.card());
                t.getTableHeader().setForeground(theme.accent());
                t.setGridColor(theme.border());
            }
            if (c instanceof JScrollPane sp) {
                sp.getViewport().setBackground(theme.background());
            }
            if (c instanceof JTextField tf) {
                tf.setBackground(theme.btnBg());
                tf.setForeground(theme.primaryText());
                tf.setCaretColor(theme.accent());
            }
            if (c instanceof JTextArea ta) {
                ta.setBackground(theme.card());
                ta.setForeground(theme.primaryText());
            }
            if (c instanceof Container cc) applyThemeToAllPanels(cc, prev);
        }
    }

    private boolean colorMatches(Color a, Color b) {
        if (a == null || b == null) return false;
        return a.getRed() == b.getRed() && a.getGreen() == b.getGreen() && a.getBlue() == b.getBlue();
    }

    private void saveThemePreference() {
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream("settings.properties")) {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("theme", isDarkTheme ? "dark" : "light");
            p.store(fos, null);
        } catch (Exception ignored) {}
    }

    private void loadThemePreference() {
        try {
            java.util.Properties p = new java.util.Properties();
            try (java.io.FileInputStream fis = new java.io.FileInputStream("settings.properties")) {
                p.load(fis);
            }
            isDarkTheme = !"light".equals(p.getProperty("theme"));
            theme = isDarkTheme ? DARK_THEME : LIGHT_THEME;
            updateUIManagerForTheme(theme);
        } catch (Exception ignored) {}
    }

    // =========================================================================
    // Earnings Calendar tab
    // =========================================================================

    /** Builds the Earnings Calendar panel with a watchlist-driven earnings table. */
    private JPanel buildEarningsCalendarPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        // --- Header -----------------------------------------------------------
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Earnings Calendar", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setBackground(theme.background());
        earningsStatusLabel = makeLabel("Load your watchlist tickers to see upcoming earnings.",
                CAPTION_FONT, theme.mutedText());
        JButton refreshBtn = makeActionButton("Refresh");
        refreshBtn.addActionListener(e -> refreshEarningsInBackground());
        headerRight.add(earningsStatusLabel);
        headerRight.add(refreshBtn);
        header.add(headerRight, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // --- Explanation card -------------------------------------------------
        JPanel infoCard = new JPanel(new BorderLayout());
        infoCard.setBackground(theme.card());
        infoCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        JLabel infoLabel = makeLabel(
                "Shows upcoming earnings for all stocks in your Watchlist.  "
                + "Color coding: green = within 7 days, yellow = within 14 days, "
                + "blue = within 30 days.",
                CAPTION_FONT, theme.mutedText());
        infoLabel.setHorizontalAlignment(SwingConstants.LEFT);
        infoCard.add(infoLabel, BorderLayout.CENTER);

        // --- Earnings table ---------------------------------------------------
        String[] columns = {"Ticker", "Company", "Date", "Time", "EPS Est.", "EPS Actual", "Surprise"};
        earningsTableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return false; }
        };

        earningsTable = new JTable(earningsTableModel);
        earningsTable.setBackground(theme.background());
        earningsTable.setForeground(theme.primaryText());
        earningsTable.setFont(BODY_FONT);
        earningsTable.setRowHeight(28);
        earningsTable.setShowGrid(true);
        earningsTable.setGridColor(theme.border());
        earningsTable.setSelectionBackground(theme.selectionBg());
        earningsTable.setSelectionForeground(theme.primaryText());
        applyTableStyling(earningsTable);
        earningsTable.getTableHeader().setFont(CAPTION_FONT);
        earningsTable.getTableHeader().setBackground(theme.card());
        earningsTable.getTableHeader().setForeground(theme.accent());

        // Custom renderer for color-coded rows
        DefaultTableCellRenderer earningsRenderer = new DefaultTableCellRenderer() {
            private final SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
            @Override
            public java.awt.Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (!isSelected) {
                    // Get the date from column 2 to determine color
                    Object dateObj = table.getModel().getValueAt(row, 2);
                    if (dateObj instanceof Long) {
                        long earningsDateSec = (Long) dateObj;
                        long nowSec = System.currentTimeMillis() / 1000L;
                        long daysAway = (earningsDateSec - nowSec) / 86400L;
                        if      (daysAway <= 7)  setBackground(new Color(20, 48, 20));
                        else if (daysAway <= 14) setBackground(new Color(48, 42, 10));
                        else if (daysAway <= 30) setBackground(new Color(18, 30, 56));
                        else                     setBackground(theme.background());
                    } else {
                        setBackground(theme.background());
                    }
                    setForeground(theme.primaryText());
                }
                // Format date column
                if (column == 2 && value instanceof Long) {
                    setText(sdf.format(new Date((Long) value * 1000L)));
                }
                // Format EPS columns
                if ((column == 4 || column == 5) && value instanceof Double) {
                    double d = (Double) value;
                    setText(Double.isNaN(d) ? "N/A" : String.format("$%.2f", d));
                }
                // Format Surprise column
                if (column == 6 && value instanceof Double) {
                    double d = (Double) value;
                    if (Double.isNaN(d)) { setText("N/A"); }
                    else {
                        setText(String.format("%+.1f%%", d));
                        setForeground(d >= 0 ? theme.gain() : theme.loss());
                    }
                }
                setBorder(new EmptyBorder(0, 8, 0, 8));
                return this;
            }
        };
        for (int col = 0; col < columns.length; col++) {
            earningsTable.getColumnModel().getColumn(col).setCellRenderer(earningsRenderer);
        }
        earningsTable.getColumnModel().getColumn(0).setPreferredWidth(70);
        earningsTable.getColumnModel().getColumn(1).setPreferredWidth(220);
        earningsTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        earningsTable.getColumnModel().getColumn(3).setPreferredWidth(70);
        earningsTable.getColumnModel().getColumn(4).setPreferredWidth(90);
        earningsTable.getColumnModel().getColumn(5).setPreferredWidth(90);
        earningsTable.getColumnModel().getColumn(6).setPreferredWidth(90);

        JScrollPane tableScroll = new JScrollPane(earningsTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        tableScroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1));
        tableScroll.getViewport().setBackground(theme.background());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel tableCard = new JPanel(new BorderLayout());
        tableCard.setBackground(theme.card());
        tableCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(0, 0, 0, 0)));
        tableCard.add(tableScroll, BorderLayout.CENTER);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBackground(theme.background());
        content.add(infoCard,   BorderLayout.NORTH);
        content.add(tableCard,  BorderLayout.CENTER);
        panel.add(content, BorderLayout.CENTER);

        return panel;
    }

    /**
     * Fetches earnings for all watchlist tickers on a background thread and
     * populates the earnings table on the EDT.
     */
    private void refreshEarningsInBackground() {
        List<String> tickers = new ArrayList<>();
        for (WatchlistManager.WatchlistEntry entry : watchlistManager.getEntries()) {
            tickers.add(entry.ticker);
        }
        if (tickers.isEmpty()) {
            if (earningsStatusLabel != null) {
                earningsStatusLabel.setText("Add stocks to your Watchlist first.");
            }
            return;
        }

        if (earningsStatusLabel != null) {
            earningsStatusLabel.setText("Loading earnings for " + tickers.size() + " tickers\u2026");
        }

        new SwingWorker<List<EarningsEntry>, Void>() {
            @Override protected List<EarningsEntry> doInBackground() {
                return YahooFinanceFetcher.fetchEarningsCalendar(tickers);
            }

            @Override protected void done() {
                try {
                    List<EarningsEntry> entries = get();
                    // Sort by earnings date ascending
                    entries.sort(Comparator.comparingLong(EarningsEntry::earningsDate));

                    earningsTableModel.setRowCount(0);
                    long nowSec = System.currentTimeMillis() / 1000L;
                    for (EarningsEntry e : entries) {
                        double surprise = Double.NaN;
                        if (!Double.isNaN(e.epsActual()) && !Double.isNaN(e.epsEstimate())
                                && e.epsEstimate() != 0) {
                            surprise = (e.epsActual() - e.epsEstimate()) / Math.abs(e.epsEstimate()) * 100.0;
                        }
                        earningsTableModel.addRow(new Object[]{
                                e.ticker(),
                                e.companyName(),
                                e.earningsDate(),   // raw long — rendered by custom renderer
                                e.earningsTime(),
                                e.epsEstimate(),    // NaN = "N/A"
                                e.epsActual(),
                                surprise
                        });
                    }

                    if (earningsStatusLabel != null) {
                        earningsStatusLabel.setText(entries.isEmpty()
                                ? "No upcoming earnings found for watchlist tickers."
                                : "Showing " + entries.size() + " earnings event"
                                        + (entries.size() == 1 ? "" : "s") + "  \u00B7  updated "
                                        + new SimpleDateFormat("HH:mm:ss").format(new Date()));
                    }
                } catch (Exception ex) {
                    if (earningsStatusLabel != null) {
                        earningsStatusLabel.setText("Error loading earnings data.");
                    }
                }
            }
        }.execute();
    }

    // =========================================================================
    // Portfolio chart inner panel
    // =========================================================================

    private class PortfolioChartPanel extends JPanel {
        private List<PortfolioHistoryManager.DailySnapshot> data = new ArrayList<>();

        void setData(List<PortfolioHistoryManager.DailySnapshot> snapshots) {
            this.data = snapshots;
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int pad = 48, padTop = 12, padBottom = 28;
            g2.setColor(theme.card());
            g2.fillRect(0, 0, w, h);
            if (data == null || data.size() < 2) {
                g2.setColor(theme.mutedText());
                g2.setFont(CAPTION_FONT);
                String msg = "No historical data yet \u2014 check back after prices refresh.";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
                g2.dispose(); return;
            }
            double minV = data.stream().mapToDouble(PortfolioHistoryManager.DailySnapshot::totalValue).min().orElse(0);
            double maxV = data.stream().mapToDouble(PortfolioHistoryManager.DailySnapshot::totalValue).max().orElse(1);
            if (maxV == minV) maxV = minV + 1;
            double range = maxV - minV;
            int chartW = w - pad - 8, chartH = h - padTop - padBottom;
            int n = data.size();
            g2.setFont(CAPTION_FONT); g2.setColor(theme.mutedText());
            for (int i = 0; i <= 4; i++) {
                double val = minV + range * i / 4.0;
                int y = padTop + chartH - (int)(chartH * i / 4.0);
                g2.drawString(String.format("$%.0f", val), 2, y + 4);
                g2.setColor(theme.border());
                g2.drawLine(pad, y, w - 8, y);
                g2.setColor(theme.mutedText());
            }
            Path2D path = new Path2D.Double();
            for (int i = 0; i < n; i++) {
                double v = data.get(i).totalValue();
                int x = pad + (int)((double)i / (n-1) * chartW);
                int y = padTop + chartH - (int)((v - minV) / range * chartH);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g2.setStroke(new BasicStroke(2f));
            g2.setColor(theme.accent()); g2.draw(path);
            g2.setFont(CAPTION_FONT); g2.setColor(theme.mutedText());
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d");
            int labelStep = Math.max(1, n / 5);
            for (int i = 0; i < n; i += labelStep) {
                long epochMs = data.get(i).epochDay() * 86400000L;
                String lbl = sdf.format(new java.util.Date(epochMs));
                int x = pad + (int)((double)i / (n-1) * chartW);
                g2.drawString(lbl, x - g2.getFontMetrics().stringWidth(lbl)/2, h - 6);
            }
            g2.dispose();
        }
    }

    // =========================================================================
    // Sparkline inner panel
    // =========================================================================

    private static class SparklinePanel extends JPanel {
        private double[] prices;
        private Color lineColor = DARK_THEME.accent();

        void setData(double[] prices, Color lineColor) {
            this.prices = prices;
            this.lineColor = lineColor != null ? lineColor : DARK_THEME.accent();
            setOpaque(false);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (prices == null || prices.length < 2) return;
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double min   = Arrays.stream(prices).min().getAsDouble();
            double max   = Arrays.stream(prices).max().getAsDouble();
            double range = max - min == 0 ? 1 : max - min;
            int w = getWidth(), h = getHeight();
            int leftPad = 4, rightPad = 4, topPad = 4, bottomPad = 4;

            Path2D fill = new Path2D.Float();
            fill.moveTo(leftPad, h - bottomPad);
            for (int i = 0; i < prices.length; i++) {
                double x = leftPad + (w - leftPad - rightPad) * i / (prices.length - 1.0);
                double y = topPad + (h - topPad - bottomPad)
                        * (1.0 - (prices[i] - min) / range);
                fill.lineTo(x, y);
            }
            fill.lineTo(w - rightPad, h - bottomPad);
            fill.closePath();
            g2.setColor(new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(), 32));
            g2.fill(fill);

            g2.setColor(lineColor);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D path = new Path2D.Float();
            for (int i = 0; i < prices.length; i++) {
                double x = leftPad + (w - leftPad - rightPad) * i / (prices.length - 1.0);
                double y = topPad + (h - topPad - bottomPad)
                        * (1.0 - (prices[i] - min) / range);
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g2.draw(path);
        }
    }

    // =========================================================================
    // Options panel
    // =========================================================================

    private record OptionsFilterCriteria(String typeFilter, String moneynessFilter,
            int minVolume, int minOpenInterest, double maxDistancePercent, String sortMode) {}

    private record OptionTableRow(OptionsContract contract, boolean call,
            OptionAnalysis analysis, String moneynessLabel) {}

    /** Builds the Options analysis workspace. */
    private JPanel buildOptionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        optionsTickerField = new JTextField(8);
        optionsTickerField.setUI(new javax.swing.plaf.basic.BasicTextFieldUI());
        optionsTickerField.setFont(MONOSPACE_FONT);
        optionsTickerField.setBackground(theme.btnBg());
        optionsTickerField.setForeground(theme.primaryText());
        optionsTickerField.setOpaque(true);
        optionsTickerField.setCaretColor(theme.accent());
        optionsTickerField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(6, 10, 6, 10)));
        optionsTickerField.putClientProperty("JTextField.placeholderText", "e.g. AAPL");

        JButton loadBtn = makeActionButton("Load Options");
        loadBtn.addActionListener(e -> {
            String ticker = optionsTickerField.getText().trim().toUpperCase();
            if (!ticker.isEmpty()) loadOptionsInBackground(ticker);
        });
        optionsTickerField.addActionListener(e -> loadBtn.doClick());

        optionsStatusLabel = makeLabel(
                "Load an option chain to analyze liquidity, pricing, and break-even structure.",
                CAPTION_FONT, theme.mutedText());

        expirationCombo = new JComboBox<>();
        styleComboBox(expirationCombo);
        expirationCombo.addActionListener(e -> {
            if (optionsAdjustingExpiration || currentOptionsTicker == null
                    || currentOptionsChain == null || expirationCombo.getSelectedIndex() < 0) {
                return;
            }
            int idx = expirationCombo.getSelectedIndex();
            if (idx >= currentOptionsChain.expirationDates.length) return;
            long requestedExpiration = currentOptionsChain.expirationDates[idx];
            if (requestedExpiration == selectedExpiration) refreshOptionsView();
            else loadOptionsForExpiration(currentOptionsTicker, requestedExpiration);
        });

        JPanel controlsCard = new JPanel();
        controlsCard.setLayout(new BoxLayout(controlsCard, BoxLayout.Y_AXIS));
        controlsCard.setBackground(theme.card());
        controlsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));

        JPanel loadRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        loadRow.setBackground(theme.card());
        loadRow.add(optionsTickerField);
        loadRow.add(loadBtn);
        loadRow.add(Box.createHorizontalStrut(8));
        loadRow.add(makeLabel("Expiration:", CAPTION_FONT, theme.mutedText()));
        loadRow.add(expirationCombo);
        loadRow.add(Box.createHorizontalStrut(10));
        loadRow.add(optionsStatusLabel);
        controlsCard.add(loadRow);

        JPanel summaryGrid = new JPanel(new GridLayout(2, 4, CARD_GAP, CARD_GAP));
        summaryGrid.setBackground(theme.card());
        summaryGrid.setBorder(new EmptyBorder(10, 0, 0, 0));
        optionsUnderlyingValueLabel = createOptionsSummaryValueLabel();
        optionsDaysToExpiryValueLabel = createOptionsSummaryValueLabel();
        optionsAtmValueLabel = createOptionsSummaryValueLabel();
        optionsImpliedMoveValueLabel = createOptionsSummaryValueLabel();
        optionsPutCallRatioValueLabel = createOptionsSummaryValueLabel();
        optionsMaxPainValueLabel = createOptionsSummaryValueLabel();
        optionsIvRankValueLabel = createOptionsSummaryValueLabel();
        summaryGrid.add(buildOptionsSummaryCard("Underlying", optionsUnderlyingValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("Days To Expiry", optionsDaysToExpiryValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("ATM Pivot", optionsAtmValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("Implied Move", optionsImpliedMoveValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("Put/Call Flow", optionsPutCallRatioValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("Max Pain", optionsMaxPainValueLabel));
        summaryGrid.add(buildOptionsSummaryCard("IV Rank", optionsIvRankValueLabel));
        summaryGrid.add(new JPanel() {{ setBackground(theme.card()); setBorder(BorderFactory.createLineBorder(theme.border(),1,true)); }});
        controlsCard.add(summaryGrid);

        JPanel filtersCard = new JPanel(new BorderLayout(0, 8));
        filtersCard.setBackground(theme.card());
        filtersCard.setBorder(new EmptyBorder(12, 0, 0, 0));

        JPanel labelsRow = new JPanel(new GridLayout(1, 6, 8, 0));
        labelsRow.setBackground(theme.card());
        labelsRow.add(makeLabel("Contract Type", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Moneyness", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min Volume", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Min OI", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Max Strike Distance %", CAPTION_FONT, theme.mutedText()));
        labelsRow.add(makeLabel("Sort By", CAPTION_FONT, theme.mutedText()));

        optionsTypeFilterCombo = new JComboBox<>(OPTIONS_TYPE_FILTERS);
        optionsMoneynessFilterCombo = new JComboBox<>(OPTIONS_MONEYNESS_FILTERS);
        optionsSortCombo = new JComboBox<>(OPTIONS_SORT_MODES);
        styleComboBox(optionsTypeFilterCombo);
        styleComboBox(optionsMoneynessFilterCombo);
        styleComboBox(optionsSortCombo);
        optionsTypeFilterCombo.addActionListener(e -> refreshOptionsView());
        optionsMoneynessFilterCombo.addActionListener(e -> refreshOptionsView());
        optionsSortCombo.addActionListener(e -> refreshOptionsView());

        optionsMinVolumeField = makeFilterField("100", this::refreshOptionsView);
        optionsMinOpenInterestField = makeFilterField("250", this::refreshOptionsView);
        optionsMaxDistanceField = makeFilterField("10", this::refreshOptionsView);

        JPanel inputsRow = new JPanel(new GridLayout(1, 6, 8, 0));
        inputsRow.setBackground(theme.card());
        inputsRow.add(optionsTypeFilterCombo);
        inputsRow.add(optionsMoneynessFilterCombo);
        inputsRow.add(optionsMinVolumeField);
        inputsRow.add(optionsMinOpenInterestField);
        inputsRow.add(optionsMaxDistanceField);
        inputsRow.add(optionsSortCombo);

        JButton applyBtn = makeActionButton("Apply Filters");
        JButton resetBtn = makeActionButton("Reset Filters");
        applyBtn.addActionListener(e -> refreshOptionsView());
        resetBtn.addActionListener(e -> resetOptionsFilters());

        JPanel filterActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        filterActions.setBackground(theme.card());
        filterActions.add(resetBtn);
        filterActions.add(applyBtn);

        filtersCard.add(labelsRow, BorderLayout.NORTH);
        filtersCard.add(inputsRow, BorderLayout.CENTER);
        filtersCard.add(filterActions, BorderLayout.SOUTH);
        controlsCard.add(filtersCard);

        panel.add(controlsCard, BorderLayout.NORTH);

        JPanel contractsCard = new JPanel(new BorderLayout(0, 8));
        contractsCard.setBackground(theme.card());
        contractsCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));

        JPanel contractsHeader = new JPanel(new BorderLayout());
        contractsHeader.setBackground(theme.card());
        contractsHeader.add(makeLabel("Contracts", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.WEST);
        optionsResultsLabel = makeLabel("No contracts loaded.", CAPTION_FONT, theme.mutedText());
        contractsHeader.add(optionsResultsLabel, BorderLayout.EAST);
        contractsCard.add(contractsHeader, BorderLayout.NORTH);

        optionsContractsTableModel = new OptionsContractsTableModel();
        optionsContractsTable = new JTable(optionsContractsTableModel);
        optionsContractsTable.setUI(new javax.swing.plaf.basic.BasicTableUI());
        optionsContractsTable.setFont(BODY_FONT);
        optionsContractsTable.setForeground(theme.primaryText());
        optionsContractsTable.setBackground(theme.background());
        optionsContractsTable.setGridColor(theme.border());
        optionsContractsTable.setRowHeight(26);
        optionsContractsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        optionsContractsTable.setSelectionBackground(theme.selectionBg());
        optionsContractsTable.setSelectionForeground(theme.primaryText());
        applyTableStyling(optionsContractsTable);
        optionsContractsTable.setFillsViewportHeight(true);
        optionsContractsTable.setShowVerticalLines(true);
        optionsContractsTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        optionsContractsTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            int rowIndex = optionsContractsTable.getSelectedRow();
            if (rowIndex < 0) {
                selectedOptionRow = null;
                clearOptionsDetail();
                return;
            }
            OptionTableRow row = optionsContractsTableModel.getRow(rowIndex);
            if (row != null) {
                selectedOptionRow = row;
                updateOptionsDetail(row);
            }
        });

        JTableHeader optionsHeader = optionsContractsTable.getTableHeader();
        optionsHeader.setUI(new javax.swing.plaf.basic.BasicTableHeaderUI());
        optionsHeader.setFont(CAPTION_FONT);
        optionsHeader.setBackground(theme.card());
        optionsHeader.setForeground(theme.accent());
        optionsHeader.setBorder(BorderFactory.createLineBorder(theme.border()));
        optionsHeader.setReorderingAllowed(false);

        configureOptionsTableRenderers();

        JScrollPane tableScroll = new JScrollPane(optionsContractsTable,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        tableScroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        tableScroll.getViewport().setBackground(theme.background());
        tableScroll.setBackground(theme.background());
        tableScroll.getVerticalScrollBar().setUnitIncrement(16);
        styleScrollBar(tableScroll.getVerticalScrollBar());
        styleScrollBar(tableScroll.getHorizontalScrollBar());
        contractsCard.add(tableScroll, BorderLayout.CENTER);

        JPanel detailCard = buildOptionsDetailCard();

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, contractsCard, detailCard);
        splitPane.setResizeWeight(0.64);
        splitPane.setDividerSize(6);
        splitPane.setBorder(null);
        splitPane.setBackground(theme.background());
        splitPane.setUI(new javax.swing.plaf.basic.BasicSplitPaneUI());
        splitPane.getUI().installUI(splitPane);
        splitPane.setOpaque(true);
        panel.add(splitPane, BorderLayout.CENTER);

        clearOptionsSummary();
        clearOptionsDetail();
        return panel;
    }

    private JLabel createOptionsSummaryValueLabel() {
        JLabel label = makeLabel("\u2014", new Font("Segoe UI", Font.BOLD, 16), theme.primaryText());
        label.setVerticalAlignment(SwingConstants.TOP);
        return label;
    }

    private JPanel buildOptionsSummaryCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(theme.background());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.add(makeLabel(title, CAPTION_FONT, theme.mutedText()), BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    private JPanel buildOptionsDetailCard() {
        JPanel detailShell = new JPanel(new BorderLayout(0, 8));
        detailShell.setBackground(theme.card());
        detailShell.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));

        detailShell.add(makeLabel("Contract Detail", STAT_VALUE_FONT, theme.primaryText()), BorderLayout.NORTH);

        JPanel content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        content.setBackground(theme.card());

        optionsDetailTitleLabel = makeLabel("Select a contract", new Font("Segoe UI", Font.BOLD, 18), theme.primaryText());
        optionsDetailSubtitleLabel = makeLabel(
                "Load a chain and click a row to inspect its structure.",
                BODY_FONT, theme.mutedText());
        content.add(optionsDetailTitleLabel);
        content.add(Box.createVerticalStrut(4));
        content.add(optionsDetailSubtitleLabel);
        content.add(Box.createVerticalStrut(12));

        JPanel metricsGrid = new JPanel(new GridLayout(5, 2, 8, 8));
        metricsGrid.setBackground(theme.card());
        optionsDetailPremiumLabel = createOptionsDetailValueLabel();
        optionsDetailSpreadLabel = createOptionsDetailValueLabel();
        optionsDetailBreakEvenLabel = createOptionsDetailValueLabel();
        optionsDetailIntrinsicLabel = createOptionsDetailValueLabel();
        optionsDetailExtrinsicLabel = createOptionsDetailValueLabel();
        optionsDetailDistanceLabel = createOptionsDetailValueLabel();
        optionsDetailLiquidityLabel = createOptionsDetailValueLabel();
        optionsDetailDeltaProbLabel = createOptionsDetailValueLabel();
        optionsDetailGammaVegaLabel = createOptionsDetailValueLabel();
        optionsDetailThetaLabel = createOptionsDetailValueLabel();
        optionsDetailCapitalLabel = createOptionsDetailValueLabel();

        metricsGrid.add(buildOptionsDetailMetricCard("Premium Stack", optionsDetailPremiumLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Spread Quality", optionsDetailSpreadLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Break-even", optionsDetailBreakEvenLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Intrinsic Value", optionsDetailIntrinsicLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Extrinsic Value", optionsDetailExtrinsicLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Strike Distance", optionsDetailDistanceLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Liquidity", optionsDetailLiquidityLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Delta / Prob ITM", optionsDetailDeltaProbLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Gamma / Vega", optionsDetailGammaVegaLabel));
        metricsGrid.add(buildOptionsDetailMetricCard("Theta / Day", optionsDetailThetaLabel));
        content.add(metricsGrid);
        content.add(Box.createVerticalStrut(8));

        JPanel capitalCard = buildOptionsDetailMetricCard("Capital At Mid", optionsDetailCapitalLabel);
        capitalCard.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(capitalCard);
        content.add(Box.createVerticalStrut(10));

        JLabel payoffHeader = makeLabel("Payoff at Expiration", STAT_VALUE_FONT, theme.primaryText());
        payoffHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(payoffHeader);
        content.add(Box.createVerticalStrut(6));
        optionsPayoffPanel = new OptionsPayoffPanel();
        optionsPayoffPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        optionsPayoffPanel.setPreferredSize(new Dimension(300, 180));
        optionsPayoffPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 180));
        content.add(optionsPayoffPanel);
        content.add(Box.createVerticalStrut(10));

        JLabel notesHeader = makeLabel("Trade Read", STAT_VALUE_FONT, theme.primaryText());
        notesHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(notesHeader);
        content.add(Box.createVerticalStrut(6));

        optionsDetailNotesArea = new JTextArea();
        optionsDetailNotesArea.setEditable(false);
        optionsDetailNotesArea.setLineWrap(true);
        optionsDetailNotesArea.setWrapStyleWord(true);
        optionsDetailNotesArea.setFont(BODY_FONT);
        optionsDetailNotesArea.setBackground(theme.background());
        optionsDetailNotesArea.setForeground(theme.primaryText());
        optionsDetailNotesArea.setBorder(new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H));
        optionsDetailNotesArea.setRows(8);

        JScrollPane notesScroll = new JScrollPane(optionsDetailNotesArea,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        notesScroll.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        notesScroll.getViewport().setBackground(theme.background());
        notesScroll.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(notesScroll);

        JScrollPane contentScroll = new JScrollPane(content,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        contentScroll.setBorder(null);
        contentScroll.getViewport().setBackground(theme.card());
        contentScroll.getVerticalScrollBar().setUnitIncrement(16);
        detailShell.add(contentScroll, BorderLayout.CENTER);

        return detailShell;
    }

    private JLabel createOptionsDetailValueLabel() {
        JLabel label = makeLabel("\u2014", STAT_VALUE_FONT, theme.primaryText());
        label.setVerticalAlignment(SwingConstants.TOP);
        return label;
    }

    private JPanel buildOptionsDetailMetricCard(String title, JLabel valueLabel) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(theme.background());
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(theme.border(), 1, true),
                new EmptyBorder(CARD_PADDING_V, CARD_PADDING_H, CARD_PADDING_V, CARD_PADDING_H)));
        card.add(makeLabel(title, CAPTION_FONT, theme.mutedText()), BorderLayout.NORTH);
        card.add(valueLabel, BorderLayout.CENTER);
        return card;
    }

    /** Loads the nearest available options chain for {@code ticker}. */
    private void loadOptionsInBackground(String ticker) {
        String normalizedTicker = ticker == null ? "" : ticker.trim().toUpperCase();
        if (normalizedTicker.isEmpty()) return;

        currentOptionsTicker = normalizedTicker;
        currentOptionsChain = null;
        selectedExpiration = 0;
        selectedOptionRow = null;
        optionsChainCache.clear();
        currentOptionTableRows.clear();
        optionsTickerField.setText(normalizedTicker);
        clearOptionsSummary();
        clearOptionsDetail();
        optionsContractsTableModel.setRows(Collections.emptyList());
        optionsResultsLabel.setText("Loading contracts\u2026");
        optionsStatusLabel.setText("Loading options for " + normalizedTicker + "\u2026");
        optionsStatusLabel.setForeground(theme.mutedText());

        optionsAdjustingExpiration = true;
        expirationCombo.removeAllItems();
        optionsAdjustingExpiration = false;

        new SwingWorker<OptionsChain, Void>() {
            @Override
            protected OptionsChain doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchOptions(normalizedTicker);
            }

            @Override
            protected void done() {
                try {
                    OptionsChain chain = get();
                    currentOptionsChain = chain;
                    currentOptionsTicker = chain.ticker;
                    long defaultExpiration = resolveChainExpiration(chain);
                    selectedExpiration = defaultExpiration;
                    if (defaultExpiration > 0) optionsChainCache.put(defaultExpiration, chain);
                    populateExpirationCombo(chain.expirationDates, defaultExpiration);
                    refreshOptionsView();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    optionsStatusLabel.setText("Error: " + cause.getMessage());
                    optionsStatusLabel.setForeground(theme.loss());
                    optionsResultsLabel.setText("Load failed");
                    clearOptionsSummary();
                    clearOptionsDetail();
                }
            }
        }.execute();
    }

    private void loadOptionsForExpiration(String ticker, long expiration) {
        if (ticker == null || ticker.isBlank() || expiration <= 0) return;

        OptionsChain cachedChain = optionsChainCache.get(expiration);
        if (cachedChain != null) {
            currentOptionsChain = cachedChain;
            selectedExpiration = expiration;
            refreshOptionsView();
            return;
        }

        optionsStatusLabel.setText("Loading " + formatExpirationDate(expiration) + " chain\u2026");
        optionsStatusLabel.setForeground(theme.mutedText());
        optionsResultsLabel.setText("Loading contracts\u2026");

        new SwingWorker<OptionsChain, Void>() {
            @Override
            protected OptionsChain doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchOptions(ticker, expiration);
            }

            @Override
            protected void done() {
                try {
                    OptionsChain chain = get();
                    currentOptionsChain = chain;
                    selectedExpiration = expiration;
                    optionsChainCache.put(expiration, chain);
                    populateExpirationCombo(chain.expirationDates, expiration);
                    refreshOptionsView();
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    optionsStatusLabel.setText("Error: " + cause.getMessage());
                    optionsStatusLabel.setForeground(theme.loss());
                    optionsResultsLabel.setText("Load failed");
                }
            }
        }.execute();
    }

    private void populateExpirationCombo(long[] expirationDates, long selectedValue) {
        optionsAdjustingExpiration = true;
        expirationCombo.removeAllItems();
        for (long expirationDate : expirationDates) {
            expirationCombo.addItem(formatExpirationDate(expirationDate));
        }
        if (selectedValue > 0 && expirationDates != null) {
            for (int i = 0; i < expirationDates.length; i++) {
                if (expirationDates[i] == selectedValue) {
                    expirationCombo.setSelectedIndex(i);
                    break;
                }
            }
        }
        optionsAdjustingExpiration = false;
    }

    private long resolveChainExpiration(OptionsChain chain) {
        if (chain == null) return 0L;
        if (!chain.calls.isEmpty() && chain.calls.get(0).expiration() > 0) {
            return chain.calls.get(0).expiration();
        }
        if (!chain.puts.isEmpty() && chain.puts.get(0).expiration() > 0) {
            return chain.puts.get(0).expiration();
        }
        return chain.expirationDates.length > 0 ? chain.expirationDates[0] : 0L;
    }

    private void refreshOptionsView() {
        if (currentOptionsChain == null || selectedExpiration <= 0) return;

        OptionsFilterCriteria criteria;
        try {
            criteria = readOptionsCriteria();
        } catch (IllegalArgumentException ex) {
            optionsStatusLabel.setText(ex.getMessage());
            optionsStatusLabel.setForeground(theme.loss());
            return;
        }

        long valuationEpochSeconds = System.currentTimeMillis() / 1000L;
        OptionsChainSummary summary = OptionsAnalytics.summarize(
                currentOptionsChain, selectedExpiration, valuationEpochSeconds);
        List<OptionTableRow> rows = buildOptionRows(currentOptionsChain, selectedExpiration,
                valuationEpochSeconds);
        List<OptionTableRow> filteredRows = applyOptionsFilters(rows, criteria);
        sortOptionRows(filteredRows, criteria.sortMode());

        String previousSelection = selectedOptionRow != null
                ? selectedOptionRow.contract().contractSymbol() : null;
        currentOptionTableRows.clear();
        currentOptionTableRows.addAll(filteredRows);
        optionsContractsTableModel.setRows(filteredRows);

        updateOptionsSummary(summary, currentOptionsChain);
        updateOptionsStatus(currentOptionsChain, summary, filteredRows.size());

        if (filteredRows.isEmpty()) {
            optionsResultsLabel.setText("No contracts matched the current filters.");
            optionsContractsTable.clearSelection();
            selectedOptionRow = null;
            clearOptionsDetail();
            return;
        }

        optionsResultsLabel.setText(filteredRows.size() + " contracts matched");
        restoreOptionSelection(previousSelection, filteredRows);
    }

    private OptionsFilterCriteria readOptionsCriteria() {
        int minVolume = (int) parseOptionalWholeNumber(optionsMinVolumeField, "Min volume");
        int minOpenInterest = (int) parseOptionalWholeNumber(optionsMinOpenInterestField, "Min open interest");
        double maxDistancePercent = parseOptionalDouble(optionsMaxDistanceField, "Max strike distance %");
        if (Double.isNaN(maxDistancePercent)) maxDistancePercent = Double.POSITIVE_INFINITY;
        return new OptionsFilterCriteria(
                Objects.toString(optionsTypeFilterCombo.getSelectedItem(), OPTIONS_TYPE_FILTERS[0]),
                Objects.toString(optionsMoneynessFilterCombo.getSelectedItem(), OPTIONS_MONEYNESS_FILTERS[0]),
                Math.max(minVolume, 0),
                Math.max(minOpenInterest, 0),
                maxDistancePercent,
                Objects.toString(optionsSortCombo.getSelectedItem(), OPTIONS_SORT_MODES[0])
        );
    }

    private List<OptionTableRow> buildOptionRows(OptionsChain chain, long expiration,
                                                 long valuationEpochSeconds) {
        List<OptionTableRow> rows = new ArrayList<>();
        for (OptionsContract contract : chain.calls) {
            if (contract.expiration() != expiration) continue;
            OptionAnalysis analysis = OptionsAnalytics.analyze(contract, chain.underlyingPrice,
                    valuationEpochSeconds, true);
            rows.add(new OptionTableRow(contract, true, analysis,
                    deriveOptionsMoneyness(contract, analysis)));
        }
        for (OptionsContract contract : chain.puts) {
            if (contract.expiration() != expiration) continue;
            OptionAnalysis analysis = OptionsAnalytics.analyze(contract, chain.underlyingPrice,
                    valuationEpochSeconds, false);
            rows.add(new OptionTableRow(contract, false, analysis,
                    deriveOptionsMoneyness(contract, analysis)));
        }
        return rows;
    }

    private String deriveOptionsMoneyness(OptionsContract contract, OptionAnalysis analysis) {
        if (analysis == null) return contract != null && contract.inTheMoney() ? "ITM" : "OTM";
        double distance = Math.abs(analysis.signedStrikeDistancePercent());
        if (!Double.isNaN(distance) && distance <= 2.0) return "ATM";
        return contract.inTheMoney() ? "ITM" : "OTM";
    }

    private List<OptionTableRow> applyOptionsFilters(List<OptionTableRow> rows,
                                                     OptionsFilterCriteria criteria) {
        List<OptionTableRow> filtered = new ArrayList<>();
        for (OptionTableRow row : rows) {
            if (!matchesOptionsCriteria(row, criteria)) continue;
            filtered.add(row);
        }
        return filtered;
    }

    private boolean matchesOptionsCriteria(OptionTableRow row, OptionsFilterCriteria criteria) {
        if ("Calls Only".equals(criteria.typeFilter()) && !row.call()) return false;
        if ("Puts Only".equals(criteria.typeFilter()) && row.call()) return false;

        if ("ITM".equals(criteria.moneynessFilter()) && !"ITM".equals(row.moneynessLabel())) return false;
        if ("ATM +/-2%".equals(criteria.moneynessFilter()) && !"ATM".equals(row.moneynessLabel())) return false;
        if ("OTM".equals(criteria.moneynessFilter()) && !"OTM".equals(row.moneynessLabel())) return false;

        if (row.contract().volume() < criteria.minVolume()) return false;
        if (row.contract().openInterest() < criteria.minOpenInterest()) return false;

        double absDistance = Math.abs(row.analysis().signedStrikeDistancePercent());
        return Double.isNaN(absDistance) || absDistance <= criteria.maxDistancePercent();
    }

    private void sortOptionRows(List<OptionTableRow> rows, String sortMode) {
        Comparator<OptionTableRow> comparator = switch (sortMode) {
            case "Volume" -> Comparator
                    .comparingInt((OptionTableRow row) -> row.contract().volume()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (OptionTableRow row) -> row.contract().openInterest()).reversed());
            case "IV %" -> Comparator
                    .comparingDouble((OptionTableRow row) -> row.contract().impliedVolatility()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (OptionTableRow row) -> row.contract().openInterest()).reversed());
            case "Nearest ATM" -> Comparator
                    .comparingDouble((OptionTableRow row) -> Math.abs(row.analysis().signedStrikeDistancePercent()))
                    .thenComparing(Comparator.comparingInt((OptionTableRow row) -> row.contract().openInterest()).reversed());
            case "Tightest Spread" -> Comparator
                    .comparingDouble((OptionTableRow row) -> sanitizeSortValue(row.analysis().spreadPercent()))
                    .thenComparing(Comparator.comparingInt((OptionTableRow row) -> row.contract().openInterest()).reversed());
            case "Open Interest" -> Comparator
                    .comparingInt((OptionTableRow row) -> row.contract().openInterest()).reversed()
                    .thenComparing(Comparator.comparingInt(
                            (OptionTableRow row) -> row.contract().volume()).reversed());
            default -> Comparator.comparingDouble(
                    (OptionTableRow row) -> sanitizeSortValue(Math.abs(row.analysis().signedStrikeDistancePercent())));
        };
        rows.sort(comparator.thenComparingDouble(row -> row.contract().strike()));
    }

    private double sanitizeSortValue(double value) {
        return Double.isNaN(value) ? Double.POSITIVE_INFINITY : value;
    }

    private void updateOptionsSummary(OptionsChainSummary summary, OptionsChain chain) {
        optionsUnderlyingValueLabel.setText(chain.ticker + " " + formatOptionsDollar(chain.underlyingPrice));
        optionsDaysToExpiryValueLabel.setText(formatOptionsDecimal(summary.daysToExpiration(), "%.1f") + " days");

        if (summary.atmStrike() > 0) {
            String straddleText = summary.atmStraddleMidpoint() > 0
                    ? " | Straddle " + formatOptionsDollar(summary.atmStraddleMidpoint())
                    : "";
            optionsAtmValueLabel.setText(formatOptionsDollar(summary.atmStrike()) + straddleText);
        } else {
            optionsAtmValueLabel.setText("N/A");
        }

        if (Double.isNaN(summary.impliedMovePercent())) {
            optionsImpliedMoveValueLabel.setText("N/A");
        } else {
            optionsImpliedMoveValueLabel.setText("+/- " + formatOptionsDollar(summary.impliedMoveAmount())
                    + " (" + formatOptionsDecimal(summary.impliedMovePercent(), "%.2f") + "%)");
        }

        double volumeRatio = summary.totalCallVolume() > 0
                ? (double) summary.totalPutVolume() / summary.totalCallVolume() : Double.NaN;
        double oiRatio = summary.totalCallOpenInterest() > 0
                ? (double) summary.totalPutOpenInterest() / summary.totalCallOpenInterest() : Double.NaN;
        optionsPutCallRatioValueLabel.setText("Vol " + formatOptionsRatio(volumeRatio)
                + " | OI " + formatOptionsRatio(oiRatio));
        optionsMaxPainValueLabel.setText(Double.isNaN(summary.maxPainStrike())
                ? "N/A" : formatOptionsDollar(summary.maxPainStrike()));

        double ivRank = computeIvRank(chain);
        if (Double.isNaN(ivRank)) {
            optionsIvRankValueLabel.setText("N/A");
            optionsIvRankValueLabel.setForeground(theme.primaryText());
        } else {
            optionsIvRankValueLabel.setText(buildIvRankBar(ivRank));
            optionsIvRankValueLabel.setFont(MONOSPACE_FONT.deriveFont(Font.BOLD));
            optionsIvRankValueLabel.setForeground(ivRank < 30 ? theme.gain() : ivRank < 70 ? new Color(255, 200, 50) : theme.loss());
        }
    }

    private void updateOptionsStatus(OptionsChain chain, OptionsChainSummary summary, int matchCount) {
        String expirationText = formatExpirationDate(selectedExpiration);
        optionsStatusLabel.setText(chain.ticker + " | " + expirationText + " | "
                + matchCount + " matches | " + summary.callCount() + " calls / "
                + summary.putCount() + " puts");
        optionsStatusLabel.setForeground(theme.primaryText());
    }

    private void restoreOptionSelection(String previousSelection, List<OptionTableRow> filteredRows) {
        int targetIndex = 0;
        if (previousSelection != null) {
            for (int i = 0; i < filteredRows.size(); i++) {
                if (previousSelection.equals(filteredRows.get(i).contract().contractSymbol())) {
                    targetIndex = i;
                    break;
                }
            }
        }
        optionsContractsTable.getSelectionModel().setSelectionInterval(targetIndex, targetIndex);
        optionsContractsTable.scrollRectToVisible(optionsContractsTable.getCellRect(targetIndex, 0, true));
    }

    private void updateOptionsDetail(OptionTableRow row) {
        if (row == null) {
            clearOptionsDetail();
            return;
        }

        OptionsContract contract = row.contract();
        OptionAnalysis analysis = row.analysis();
        String typeLabel = row.call() ? "CALL" : "PUT";
        String expirationText = formatExpirationDate(contract.expiration());

        optionsDetailTitleLabel.setText(contract.contractSymbol());
        optionsDetailTitleLabel.setForeground(row.call() ? theme.gain() : theme.loss());
        optionsDetailSubtitleLabel.setText(typeLabel + " | Strike " + formatOptionsDollar(contract.strike())
                + " | " + expirationText + " | " + row.moneynessLabel());

        optionsDetailPremiumLabel.setText("Mid " + formatOptionsDollar(analysis.midpoint())
                + " | Last " + formatOptionsDollar(contract.lastPrice())
                + " | " + formatOptionsDollar(contract.bid()) + " x " + formatOptionsDollar(contract.ask()));
        optionsDetailSpreadLabel.setText(formatOptionsDollar(analysis.spread()) + " ("
                + formatOptionsPercent(analysis.spreadPercent()) + ")");
        optionsDetailBreakEvenLabel.setText(formatOptionsDollar(analysis.breakEven()));
        optionsDetailIntrinsicLabel.setText(formatOptionsDollar(analysis.intrinsicValue()));
        optionsDetailExtrinsicLabel.setText(formatOptionsDollar(analysis.extrinsicValue()));
        optionsDetailDistanceLabel.setText(formatSignedOptionsPercent(analysis.signedStrikeDistancePercent())
                + " vs spot");
        optionsDetailLiquidityLabel.setText("Vol " + formatVolume(contract.volume())
                + " | OI " + formatVolume(contract.openInterest()));
        optionsDetailDeltaProbLabel.setText("Delta " + formatSignedOptionsDecimal(analysis.delta(), "%.2f")
                + " | " + formatOptionsPercent(analysis.probabilityInTheMoney() * 100.0) + " ITM");
        optionsDetailGammaVegaLabel.setText("Gamma "
                + formatOptionsDecimal(analysis.gamma(), "%.4f")
                + " | Vega " + formatOptionsDecimal(analysis.vegaPerVolPoint(), "%.3f"));
        optionsDetailThetaLabel.setText(formatSignedOptionsDecimal(analysis.thetaPerDay(), "%.3f"));
        optionsDetailCapitalLabel.setText(formatOptionsDollar(analysis.midpoint() * 100.0) + " per contract");
        optionsDetailNotesArea.setText(buildOptionsTradeRead(row));
        optionsDetailNotesArea.setCaretPosition(0);
        if (optionsPayoffPanel != null && currentOptionsChain != null)
            optionsPayoffPanel.setContract(row, currentOptionsChain.underlyingPrice);
    }

    private String buildOptionsTradeRead(OptionTableRow row) {
        List<String> notes = new ArrayList<>();
        OptionAnalysis analysis = row.analysis();
        OptionsContract contract = row.contract();

        if ("ATM".equals(row.moneynessLabel())) {
            notes.add("Near-the-money contract. Sensitivity to both directional moves and volatility is typically highest here.");
        } else if ("ITM".equals(row.moneynessLabel())) {
            notes.add("In-the-money premium is carrying intrinsic value. Expect the option to behave more like stock and less like a pure volatility bet.");
        } else {
            notes.add("Out-of-the-money premium is entirely extrinsic. This position needs price movement before expiration, not just time.");
        }

        if (!Double.isNaN(analysis.spreadPercent())) {
            if (analysis.spreadPercent() >= 20.0) {
                notes.add("Spread is wide. Treat the posted market as a starting point and use limit orders.");
            } else if (analysis.spreadPercent() <= 8.0) {
                notes.add("Spread is relatively tight for a retail fill. Execution quality should be less punitive.");
            }
        }

        if (contract.volume() < 100 && contract.openInterest() < 250) {
            notes.add("Liquidity is thin. Entries and exits may move around more than the model implies.");
        } else if (contract.openInterest() >= 1000) {
            notes.add("Open interest is healthy enough to suggest a real market rather than a ghost strike.");
        }

        if (analysis.daysToExpiration() <= 7.0) {
            notes.add("Short-dated contract. Theta will accelerate quickly, so timing matters more than usual.");
        } else if (analysis.daysToExpiration() >= 45.0) {
            notes.add("Longer-dated contract. Vega becomes more meaningful and daily theta drag is less violent.");
        }

        if (contract.impliedVolatility() >= 0.60) {
            notes.add("Implied volatility is elevated. Premium is rich, so you need a stronger realized move to justify paying up.");
        } else if (contract.impliedVolatility() > 0 && contract.impliedVolatility() <= 0.25) {
            notes.add("Implied volatility is comparatively contained. Premium is less inflated, but explosive upside from vol expansion is also lower.");
        }

        return String.join("\n\n", notes);
    }

    private void clearOptionsSummary() {
        if (optionsUnderlyingValueLabel == null) return;
        optionsUnderlyingValueLabel.setText("\u2014");
        optionsDaysToExpiryValueLabel.setText("\u2014");
        optionsAtmValueLabel.setText("\u2014");
        optionsImpliedMoveValueLabel.setText("\u2014");
        optionsPutCallRatioValueLabel.setText("\u2014");
        optionsMaxPainValueLabel.setText("\u2014");
        if (optionsIvRankValueLabel != null) optionsIvRankValueLabel.setText("\u2014");
    }

    private double computeIvRank(OptionsChain chain) {
        double minIv = Double.MAX_VALUE, maxIv = -Double.MAX_VALUE;
        for (OptionsContract c : chain.calls) {
            if (c.impliedVolatility() > 0) { minIv = Math.min(minIv, c.impliedVolatility()); maxIv = Math.max(maxIv, c.impliedVolatility()); }
        }
        for (OptionsContract c : chain.puts) {
            if (c.impliedVolatility() > 0) { minIv = Math.min(minIv, c.impliedVolatility()); maxIv = Math.max(maxIv, c.impliedVolatility()); }
        }
        if (maxIv <= minIv || minIv == Double.MAX_VALUE) return Double.NaN;
        double atmIv = findAtmIv(chain);
        if (Double.isNaN(atmIv)) return Double.NaN;
        return (atmIv - minIv) / (maxIv - minIv) * 100.0;
    }

    private double findAtmIv(OptionsChain chain) {
        double spot = chain.underlyingPrice;
        if (spot <= 0) return Double.NaN;
        OptionsContract best = null;
        double bestDist = Double.MAX_VALUE;
        for (OptionsContract c : chain.calls) {
            double d = Math.abs(c.strike() - spot);
            if (d < bestDist && c.impliedVolatility() > 0) { bestDist = d; best = c; }
        }
        return best == null ? Double.NaN : best.impliedVolatility();
    }

    private String buildIvRankBar(double rank) {
        int filled = (int) Math.round(rank / 10.0);
        filled = Math.max(0, Math.min(10, filled));
        return "#".repeat(filled) + "-".repeat(10 - filled) + " " + String.format("%.0f", rank) + "%";
    }

    private void clearOptionsDetail() {
        if (optionsDetailTitleLabel == null) return;
        optionsDetailTitleLabel.setText("Select a contract");
        optionsDetailTitleLabel.setForeground(theme.primaryText());
        optionsDetailSubtitleLabel.setText(
                "Load a chain and click a row to inspect its structure.");
        optionsDetailPremiumLabel.setText("\u2014");
        optionsDetailSpreadLabel.setText("\u2014");
        optionsDetailBreakEvenLabel.setText("\u2014");
        optionsDetailIntrinsicLabel.setText("\u2014");
        optionsDetailExtrinsicLabel.setText("\u2014");
        optionsDetailDistanceLabel.setText("\u2014");
        optionsDetailLiquidityLabel.setText("\u2014");
        optionsDetailDeltaProbLabel.setText("\u2014");
        optionsDetailGammaVegaLabel.setText("\u2014");
        optionsDetailThetaLabel.setText("\u2014");
        optionsDetailCapitalLabel.setText("\u2014");
        if (optionsDetailNotesArea != null) {
            optionsDetailNotesArea.setText(
                    "Premium, spread quality, break-even, and model sensitivities will appear here after you select a contract.");
            optionsDetailNotesArea.setCaretPosition(0);
        }
        if (optionsPayoffPanel != null) optionsPayoffPanel.clear();
    }

    private void resetOptionsFilters() {
        optionsTypeFilterCombo.setSelectedIndex(0);
        optionsMoneynessFilterCombo.setSelectedIndex(0);
        optionsSortCombo.setSelectedIndex(0);
        optionsMinVolumeField.setText("");
        optionsMinOpenInterestField.setText("");
        optionsMaxDistanceField.setText("");
        refreshOptionsView();
    }

    private void configureOptionsTableRenderers() {
        DefaultTableCellRenderer rightAlignedPriceRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue())) setText("N/A");
                else setText(String.format("$%.2f", n.doubleValue()));
                setForeground(theme.primaryText());
            }
        };
        rightAlignedPriceRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer volumeRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || n.longValue() <= 0) setText("N/A");
                else setText(formatVolume(n.longValue()));
                setForeground(theme.primaryText());
            }
        };
        volumeRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer percentRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue())) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    setText(String.format("%.1f%%", n.doubleValue()));
                    setForeground(theme.primaryText());
                }
            }
        };
        percentRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer signedPercentRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue())) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    double pct = n.doubleValue();
                    setText(String.format("%+.1f%%", pct));
                    setForeground(pct >= 0 ? theme.loss() : theme.gain());
                }
            }
        };
        signedPercentRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer decimalRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                if (!(value instanceof Number n) || Double.isNaN(n.doubleValue())) {
                    setText("N/A");
                    setForeground(theme.mutedText());
                } else {
                    setText(String.format("%.2f", n.doubleValue()));
                    setForeground(theme.primaryText());
                }
            }
        };
        decimalRenderer.setHorizontalAlignment(SwingConstants.RIGHT);

        DefaultTableCellRenderer typeRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                String text = Objects.toString(value, "");
                setText(text);
                setForeground("CALL".equals(text) ? theme.gain() : theme.loss());
            }
        };
        typeRenderer.setHorizontalAlignment(SwingConstants.CENTER);

        DefaultTableCellRenderer moneynessRenderer = new DefaultTableCellRenderer() {
            @Override protected void setValue(Object value) {
                String text = Objects.toString(value, "");
                setText(text);
                if ("ITM".equals(text)) setForeground(theme.gain());
                else if ("OTM".equals(text)) setForeground(theme.loss());
                else setForeground(theme.accent());
            }
        };
        moneynessRenderer.setHorizontalAlignment(SwingConstants.CENTER);

        TableColumnModel columns = optionsContractsTable.getColumnModel();
        columns.getColumn(0).setPreferredWidth(70);
        columns.getColumn(1).setPreferredWidth(170);
        columns.getColumn(2).setPreferredWidth(85);
        columns.getColumn(3).setPreferredWidth(85);
        columns.getColumn(4).setPreferredWidth(85);
        columns.getColumn(5).setPreferredWidth(85);
        columns.getColumn(6).setPreferredWidth(80);
        columns.getColumn(7).setPreferredWidth(80);
        columns.getColumn(8).setPreferredWidth(80);
        columns.getColumn(9).setPreferredWidth(100);
        columns.getColumn(10).setPreferredWidth(85);
        columns.getColumn(11).setPreferredWidth(95);
        columns.getColumn(12).setPreferredWidth(75);
        columns.getColumn(13).setPreferredWidth(95);

        columns.getColumn(0).setCellRenderer(typeRenderer);
        columns.getColumn(2).setCellRenderer(rightAlignedPriceRenderer);
        columns.getColumn(3).setCellRenderer(rightAlignedPriceRenderer);
        columns.getColumn(4).setCellRenderer(rightAlignedPriceRenderer);
        columns.getColumn(5).setCellRenderer(percentRenderer);
        columns.getColumn(6).setCellRenderer(percentRenderer);
        columns.getColumn(7).setCellRenderer(volumeRenderer);
        columns.getColumn(8).setCellRenderer(volumeRenderer);
        columns.getColumn(9).setCellRenderer(moneynessRenderer);
        columns.getColumn(10).setCellRenderer(signedPercentRenderer);
        columns.getColumn(11).setCellRenderer(rightAlignedPriceRenderer);
        columns.getColumn(12).setCellRenderer(decimalRenderer);
        columns.getColumn(13).setCellRenderer(percentRenderer);
    }

    private String formatExpirationDate(long expiration) {
        if (expiration <= 0) return "N/A";
        return new SimpleDateFormat("MMM dd, yyyy").format(new Date(expiration * 1000L));
    }

    private double parseOptionalWholeNumber(JTextField field, String label) {
        String text = field.getText().trim();
        if (text.isEmpty()) return 0.0;
        try {
            return Double.parseDouble(text.replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be numeric.");
        }
    }

    private String formatOptionsDollar(double value) {
        return Double.isNaN(value) ? "N/A" : String.format("$%.2f", value);
    }

    private String formatOptionsPercent(double value) {
        return Double.isNaN(value) ? "N/A" : String.format("%.1f%%", value);
    }

    private String formatSignedOptionsPercent(double value) {
        return Double.isNaN(value) ? "N/A" : String.format("%+.1f%%", value);
    }

    private String formatOptionsDecimal(double value, String fmt) {
        return Double.isNaN(value) ? "N/A" : String.format(fmt, value);
    }

    private String formatSignedOptionsDecimal(double value, String fmt) {
        return Double.isNaN(value) ? "N/A" : String.format("%+" + fmt.substring(1), value);
    }

    private String formatOptionsRatio(double value) {
        return Double.isNaN(value) ? "N/A" : String.format("%.2fx", value);
    }



    // =========================================================================
    // Chart data helpers
    // =========================================================================

    /**
     * Returns a new {@link ChartData} containing only the last {@code maxBars}
     * data points from {@code data}.  Used to carve short intraday windows
     * (e.g. "15M") out of a full-day 1-minute response.
     *
     * <p>If {@code data} already has fewer than {@code maxBars} bars, it is
     * returned unchanged.
     */
    private static ChartData trimChartData(ChartData data, int maxBars) {
        int total = data.timestamps.length;
        if (total <= maxBars) return data;
        int start = total - maxBars;
        long[]   ts  = new long[maxBars];
        double[] px  = new double[maxBars];
        long[]   vol = new long[maxBars];
        System.arraycopy(data.timestamps, start, ts,  0, maxBars);
        System.arraycopy(data.prices,     start, px,  0, maxBars);
        System.arraycopy(data.volumes,    start, vol, 0, maxBars);
        return new ChartData(ts, px, vol);
    }

    // =========================================================================
    // Formatting helpers
    // =========================================================================

    /**
     * Formats a decimal number using {@code fmt}, or returns "N/A" when the
     * value is NaN or zero (both indicate unavailable data).
     */
    private String formatDecimal(double value, String fmt) {
        return (Double.isNaN(value) || value == 0) ? "N/A" : String.format(fmt, value);
    }

    /**
     * Formats a price as "{@code CURRENCY value}", e.g. "USD 173.50".
     * Returns "N/A" for a zero price.
     */
    private String formatPrice(double price, String currency) {
        return price == 0 ? "N/A" : String.format("%s %.2f", currency, price);
    }

    /**
     * Formats a large dollar amount with T/B/M suffix (e.g. "$2.94T", "$184.23B").
     * Falls back to standard currency formatting for amounts below $1 million.
     */
    private String formatLargeNumber(double amount) {
        if (amount == 0) return "N/A";
        if (amount >= 1_000_000_000_000.0) return String.format("$%.2fT", amount / 1_000_000_000_000.0);
        if (amount >= 1_000_000_000.0)     return String.format("$%.2fB", amount / 1_000_000_000.0);
        if (amount >= 1_000_000.0)         return String.format("$%.2fM", amount / 1_000_000.0);
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    /**
     * Formats a share count with K/M suffix (e.g. "54.23M", "487.6K").
     * Returns "N/A" for zero volume.
     */
    private String formatVolume(long shares) {
        if (shares == 0) return "N/A";
        if (shares >= 1_000_000) return String.format("%.2fM", shares / 1_000_000.0);
        if (shares >= 1_000)     return String.format("%.1fK", shares / 1_000.0);
        return String.valueOf(shares);
    }

    /** Updates the status bar with the given message and colour. */
    private void showStatus(String message, Color color) {
        statusLabel.setText(message);
        statusLabel.setForeground(color);
    }

    private void showStatusLoading(String message) {
        statusLabel.setText(message);
        statusLabel.setForeground(theme.mutedText());
        statusSpinner.startSpinning();
    }

    private void hideStatusLoading(String message) {
        statusLabel.setText(message);
        statusLabel.setForeground(theme.mutedText());
        statusSpinner.stopSpinning();
    }

    // =========================================================================
    // Options table model
    // =========================================================================

    private static class OptionsContractsTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Type", "Symbol", "Strike", "Mid", "Last", "Spread %",
                "IV %", "Vol", "OI", "Moneyness", "Dist %", "Break-even",
                "Delta", "Prob ITM %"
        };

        private final List<OptionTableRow> rows = new ArrayList<>();

        void setRows(List<OptionTableRow> newRows) {
            rows.clear();
            if (newRows != null) rows.addAll(newRows);
            fireTableDataChanged();
        }

        OptionTableRow getRow(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 0, 1, 9 -> String.class;
                case 7, 8 -> Integer.class;
                default -> Double.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            OptionTableRow row = rows.get(rowIndex);
            OptionsContract contract = row.contract();
            OptionAnalysis analysis = row.analysis();
            return switch (columnIndex) {
                case 0 -> row.call() ? "CALL" : "PUT";
                case 1 -> contract.contractSymbol();
                case 2 -> contract.strike();
                case 3 -> analysis.midpoint();
                case 4 -> contract.lastPrice();
                case 5 -> analysis.spreadPercent();
                case 6 -> contract.impliedVolatility() * 100.0;
                case 7 -> contract.volume();
                case 8 -> contract.openInterest();
                case 9 -> row.moneynessLabel();
                case 10 -> analysis.signedStrikeDistancePercent();
                case 11 -> analysis.breakEven();
                case 12 -> analysis.delta();
                case 13 -> analysis.probabilityInTheMoney() * 100.0;
                default -> "";
            };
        }
    }

    // =========================================================================
    // Screener table model
    // =========================================================================

    private static class ScreenerTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {
                "Symbol", "Name", "Exchange", "Price", "Change %",
                "Market Cap", "Volume", "P/E", "Beta", "Div Yield"
        };

        private final List<ScreenerStock> rows = new ArrayList<>();

        void setRows(List<ScreenerStock> newRows) {
            rows.clear();
            if (newRows != null) rows.addAll(newRows);
            fireTableDataChanged();
        }

        ScreenerStock getRow(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return COLUMNS.length; }
        @Override public String getColumnName(int column) { return COLUMNS[column]; }

        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return switch (columnIndex) {
                case 3, 4, 5, 7, 8, 9 -> Double.class;
                case 6 -> Long.class;
                default -> String.class;
            };
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ScreenerStock row = rows.get(rowIndex);
            return switch (columnIndex) {
                case 0 -> row.symbol();
                case 1 -> row.name();
                case 2 -> row.exchange();
                case 3 -> row.price();
                case 4 -> row.changePercent();
                case 5 -> row.marketCap();
                case 6 -> row.volume();
                case 7 -> row.peRatio();
                case 8 -> row.beta();
                case 9 -> row.dividendYield();
                default -> "";
            };
        }
    }

    // =========================================================================
    // StockChartPanel — inner class
    // =========================================================================

    /**
     * Custom Swing panel that renders an interactive stock price chart.
     *
     * <h3>Layout (top to bottom)</h3>
     * <pre>
     *   ┌────────────────────────────────────┐
     *   │  Price area  (gradient fill + line)│  ← priceAreaHeight
     *   │  [MA20 / MA50 overlaid if enabled] │
     *   ├────────────────────────────────────┤  ← gap
     *   │  Volume bars                       │  ← volumeSectionHeight
     *   ├────────────────────────────────────┤  ← rsiTopGap (if RSI on)
     *   │  RSI sub-panel (if enabled)        │  ← rsiPanelHeight (if RSI on)
     *   ├────────────────────────────────────┤
     *   │  X-axis date labels                │  ← bottomPadding
     *   └────────────────────────────────────┘
     *   Left padding = leftPadding (for Y-axis labels)
     * </pre>
     *
     * <h3>Comparison mode</h3>
     * When {@link #comparisonChartData} is set, both series are normalised to
     * percentage change from their respective first data points so they can be
     * plotted on the same scale regardless of absolute price differences.
     * Y-axis labels switch to "%" format.  MA overlays are disabled in
     * comparison mode because they are in raw price space.
     *
     * <h3>Hover / crosshair</h3>
     * Mouse movement updates {@link #hoveredDataIndex}.  The nearest data point
     * is highlighted with a dot on the price line, a vertical dashed line, and
     * a tooltip box showing price (or %) , date, and volume.
     */

    // =========================================================================
    // Multi-Stock Comparison panel
    // =========================================================================

    private JTable comparisonTable;
    private DefaultTableModel comparisonTableModel;
    private JTextField[] compTickerFields;

    private JPanel buildComparisonPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 10));
        panel.setBackground(theme.background());
        panel.setBorder(new EmptyBorder(PAGE_PADDING_TOP, 0, CARD_GAP, 0));

        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(theme.background());
        header.setBorder(new EmptyBorder(0, 0, HEADER_BOTTOM_GAP, 0));
        header.add(makeLabel("Multi-Stock Comparison", HEADING_FONT, theme.primaryText()), BorderLayout.WEST);
        panel.add(header, BorderLayout.NORTH);

        // Ticker input row
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        inputRow.setBackground(theme.background());
        compTickerFields = new JTextField[5];
        for (int i = 0; i < 5; i++) {
            compTickerFields[i] = new JTextField(6);
            compTickerFields[i].setFont(MONOSPACE_FONT);
            compTickerFields[i].setBackground(theme.btnBg());
            compTickerFields[i].setForeground(theme.primaryText());
            compTickerFields[i].setCaretColor(theme.accent());
            compTickerFields[i].setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(theme.border(), 1, true),
                    new EmptyBorder(6, 8, 6, 8)));
            inputRow.add(compTickerFields[i]);
        }
        JButton compareBtn = makeActionButton("Compare");
        compareBtn.addActionListener(e -> runComparison());
        inputRow.add(compareBtn);

        // Table
        comparisonTableModel = new DefaultTableModel();
        comparisonTable = new JTable(comparisonTableModel);
        comparisonTable.setFont(BODY_FONT);
        comparisonTable.setRowHeight(26);
        comparisonTable.setBackground(theme.background());
        comparisonTable.setForeground(theme.primaryText());
        comparisonTable.setGridColor(theme.border());
        comparisonTable.getTableHeader().setBackground(theme.card());
        comparisonTable.getTableHeader().setForeground(theme.accent());
        applyTableStyling(comparisonTable);

        JScrollPane sp = new JScrollPane(comparisonTable);
        sp.setBorder(BorderFactory.createLineBorder(theme.border(), 1, true));
        sp.getViewport().setBackground(theme.background());
        sp.getVerticalScrollBar().setUnitIncrement(16);

        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setBackground(theme.background());
        center.add(inputRow, BorderLayout.NORTH);
        center.add(sp, BorderLayout.CENTER);
        panel.add(center, BorderLayout.CENTER);
        return panel;
    }

    private void runComparison() {
        List<String> tickers = new ArrayList<>();
        for (JTextField f : compTickerFields) {
            String t = f.getText().trim().toUpperCase();
            if (!t.isEmpty()) tickers.add(t);
        }
        if (tickers.size() < 2) return;
        showStatusLoading("Comparing " + tickers.size() + " stocks...");

        new SwingWorker<Map<String, StockData>, Void>() {
            @Override protected Map<String, StockData> doInBackground() {
                Map<String, StockData> results = new LinkedHashMap<>();
                List<java.util.concurrent.Future<StockData>> futures = new ArrayList<>();
                for (String t : tickers) {
                    futures.add(FETCH_POOL.submit(() -> {
                        try { return YahooFinanceFetcher.fetch(t); }
                        catch (Exception e) { return null; }
                    }));
                }
                for (int i = 0; i < tickers.size(); i++) {
                    try { results.put(tickers.get(i), futures.get(i).get()); }
                    catch (Exception e) { results.put(tickers.get(i), null); }
                }
                return results;
            }
            @Override protected void done() {
                try {
                    Map<String, StockData> data = get();
                    populateComparisonTable(data);
                    hideStatusLoading("Comparison complete");
                } catch (Exception e) {
                    statusSpinner.stopSpinning();
                    showStatus("Comparison failed", theme.loss());
                }
            }
        }.execute();
    }

    private void populateComparisonTable(Map<String, StockData> data) {
        String[] metrics = {"Price", "Change %", "P/E", "Forward P/E", "EPS",
                "Beta", "Div Yield %", "52W High", "52W Low", "50-day MA", "200-day MA", "Market Cap"};
        List<String> tickers = new ArrayList<>(data.keySet());
        String[] columns = new String[tickers.size() + 1];
        columns[0] = "Metric";
        for (int i = 0; i < tickers.size(); i++) columns[i + 1] = tickers.get(i);

        Object[][] tableData = new Object[metrics.length][columns.length];
        for (int r = 0; r < metrics.length; r++) {
            tableData[r][0] = metrics[r];
            for (int c = 0; c < tickers.size(); c++) {
                StockData sd = data.get(tickers.get(c));
                if (sd == null) { tableData[r][c + 1] = "N/A"; continue; }
                tableData[r][c + 1] = switch (r) {
                    case 0  -> String.format("$%.2f", sd.currentPrice);
                    case 1  -> String.format("%+.2f%%", sd.priceChangePercent);
                    case 2  -> formatDecimal(sd.peRatio, "%.2f");
                    case 3  -> formatDecimal(sd.forwardPE, "%.2f");
                    case 4  -> formatDecimal(sd.earningsPerShare, "%.2f");
                    case 5  -> formatDecimal(sd.beta, "%.2f");
                    case 6  -> formatDecimal(sd.dividendYield * 100, "%.2f%%");
                    case 7  -> String.format("$%.2f", sd.fiftyTwoWeekHigh);
                    case 8  -> String.format("$%.2f", sd.fiftyTwoWeekLow);
                    case 9  -> formatDecimal(sd.fiftyDayMovingAverage, "$%.2f");
                    case 10 -> formatDecimal(sd.twoHundredDayMovingAverage, "$%.2f");
                    case 11 -> formatLargeNumber(sd.marketCap);
                    default -> "N/A";
                };
            }
        }
        comparisonTableModel.setDataVector(tableData, columns);
    }

    // =========================================================================
    // Options Payoff Diagram
    // =========================================================================

    private class OptionsPayoffPanel extends JPanel {
        private double strike, premium, underlyingPrice, breakEven, delta;
        private boolean isCall, hasData;

        void setContract(OptionTableRow row, double underlyingPrice) {
            OptionsContract c = row.contract();
            OptionAnalysis a = row.analysis();
            this.strike = c.strike();
            this.premium = a.midpoint();
            this.underlyingPrice = underlyingPrice;
            this.breakEven = a.breakEven();
            this.delta = a.delta();
            this.isCall = row.call();
            this.hasData = true;
            repaint();
        }

        void clear() {
            hasData = false;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(theme.card());
            g2.fillRect(0, 0, w, h);

            if (!hasData) {
                g2.setColor(theme.mutedText());
                g2.setFont(CAPTION_FONT);
                String msg = "Select a contract";
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg, (w - fm.stringWidth(msg)) / 2, h / 2);
                g2.dispose();
                return;
            }

            int lp = 56, rp = 14, tp = 16, bp = 28;
            int chartW = w - lp - rp;
            int chartH = h - tp - bp;

            // X range: ±30% around underlying, widened to include strike
            double xMin = Math.min(underlyingPrice * 0.70, strike * 0.95);
            double xMax = Math.max(underlyingPrice * 1.30, strike * 1.05);

            // Compute payoff at edges
            double yAtXMin = isCall ? -premium : (strike - xMin - premium);
            double yAtXMax = isCall ? (xMax - strike - premium) : -premium;

            // Y range
            double yMin = Math.min(-premium * 1.15, Math.min(yAtXMin, yAtXMax) * 1.15);
            double yMax = Math.max(0, Math.max(yAtXMin, yAtXMax)) * 1.15;
            if (yMax <= 0) yMax = premium * 0.5; // ensure some space above zero
            if (yMin >= 0) yMin = -premium * 1.15;
            double yRange = yMax - yMin;
            if (yRange == 0) yRange = 1;

            // Helper lambdas as local variables
            // Map data coords to pixel coords
            // xToPixel: px = lp + (x - xMin) / (xMax - xMin) * chartW
            // yToPixel: py = tp + (yMax - y) / yRange * chartH

            // --- Grid lines ---
            // Zero line
            int yZeroPx = tp + (int)((yMax - 0.0) / yRange * chartH);
            g2.setColor(new Color(theme.mutedText().getRed(), theme.mutedText().getGreen(),
                    theme.mutedText().getBlue(), 80));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(lp, yZeroPx, lp + chartW, yZeroPx);

            // Max loss line (y = -premium)
            int yMaxLossPx = tp + (int)((yMax - (-premium)) / yRange * chartH);
            g2.setColor(new Color(theme.border().getRed(), theme.border().getGreen(),
                    theme.border().getBlue(), 60));
            g2.drawLine(lp, yMaxLossPx, lp + chartW, yMaxLossPx);

            // --- Build payoff key points ---
            // Clip helper: we collect (x,y) pairs for the payoff line
            double[] pxs, pys;
            if (isCall) {
                // Flat at -premium from xMin to strike, then linear up
                double yRight = xMax - strike - premium;
                pxs = new double[]{xMin, strike, breakEven, xMax};
                pys = new double[]{-premium, -premium, 0, yRight};
            } else {
                // Linear down from xMin, flat at -premium from strike to xMax
                double yLeft = strike - xMin - premium;
                pxs = new double[]{xMin, breakEven, strike, xMax};
                pys = new double[]{yLeft, 0, -premium, -premium};
            }

            // Convert to pixel arrays
            int n = pxs.length;
            int[] pxPix = new int[n];
            int[] pyPix = new int[n];
            for (int i = 0; i < n; i++) {
                pxPix[i] = lp + (int)((pxs[i] - xMin) / (xMax - xMin) * chartW);
                pyPix[i] = tp + (int)((yMax - Math.max(yMin, Math.min(yMax, pys[i]))) / yRange * chartH);
            }

            // --- Fill loss region (below zero) ---
            // Build polygon: payoff points clipped at zero from below
            Color lossColor = theme.loss();
            Color gainColor = theme.gain();

            Path2D lossRegion = new Path2D.Double();
            Path2D gainRegion = new Path2D.Double();

            // Walk through segments, splitting at zero crossings
            buildFillRegions(pxs, pys, xMin, xMax, yMin, yMax, yZeroPx, lp, tp, chartW, chartH,
                    lossRegion, gainRegion);

            g2.setColor(new Color(lossColor.getRed(), lossColor.getGreen(), lossColor.getBlue(), 60));
            g2.fill(lossRegion);
            g2.setColor(new Color(gainColor.getRed(), gainColor.getGreen(), gainColor.getBlue(), 60));
            g2.fill(gainRegion);

            // --- Draw payoff line ---
            g2.setColor(theme.primaryText());
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            Path2D payoffLine = new Path2D.Double();
            payoffLine.moveTo(pxPix[0], pyPix[0]);
            for (int i = 1; i < n; i++) payoffLine.lineTo(pxPix[i], pyPix[i]);
            g2.draw(payoffLine);

            // --- Vertical dashed line: current price ---
            int xCurPx = lp + (int)((underlyingPrice - xMin) / (xMax - xMin) * chartW);
            g2.setColor(theme.accent());
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                    10f, new float[]{4f, 4f}, 0f));
            g2.drawLine(xCurPx, tp, xCurPx, tp + chartH);
            g2.setFont(CAPTION_FONT);
            FontMetrics fm = g2.getFontMetrics();
            String curLabel = String.format("$%.2f", underlyingPrice);
            int curLabelX = Math.max(lp, Math.min(lp + chartW - fm.stringWidth(curLabel), xCurPx - fm.stringWidth(curLabel) / 2));
            g2.drawString(curLabel, curLabelX, tp - 2);

            // --- Vertical dashed line: break-even ---
            if (breakEven > xMin && breakEven < xMax) {
                int xBePx = lp + (int)((breakEven - xMin) / (xMax - xMin) * chartW);
                g2.setColor(theme.mutedText());
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                        10f, new float[]{3f, 3f}, 0f));
                g2.drawLine(xBePx, tp, xBePx, tp + chartH);
                String beLabel = "B/E";
                int beLabelX = Math.max(lp, Math.min(lp + chartW - fm.stringWidth(beLabel), xBePx - fm.stringWidth(beLabel) / 2));
                g2.drawString(beLabel, beLabelX, tp + 10);
            }

            // --- X-axis labels ---
            g2.setColor(theme.mutedText());
            g2.setStroke(new BasicStroke(1f));
            String lblXMin = String.format("$%.0f", xMin);
            String lblXMid = String.format("$%.0f", underlyingPrice);
            String lblXMax = String.format("$%.0f", xMax);
            g2.drawString(lblXMin, lp, h - 6);
            g2.drawString(lblXMid, xCurPx - fm.stringWidth(lblXMid) / 2, h - 6);
            g2.drawString(lblXMax, lp + chartW - fm.stringWidth(lblXMax), h - 6);

            // --- Y-axis labels ---
            g2.setColor(theme.mutedText());
            // Max loss label
            String maxLossLbl = String.format("-$%.2f", premium);
            g2.drawString(maxLossLbl, 2, yMaxLossPx + 4);
            // Zero label
            g2.drawString("$0", 2, yZeroPx + 4);
            // Max gain label (at xMax for call, at xMin for put)
            double maxGain = isCall ? (xMax - strike - premium) : (strike - xMin - premium);
            if (maxGain > 0) {
                int yGainPx = tp + (int)((yMax - Math.min(yMax, maxGain)) / yRange * chartH);
                String gainLbl = String.format("+$%.2f", maxGain);
                g2.drawString(gainLbl, 2, yGainPx + 4);
            }

            g2.dispose();
        }

        private void buildFillRegions(double[] xs, double[] ys,
                double xMin, double xMax, double yMin, double yMax,
                int yZeroPx, int lp, int tp, int chartW, int chartH,
                Path2D lossOut, Path2D gainOut) {

            // We'll trace the polygon for each region by walking segments
            // and splitting at y=0 crossings
            java.util.List<double[]> lossPoints = new java.util.ArrayList<>();
            java.util.List<double[]> gainPoints = new java.util.ArrayList<>();

            for (int i = 0; i < xs.length - 1; i++) {
                double x0 = xs[i], y0 = ys[i];
                double x1 = xs[i+1], y1 = ys[i+1];

                if (y0 <= 0 && y1 <= 0) {
                    // entire segment below zero: loss
                    lossPoints.add(new double[]{x0, y0});
                    if (i == xs.length - 2) lossPoints.add(new double[]{x1, y1});
                } else if (y0 >= 0 && y1 >= 0) {
                    // entire segment above zero: gain
                    gainPoints.add(new double[]{x0, y0});
                    if (i == xs.length - 2) gainPoints.add(new double[]{x1, y1});
                } else {
                    // crossing zero: find intersection
                    double t = y0 / (y0 - y1);
                    double xCross = x0 + t * (x1 - x0);
                    if (y0 < 0) {
                        // loss then gain
                        lossPoints.add(new double[]{x0, y0});
                        lossPoints.add(new double[]{xCross, 0});
                        gainPoints.add(new double[]{xCross, 0});
                        if (i == xs.length - 2) gainPoints.add(new double[]{x1, y1});
                    } else {
                        // gain then loss
                        gainPoints.add(new double[]{x0, y0});
                        gainPoints.add(new double[]{xCross, 0});
                        lossPoints.add(new double[]{xCross, 0});
                        if (i == xs.length - 2) lossPoints.add(new double[]{x1, y1});
                    }
                }
            }

            // Build loss polygon (close along zero line)
            if (!lossPoints.isEmpty()) {
                lossOut.moveTo(toPixX(lossPoints.get(0)[0], xMin, xMax, lp, chartW),
                        toPixY(0, yMin, yMax, tp, chartH));
                for (double[] pt : lossPoints)
                    lossOut.lineTo(toPixX(pt[0], xMin, xMax, lp, chartW),
                            toPixY(Math.max(yMin, Math.min(yMax, pt[1])), yMin, yMax, tp, chartH));
                lossOut.lineTo(toPixX(lossPoints.get(lossPoints.size()-1)[0], xMin, xMax, lp, chartW),
                        toPixY(0, yMin, yMax, tp, chartH));
                lossOut.closePath();
            }

            // Build gain polygon (close along zero line)
            if (!gainPoints.isEmpty()) {
                gainOut.moveTo(toPixX(gainPoints.get(0)[0], xMin, xMax, lp, chartW),
                        toPixY(0, yMin, yMax, tp, chartH));
                for (double[] pt : gainPoints)
                    gainOut.lineTo(toPixX(pt[0], xMin, xMax, lp, chartW),
                            toPixY(Math.min(yMax, Math.max(yMin, pt[1])), yMin, yMax, tp, chartH));
                gainOut.lineTo(toPixX(gainPoints.get(gainPoints.size()-1)[0], xMin, xMax, lp, chartW),
                        toPixY(0, yMin, yMax, tp, chartH));
                gainOut.closePath();
            }
        }

        private double toPixX(double x, double xMin, double xMax, int lp, int chartW) {
            return lp + (x - xMin) / (xMax - xMin) * chartW;
        }

        private double toPixY(double y, double yMin, double yMax, int tp, int chartH) {
            return tp + (yMax - y) / (yMax - yMin) * chartH;
        }
    }

    // =========================================================================
    // Sector Pie Chart (rendered in portfolio panel)
    // =========================================================================

    private final Map<String, String> tickerToSector = new HashMap<>();

    private class SectorPieChartPanel extends JPanel {
        private Map<String, Double> sectorWeights = new LinkedHashMap<>();

        private static final Color[] SECTOR_COLORS = {
            new Color(99,179,237), new Color(72,199,142), new Color(252,100,100),
            new Color(255,165,0), new Color(180,100,255), new Color(255,200,50),
            new Color(100,200,200), new Color(200,100,150), new Color(150,200,100),
            new Color(200,200,100), new Color(100,150,200)
        };

        void setSectorWeights(Map<String, Double> weights) {
            this.sectorWeights = weights;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (sectorWeights.isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int size = Math.min(getWidth(), getHeight()) - 20;
            int cx = 10, cy = 10;
            double total = sectorWeights.values().stream().mapToDouble(v -> v).sum();
            if (total == 0) return;

            int startAngle = 0;
            int colorIdx = 0;
            int legendY = cy + 10;
            for (var entry : sectorWeights.entrySet()) {
                int sweep = (int) Math.round(360.0 * entry.getValue() / total);
                if (colorIdx == sectorWeights.size() - 1) sweep = 360 - startAngle;
                Color c = SECTOR_COLORS[colorIdx % SECTOR_COLORS.length];
                g2.setColor(c);
                g2.fillArc(cx, cy, size, size, startAngle, sweep);
                startAngle += sweep;

                // Legend
                int legendX = cx + size + 15;
                g2.setColor(c);
                g2.fillRect(legendX, legendY - 8, 10, 10);
                g2.setColor(theme.primaryText());
                g2.setFont(CAPTION_FONT);
                g2.drawString(String.format("%s (%.0f%%)", entry.getKey(), entry.getValue() / total * 100),
                        legendX + 14, legendY);
                legendY += 16;
                colorIdx++;
            }
            g2.dispose();
        }
    }

    private SectorPieChartPanel sectorPieChart;

    private void updateSectorPieChart(List<PortfolioManager.PortfolioPosition> positions) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (PortfolioManager.PortfolioPosition pos : positions) {
            if (pos.sharesOwned <= 0 || pos.currentPrice <= 0) continue;
            String sector;
            synchronized (tickerToSector) {
                sector = tickerToSector.getOrDefault(pos.ticker, "Unknown");
            }
            weights.merge(sector, pos.marketValue(), Double::sum);
        }
        if (sectorPieChart != null) sectorPieChart.setSectorWeights(weights);
    }

    private class StockChartPanel extends JPanel {

        // ---- Data -----------------------------------------------------------

        /** Primary chart data series; {@code null} while loading or on error. */
        private ChartData mainChartData;

        /** Secondary chart data series for comparison mode; {@code null} when inactive. */
        private ChartData comparisonChartData = null;

        /** Ticker symbol of the comparison series, or {@code null} when inactive. */
        private String comparisonTickerSymbol = null;

        /** Message shown in the centre of the panel when there is no data. */
        private String placeholderMessage = "Search for a stock to see the chart";

        // ---- Indicator state ------------------------------------------------

        /** Whether to overlay the 20-period moving average line. */
        private boolean showMovingAverage20 = false;

        /** Whether to overlay the 50-period moving average line. */
        private boolean showMovingAverage50 = false;

        /** Whether to show the RSI sub-panel below the volume bars. */
        private boolean showRSIPanel = false;

        /** Whether to show the MACD sub-panel below RSI (or below volume if RSI is off). */
        private boolean showMACDPanel = false;

        // ---- Hover state ----------------------------------------------------

        /**
         * Index into {@link #mainChartData} arrays for the bar under the cursor.
         * {@code -1} when the cursor is outside the chart area.
         */
        private int hoveredDataIndex = -1;

        // ---- Layout state (computed each paint, stored for mouse mapping) ---

        /** Pixels reserved on the left edge for Y-axis labels. */
        private int leftPadding;

        /** Pixels reserved on the right edge. */
        private int rightPadding;

        /** Pixels reserved at the top edge. */
        private int topPadding;

        /** Pixels reserved at the bottom edge for X-axis labels. */
        private int bottomPadding;

        /** Height in pixels of the price area (the main line chart region). */
        private int priceAreaHeight;

        /** Height in pixels of the volume bar section. */
        private int volumeSectionHeight;

        /** Width in pixels of the plottable area (between left and right padding). */
        private int chartWidth;

        // ---- Indicator colours now provided by theme (priceLineColor, ma20Color, etc.) ----

        // ---- Zoom / pan state -----------------------------------------------
        private int viewStart = 0;
        private int viewEnd   = -1; // -1 = show all
        private int dragStartX = -1;
        private int dragStartViewStart = 0;

        // ---- Animation state ------------------------------------------------

        /**
         * Progress of the chart-draw animation, 0 → 1.  The chart is clipped
         * to this fraction of its width so it "draws itself" left to right.
         */
        private float animationProgress = 1f;

        /** Timer that drives the chart-draw animation at ~60 fps. */
        private javax.swing.Timer animationTimer;

        // ---- Constructor ----------------------------------------------------

        StockChartPanel() {
            setBackground(theme.card());
            setPreferredSize(new Dimension(0, 300));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    if (dragStartX < 0) updateHoveredIndex(e.getX());
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (dragStartX >= 0 && mainChartData != null) {
                        int totalLen = mainChartData.prices.length;
                        int visLen = getVisibleLength();
                        int dx = e.getX() - dragStartX;
                        double barsPerPx = (double) visLen / chartWidth;
                        int shift = (int) (dx * barsPerPx);
                        viewStart = Math.max(0, Math.min(totalLen - visLen, dragStartViewStart - shift));
                        viewEnd = viewStart + visLen;
                        repaint();
                    }
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) {
                    hoveredDataIndex = -1;
                    repaint();
                }
                @Override public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        dragStartX = e.getX();
                        dragStartViewStart = viewStart;
                        setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    }
                }
                @Override public void mouseReleased(MouseEvent e) {
                    dragStartX = -1;
                    setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        viewStart = 0; viewEnd = -1; repaint(); // reset zoom
                    }
                }
            });
            addMouseWheelListener(e -> {
                if (mainChartData == null) return;
                int totalLen = mainChartData.prices.length;
                int visLen = getVisibleLength();
                int delta = e.getWheelRotation() > 0 ? (int)(visLen * 0.1) : -(int)(visLen * 0.1);
                int newLen = Math.max(10, Math.min(totalLen, visLen + delta));
                // Zoom centered on cursor
                double cursorFrac = (double)(e.getX() - leftPadding) / Math.max(1, chartWidth);
                cursorFrac = Math.max(0, Math.min(1, cursorFrac));
                int center = viewStart + (int)(visLen * cursorFrac);
                viewStart = Math.max(0, center - (int)(newLen * cursorFrac));
                viewEnd = Math.min(totalLen, viewStart + newLen);
                if (viewEnd - viewStart < 10) viewEnd = Math.min(totalLen, viewStart + 10);
                if (viewEnd >= totalLen && viewStart == 0) viewEnd = -1; // show all
                repaint();
            });
        }

        private int getVisibleLength() {
            if (mainChartData == null) return 0;
            int totalLen = mainChartData.prices.length;
            return viewEnd < 0 ? totalLen : Math.min(viewEnd - viewStart, totalLen);
        }

        private double[] sliceArray(double[] arr) {
            if (arr == null) return null;
            int end = viewEnd < 0 ? arr.length : Math.min(viewEnd, arr.length);
            int start = Math.min(viewStart, end);
            return java.util.Arrays.copyOfRange(arr, start, end);
        }

        private long[] sliceLongArray(long[] arr) {
            if (arr == null) return null;
            int end = viewEnd < 0 ? arr.length : Math.min(viewEnd, arr.length);
            int start = Math.min(viewStart, end);
            return java.util.Arrays.copyOfRange(arr, start, end);
        }

        // ---- Public API -----------------------------------------------------

        void setShowMovingAverage20(boolean enabled) { showMovingAverage20 = enabled; repaint(); }
        void setShowMovingAverage50(boolean enabled) { showMovingAverage50 = enabled; repaint(); }
        void setShowRSI(boolean enabled)             { showRSIPanel  = enabled;        repaint(); }
        void setShowMACD(boolean enabled)            { showMACDPanel = enabled;        repaint(); }

        /** Sets the comparison series and its display label, then repaints. */
        void setComparisonData(ChartData data, String ticker) {
            comparisonChartData   = data;
            comparisonTickerSymbol = ticker;
            repaint();
        }

        /** Removes the comparison series and reverts to single-ticker price mode. */
        void clearComparison() {
            comparisonChartData    = null;
            comparisonTickerSymbol = null;
            repaint();
        }

        /** Returns the ticker symbol of the active comparison series, or {@code null}. */
        String getComparisonTicker() { return comparisonTickerSymbol; }

        /** Returns the primary chart data, or {@code null} if none is loaded. */
        ChartData getChartData() { return mainChartData; }

        /** Shows a spinner-like placeholder while data is being fetched. */
        void showLoadingMessage() {
            placeholderMessage = "Loading\u2026";
            mainChartData = null;
            hoveredDataIndex = -1;
            repaint();
        }

        /** Shows an error message in place of the chart. */
        void showError(String message) {
            placeholderMessage = message;
            mainChartData = null;
            hoveredDataIndex = -1;
            repaint();
        }

        /**
         * Installs new chart data and starts the left-to-right draw animation.
         */
        void setChartData(ChartData data) {
            mainChartData = data;
            placeholderMessage = null;
            hoveredDataIndex = -1;
            viewStart = 0; viewEnd = -1; // reset zoom on new data

            // Start draw-in animation (progress 0 → 1 over ~25 frames ≈ 400 ms)
            if (animationTimer != null) animationTimer.stop();
            animationProgress = 0f;
            animationTimer = new javax.swing.Timer(16, null);
            animationTimer.addActionListener(e -> {
                animationProgress = Math.min(1f, animationProgress + 0.04f);
                repaint();
                if (animationProgress >= 1f) animationTimer.stop();
            });
            animationTimer.start();
        }

        // ---- Mouse hover tracking -------------------------------------------

        /**
         * Maps the cursor's X pixel position to the nearest data index and
         * stores it in {@link #hoveredDataIndex}, then repaints.
         */
        private void updateHoveredIndex(int cursorX) {
            if (mainChartData == null || mainChartData.prices.length < 2) {
                hoveredDataIndex = -1;
                repaint();
                return;
            }
            int relativeX = cursorX - leftPadding;
            if (relativeX < 0 || relativeX > chartWidth) {
                hoveredDataIndex = -1;
                repaint();
                return;
            }
            int visLen = getVisibleLength();
            if (visLen < 2) { hoveredDataIndex = -1; repaint(); return; }
            hoveredDataIndex = Math.max(0, Math.min(visLen - 1,
                    (int) Math.round((double) relativeX * (visLen - 1) / chartWidth)));
            repaint();
        }

        // ---- Painting -------------------------------------------------------

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int canvasWidth  = getWidth();
            int canvasHeight = getHeight();

            // --- No data: show placeholder message in the centre --------------
            if (mainChartData == null || mainChartData.prices.length < 2) {
                String msg = placeholderMessage != null ? placeholderMessage : "No data";
                g2.setColor(theme.mutedText());
                g2.setFont(BODY_FONT);
                FontMetrics fm = g2.getFontMetrics();
                g2.drawString(msg,
                        (canvasWidth  - fm.stringWidth(msg)) / 2,
                        canvasHeight / 2);
                return;
            }

            // --- Layout -------------------------------------------------------
            leftPadding       = 64;
            rightPadding      = 14;
            topPadding        = 14;
            bottomPadding     = 26;
            volumeSectionHeight = 48;
            int priceToVolumeGap = 8; // gap between price area and volume section
            int rsiPanelHeight   = showRSIPanel  ? 60 : 0;
            int rsiTopGap        = showRSIPanel  ?  8 : 0;
            int macdPanelHeight  = showMACDPanel ? 70 : 0;
            int macdTopGap       = showMACDPanel ?  8 : 0;

            priceAreaHeight = canvasHeight
                    - topPadding - bottomPadding
                    - volumeSectionHeight - priceToVolumeGap
                    - rsiPanelHeight - rsiTopGap
                    - macdPanelHeight - macdTopGap;
            chartWidth = canvasWidth - leftPadding - rightPadding;

            // --- Apply zoom/pan slice ----------------------------------------
            double[] visPrices  = sliceArray(mainChartData.prices);
            long[]   visVolumes = sliceLongArray(mainChartData.volumes);
            long[]   visTimes   = sliceLongArray(mainChartData.timestamps);
            if (visPrices == null || visPrices.length < 2) return;
            int dataPointCount = visPrices.length;

            // Show "Reset Zoom" hint when zoomed in
            if (viewEnd > 0) {
                g2.setColor(theme.mutedText());
                g2.setFont(CAPTION_FONT);
                g2.drawString("Double-click to reset zoom", canvasWidth - 170, 12);
            }

            // --- Determine Y-axis range --------------------------------------
            boolean isComparisonMode =
                    comparisonChartData != null && comparisonChartData.prices.length >= 2;

            double[] normalizedMain       = null;
            double[] normalizedComparison = null;
            double   yMin, yMax;

            if (isComparisonMode) {
                normalizedMain = new double[dataPointCount];
                for (int i = 0; i < dataPointCount; i++) {
                    normalizedMain[i] =
                            (visPrices[i] / visPrices[0] - 1.0) * 100.0;
                }
                int compCount = comparisonChartData.prices.length;
                normalizedComparison = new double[compCount];
                for (int i = 0; i < compCount; i++) {
                    normalizedComparison[i] =
                            (comparisonChartData.prices[i] / comparisonChartData.prices[0] - 1.0) * 100.0;
                }
                // Y range covers the union of both series
                yMin = Double.MAX_VALUE; yMax = -Double.MAX_VALUE;
                for (double v : normalizedMain)       { if (v < yMin) yMin = v; if (v > yMax) yMax = v; }
                for (double v : normalizedComparison) { if (v < yMin) yMin = v; if (v > yMax) yMax = v; }
            } else {
                yMin = Double.MAX_VALUE; yMax = -Double.MAX_VALUE;
                for (double p : visPrices) { if (p < yMin) yMin = p; if (p > yMax) yMax = p; }
            }

            // Add 5 % padding above and below the range so lines don't touch edges
            double yPadding = (yMax - yMin) * 0.05;
            yMin -= yPadding;
            yMax += yPadding;
            double yRange = yMax - yMin;
            if (yRange == 0) yRange = 1; // guard against flat price series

            // --- Map data points to pixel coordinates ------------------------
            // xPositions[i] is the horizontal pixel for bar i.
            // yPositions[i] is the vertical pixel for bar i.
            int[] xPositions = new int[dataPointCount];
            int[] yPositions = new int[dataPointCount];
            double[] ySource = isComparisonMode ? normalizedMain : visPrices;
            for (int i = 0; i < dataPointCount; i++) {
                xPositions[i] = leftPadding + (int) ((double) chartWidth * i / (dataPointCount - 1));
                yPositions[i] = topPadding  + (int) (priceAreaHeight * (yMax - ySource[i]) / yRange);
            }

            // --- Horizontal grid lines + Y-axis labels -----------------------
            g2.setFont(CAPTION_FONT);
            FontMetrics fontMetrics = g2.getFontMetrics();
            int gridLineCount = 4;
            for (int i = 0; i <= gridLineCount; i++) {
                int gridY = topPadding + priceAreaHeight * i / gridLineCount;
                g2.setColor(theme.border());
                g2.drawLine(leftPadding, gridY, leftPadding + chartWidth, gridY);

                double labelValue = yMax - yRange * i / gridLineCount;
                String labelText  = isComparisonMode
                        ? String.format("%+.1f%%", labelValue)
                        : String.format("%.2f",    labelValue);
                g2.setColor(theme.mutedText());
                g2.drawString(labelText,
                        leftPadding - fontMetrics.stringWidth(labelText) - 5,
                        gridY + fontMetrics.getAscent() / 2 - 1);
            }

            // --- Set animation clip (reveals chart drawing left to right) ----
            // Saved so it can be restored before drawing labels and the tooltip.
            Shape savedClip = g2.getClip();
            if (animationProgress < 1f) {
                int clipX = (int)(leftPadding + chartWidth * animationProgress + 0.5f);
                g2.clipRect(0, 0, clipX, canvasHeight);
            }

            // --- Gradient fill under the main price line (normal mode only) --
            if (!isComparisonMode) {
                Path2D fillArea = new Path2D.Double();
                fillArea.moveTo(xPositions[0], topPadding + priceAreaHeight);
                for (int i = 0; i < dataPointCount; i++) {
                    fillArea.lineTo(xPositions[i], yPositions[i]);
                }
                fillArea.lineTo(xPositions[dataPointCount - 1], topPadding + priceAreaHeight);
                fillArea.closePath();
                g2.setPaint(new GradientPaint(
                        0, topPadding,
                        new Color(theme.priceLineColor().getRed(), theme.priceLineColor().getGreen(),
                                theme.priceLineColor().getBlue(), 46),
                        0, topPadding + priceAreaHeight,
                        new Color(theme.priceLineColor().getRed(), theme.priceLineColor().getGreen(),
                                theme.priceLineColor().getBlue(), 0)));
                g2.fill(fillArea);
            }

            // --- Moving average overlays (only in normal price mode) ---------
            // MA lines are in raw price space; drawing them in comparison mode
            // (which uses a % scale) would place them in the wrong position.
            if (!isComparisonMode) {
                if (showMovingAverage20) {
                    drawMovingAverageLine(g2, xPositions, visPrices,
                            20, theme.ma20Color(), yRange, yMax);
                }
                if (showMovingAverage50) {
                    drawMovingAverageLine(g2, xPositions, visPrices,
                            50, theme.ma50Color(), yRange, yMax);
                }
            }

            // --- Main price line with glow effect ---------------------------
            // Build the path once and draw it three times with decreasing stroke
            // widths and increasing opacity to create a neon-glow appearance.
            Path2D pricePath = new Path2D.Double();
            pricePath.moveTo(xPositions[0], yPositions[0]);
            for (int i = 1; i < dataPointCount; i++) {
                pricePath.lineTo(xPositions[i], yPositions[i]);
            }
            g2.setColor(theme.priceLineColor());
            // Glow layer 1: wide, very faint
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 40f / 255f));
            g2.setStroke(new BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(pricePath);
            // Glow layer 2: medium, semi-transparent
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 80f / 255f));
            g2.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(pricePath);
            // Core line: narrow, fully opaque
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.draw(pricePath);

            // --- Comparison series line + legend ----------------------------
            if (isComparisonMode) {
                int compCount = comparisonChartData.prices.length;
                int[] compXPositions = new int[compCount];
                int[] compYPositions = new int[compCount];
                for (int i = 0; i < compCount; i++) {
                    compXPositions[i] = leftPadding + (int) ((double) chartWidth * i / (compCount - 1));
                    compYPositions[i] = topPadding  + (int) (priceAreaHeight * (yMax - normalizedComparison[i]) / yRange);
                }
                g2.setColor(theme.comparisonLineColor());
                g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int i = 0; i < compCount - 1; i++) {
                    g2.drawLine(compXPositions[i], compYPositions[i],
                                compXPositions[i + 1], compYPositions[i + 1]);
                }
                drawComparisonLegend(g2, fontMetrics,
                        normalizedMain[dataPointCount - 1],
                        normalizedComparison[compCount - 1]);
            }

            // --- Volume bar section ------------------------------------------
            int volumeAreaTop = topPadding + priceAreaHeight + priceToVolumeGap;
            long maxVolume = 1; // minimum of 1 to avoid division by zero
            for (long vol : visVolumes) if (vol > maxVolume) maxVolume = vol;
            int barWidth = Math.max(1, chartWidth / dataPointCount - 1);
            for (int i = 0; i < dataPointCount; i++) {
                int barHeight = (int) ((double) volumeSectionHeight * visVolumes[i] / maxVolume);
                g2.setColor(i == hoveredDataIndex
                        ? new Color(theme.accent().getRed(), theme.accent().getGreen(), theme.accent().getBlue(), 120)
                        : theme.volumeBar());
                g2.fillRect(xPositions[i] - barWidth / 2,
                        volumeAreaTop + volumeSectionHeight - barHeight,
                        barWidth, barHeight);
            }

            // --- RSI sub-panel (optional) ------------------------------------
            if (showRSIPanel) {
                int rsiTop = volumeAreaTop + volumeSectionHeight + rsiTopGap;
                drawRSISubPanel(g2, xPositions, visPrices,
                        rsiTop, rsiPanelHeight, fontMetrics);
            }

            // --- MACD sub-panel (optional) -----------------------------------
            if (showMACDPanel) {
                int macdTop = volumeAreaTop + volumeSectionHeight
                        + rsiTopGap + rsiPanelHeight + macdTopGap;
                drawMACDSubPanel(g2, xPositions, visPrices,
                        macdTop, macdPanelHeight, fontMetrics);
            }

            // Restore the clip so X-axis labels and the tooltip always render fully
            g2.setClip(savedClip);

            // --- X-axis date labels ------------------------------------------
            // Use HH:mm for intraday data and MM/dd for multi-day data.
            boolean isIntradayData =
                    (visTimes[dataPointCount - 1] - visTimes[0])
                    < 2 * 86400L;
            SimpleDateFormat xAxisDateFormat =
                    new SimpleDateFormat(isIntradayData ? "HH:mm" : "MM/dd");
            int labelCount = Math.min(6, dataPointCount);
            g2.setColor(theme.mutedText());
            g2.setFont(CAPTION_FONT);
            fontMetrics = g2.getFontMetrics();
            for (int i = 0; i < labelCount; i++) {
                int dataIdx = (labelCount == 1) ? 0 : (dataPointCount - 1) * i / (labelCount - 1);
                String dateLabel = xAxisDateFormat.format(
                        new Date(visTimes[dataIdx] * 1000L));
                int labelX = xPositions[dataIdx] - fontMetrics.stringWidth(dateLabel) / 2;
                // Clamp so the label doesn't overflow the chart edges
                labelX = Math.max(leftPadding,
                        Math.min(labelX, canvasWidth - rightPadding - fontMetrics.stringWidth(dateLabel)));
                g2.drawString(dateLabel, labelX, canvasHeight - 4);
            }

            // --- Hover crosshair + tooltip -----------------------------------
            if (hoveredDataIndex >= 0) {
                int crosshairX = xPositions[hoveredDataIndex];
                int crosshairY = yPositions[hoveredDataIndex];

                // Vertical dashed line from top of price area to bottom of volume area
                g2.setColor(new Color(200, 200, 220, 80));
                float[] dashPattern = {4f, 4f};
                g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                        BasicStroke.JOIN_BEVEL, 0, dashPattern, 0));
                g2.drawLine(crosshairX, topPadding,
                        crosshairX, volumeAreaTop + volumeSectionHeight);

                // Dot at the data point position on the price line
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(theme.priceLineColor());
                g2.fillOval(crosshairX - 4, crosshairY - 4, 8, 8);
                g2.setColor(theme.card());
                g2.fillOval(crosshairX - 2, crosshairY - 2, 4, 4);

                // Tooltip text content
                boolean isIntraday = isIntradayData;
                SimpleDateFormat tooltipDateFormat =
                        new SimpleDateFormat(isIntraday ? "MMM dd, HH:mm" : "MMM dd, yyyy");
                String tooltipDate   = tooltipDateFormat.format(
                        new Date(visTimes[hoveredDataIndex] * 1000L));
                String tooltipPrice  = isComparisonMode
                        ? String.format("%+.2f%%", normalizedMain[hoveredDataIndex])
                        : String.format("$%.2f",   visPrices[hoveredDataIndex]);
                String tooltipVolume = "Vol: " + formatVolumeForTooltip(
                        visVolumes[hoveredDataIndex]);
                // Extra line for comparison ticker's % at this index
                String tooltipCompLine = null;
                if (isComparisonMode && hoveredDataIndex < normalizedComparison.length) {
                    tooltipCompLine = comparisonTickerSymbol + ": "
                            + String.format("%+.2f%%", normalizedComparison[hoveredDataIndex]);
                }

                // Measure the tooltip box
                g2.setFont(CAPTION_FONT);
                fontMetrics = g2.getFontMetrics();
                int lineCount   = tooltipCompLine != null ? 4 : 3;
                int tooltipWidth = fontMetrics.stringWidth(tooltipDate);
                tooltipWidth = Math.max(tooltipWidth, fontMetrics.stringWidth(tooltipPrice));
                tooltipWidth = Math.max(tooltipWidth, fontMetrics.stringWidth(tooltipVolume));
                if (tooltipCompLine != null) {
                    tooltipWidth = Math.max(tooltipWidth, fontMetrics.stringWidth(tooltipCompLine));
                }
                tooltipWidth  += 20;
                int tooltipHeight = fontMetrics.getHeight() * lineCount + 14;
                int tooltipX      = crosshairX + 10;
                int tooltipY      = topPadding + 2;
                // Flip to left side if it would overflow the right edge
                if (tooltipX + tooltipWidth > canvasWidth - rightPadding) {
                    tooltipX = crosshairX - tooltipWidth - 10;
                }

                // Draw the tooltip background and border
                g2.setColor(new Color(30, 32, 54, 230));
                g2.fillRoundRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight, 8, 8);
                g2.setColor(theme.border());
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight, 8, 8);

                // Draw the tooltip text lines
                int lineHeight = fontMetrics.getHeight();
                g2.setColor(theme.primaryText());
                g2.drawString(tooltipPrice,  tooltipX + 10, tooltipY + lineHeight);
                g2.setColor(theme.mutedText());
                g2.drawString(tooltipDate,   tooltipX + 10, tooltipY + lineHeight * 2 + 2);
                g2.drawString(tooltipVolume, tooltipX + 10, tooltipY + lineHeight * 3 + 4);
                if (tooltipCompLine != null) {
                    g2.setColor(theme.comparisonLineColor());
                    g2.drawString(tooltipCompLine, tooltipX + 10, tooltipY + lineHeight * 4 + 6);
                }
            }
        }

        // ---- Drawing helpers ------------------------------------------------

        /**
         * Draws a moving average line over the price area.
         * Points where the MA value is {@link Double#NaN} (warm-up period) lift
         * the pen so the line starts only when the window is fully filled.
         *
         * @param xPositions pixel X coordinates for each data point
         * @param prices     raw closing prices
         * @param period     MA window size (20 or 50)
         * @param lineColor  colour for the line
         * @param yRange     pixel height ÷ price range ratio (for Y mapping)
         * @param yMax       maximum Y value in the current view
         */
        private void drawMovingAverageLine(Graphics2D g2, int[] xPositions,
                                           double[] prices, int period,
                                           Color lineColor, double yRange, double yMax) {
            double[] maValues = IndicatorCalculator.computeMovingAverage(prices, period);
            g2.setColor(lineColor);
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int prevX = -1, prevY = -1;
            for (int i = 0; i < maValues.length; i++) {
                if (Double.isNaN(maValues[i])) { prevX = -1; continue; } // warm-up — lift pen
                int x = xPositions[i];
                int y = topPadding + (int) (priceAreaHeight * (yMax - maValues[i]) / yRange);
                if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }
        }

        /**
         * Draws the RSI sub-panel below the volume section.
         * Includes: a dark background, dashed overbought (70) and oversold (30)
         * reference lines, the RSI line itself, and axis labels.
         *
         * @param panelTop    Y pixel of the top of the RSI panel
         * @param panelHeight height of the RSI panel in pixels
         */
        private void drawRSISubPanel(Graphics2D g2, int[] xPositions, double[] prices,
                                     int panelTop, int panelHeight, FontMetrics fontMetrics) {
            double[] rsiValues = IndicatorCalculator.computeRSI(prices, 14);

            // Separator line between volume bars and RSI panel
            g2.setColor(new Color(theme.mutedText().getRed(), theme.mutedText().getGreen(),
                    theme.mutedText().getBlue(), 60));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(leftPadding, panelTop, leftPadding + chartWidth, panelTop);

            // Dark background to visually separate RSI from the chart above it
            g2.setColor(theme.background().darker());
            g2.fillRect(leftPadding, panelTop + 1, chartWidth, panelHeight - 1);

            // Overbought (70) and oversold (30) horizontal reference lines
            int y70 = panelTop + (int) (panelHeight * (100 - 70) / 100.0);
            int y30 = panelTop + (int) (panelHeight * (100 - 30) / 100.0);
            float[] dashPattern = {3f, 3f};
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, dashPattern, 0));
            g2.setColor(new Color(theme.loss().getRed(), theme.loss().getGreen(), theme.loss().getBlue(), 80));
            g2.drawLine(leftPadding, y70, leftPadding + chartWidth, y70);
            g2.setColor(new Color(theme.gain().getRed(), theme.gain().getGreen(), theme.gain().getBlue(), 80));
            g2.drawLine(leftPadding, y30, leftPadding + chartWidth, y30);

            // RSI line (purple)
            g2.setColor(new Color(180, 130, 255));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int prevX = -1, prevY = -1;
            for (int i = 0; i < rsiValues.length; i++) {
                if (Double.isNaN(rsiValues[i])) { prevX = -1; continue; }
                int x = xPositions[i];
                int y = panelTop + (int) (panelHeight * (100.0 - rsiValues[i]) / 100.0);
                if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }

            // Y-axis labels: "RSI", "70", "30" to the left of the panel
            g2.setFont(CAPTION_FONT);
            g2.setColor(theme.mutedText());
            g2.setStroke(new BasicStroke(1f));
            g2.drawString("RSI", leftPadding - fontMetrics.stringWidth("RSI") - 5,
                    panelTop + fontMetrics.getAscent());
            g2.drawString("70",  leftPadding - fontMetrics.stringWidth("70")  - 5,
                    y70 + fontMetrics.getAscent() / 2);
            g2.drawString("30",  leftPadding - fontMetrics.stringWidth("30")  - 5,
                    y30 + fontMetrics.getAscent() / 2);
        }

        /**
         * Draws the MACD sub-panel below the RSI (or below volume if RSI is off).
         *
         * <p>Layout:
         * <ul>
         *   <li>Dark background separates it visually from the chart above.</li>
         *   <li>Histogram bars: green when positive, red when negative.</li>
         *   <li>MACD line in cyan-blue; Signal line in orange.</li>
         *   <li>Zero reference line as a thin dashed rule.</li>
         *   <li>Y-axis label "MACD" on the left.</li>
         * </ul>
         *
         * @param panelTop    Y pixel of the top of the MACD panel
         * @param panelHeight height of the MACD panel in pixels
         */
        private void drawMACDSubPanel(Graphics2D g2, int[] xPositions, double[] prices,
                                      int panelTop, int panelHeight, FontMetrics fontMetrics) {
            IndicatorCalculator.MACDData macd =
                    IndicatorCalculator.computeMACD(prices, 12, 26, 9);
            double[] macdLine  = macd.macdLine();
            double[] signalLine = macd.signalLine();
            double[] histogram  = macd.histogram();

            // Separator line
            g2.setColor(new Color(theme.mutedText().getRed(), theme.mutedText().getGreen(),
                    theme.mutedText().getBlue(), 60));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(leftPadding, panelTop, leftPadding + chartWidth, panelTop);

            // Dark background
            g2.setColor(theme.background().darker());
            g2.fillRect(leftPadding, panelTop + 1, chartWidth, panelHeight - 1);

            // Determine Y scale from max absolute value of histogram + MACD
            double maxAbs = 1e-9; // guard against all-NaN warm-up
            for (double v : macdLine)  if (!Double.isNaN(v) && Math.abs(v) > maxAbs) maxAbs = Math.abs(v);
            for (double v : histogram) if (!Double.isNaN(v) && Math.abs(v) > maxAbs) maxAbs = Math.abs(v);
            double yScale = maxAbs == 0 ? 1 : (panelHeight / 2.0) / maxAbs;
            int zeroY = panelTop + panelHeight / 2;

            // Zero reference line (dashed)
            float[] dash = {3f, 3f};
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, dash, 0));
            g2.setColor(new Color(120, 120, 160, 80));
            g2.drawLine(leftPadding, zeroY, leftPadding + chartWidth, zeroY);

            // Histogram bars
            int barW = Math.max(1, chartWidth / prices.length - 1);
            for (int i = 0; i < histogram.length; i++) {
                if (Double.isNaN(histogram[i])) continue;
                int barH = (int) Math.abs(histogram[i] * yScale);
                boolean positive = histogram[i] >= 0;
                g2.setColor(positive
                        ? new Color(theme.gain().getRed(), theme.gain().getGreen(), theme.gain().getBlue(), 120)
                        : new Color(theme.loss().getRed(), theme.loss().getGreen(), theme.loss().getBlue(), 120));
                int x   = xPositions[i];
                int top = positive ? zeroY - barH : zeroY;
                g2.fillRect(x - barW / 2, top, barW, barH);
            }

            // MACD line (cyan-blue)
            g2.setColor(new Color(100, 200, 255));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int prevX = -1, prevY = -1;
            for (int i = 0; i < macdLine.length; i++) {
                if (Double.isNaN(macdLine[i])) { prevX = -1; continue; }
                int x = xPositions[i];
                int y = zeroY - (int) (macdLine[i] * yScale);
                if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }

            // Signal line (orange)
            g2.setColor(new Color(255, 160, 40));
            g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            prevX = -1; prevY = -1;
            for (int i = 0; i < signalLine.length; i++) {
                if (Double.isNaN(signalLine[i])) { prevX = -1; continue; }
                int x = xPositions[i];
                int y = zeroY - (int) (signalLine[i] * yScale);
                if (prevX >= 0) g2.drawLine(prevX, prevY, x, y);
                prevX = x; prevY = y;
            }

            // Y-axis label
            g2.setFont(CAPTION_FONT);
            g2.setColor(theme.mutedText());
            g2.setStroke(new BasicStroke(1f));
            g2.drawString("MACD", leftPadding - fontMetrics.stringWidth("MACD") - 5,
                    panelTop + fontMetrics.getAscent());
        }

        /**
         * Draws a small legend box at the top-right of the chart area showing
         * both tickers with their respective colour swatches and cumulative % change.
         *
         * @param mainFinalPct       % change of the main series at the last bar
         * @param comparisonFinalPct % change of the comparison series at the last bar
         */
        private void drawComparisonLegend(Graphics2D g2, FontMetrics fontMetrics,
                                          double mainFinalPct, double comparisonFinalPct) {
            String mainLegendText = currentTicker + " " + String.format("%+.2f%%", mainFinalPct);
            String compLegendText = comparisonTickerSymbol + " "
                    + String.format("%+.2f%%", comparisonFinalPct);

            int boxWidth  = Math.max(fontMetrics.stringWidth(mainLegendText),
                                     fontMetrics.stringWidth(compLegendText)) + 28;
            int boxHeight = fontMetrics.getHeight() * 2 + 14;
            int boxX      = leftPadding + chartWidth - boxWidth - 4;
            int boxY      = topPadding + 4;

            // Semi-transparent background
            g2.setColor(new Color(20, 20, 38, 210));
            g2.fillRoundRect(boxX, boxY, boxWidth, boxHeight, 6, 6);
            g2.setColor(theme.border());
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 6, 6);

            int lineHeight = fontMetrics.getHeight();

            // Main ticker row (price line colour swatch)
            g2.setColor(theme.priceLineColor());
            g2.fillRect(boxX + 8, boxY + lineHeight - fontMetrics.getAscent() + 2, 8, 8);
            g2.setColor(theme.primaryText());
            g2.drawString(mainLegendText, boxX + 20, boxY + lineHeight);

            // Comparison ticker row (yellow swatch)
            g2.setColor(theme.comparisonLineColor());
            g2.fillRect(boxX + 8, boxY + lineHeight * 2 - fontMetrics.getAscent() + 4, 8, 8);
            g2.setColor(theme.primaryText());
            g2.drawString(compLegendText, boxX + 20, boxY + lineHeight * 2 + 2);
        }

        /**
         * Formats a volume number with K/M suffix for use in the hover tooltip.
         * Returns "N/A" for zero volume.
         */
        private String formatVolumeForTooltip(long volume) {
            if (volume == 0)          return "N/A";
            if (volume >= 1_000_000)  return String.format("%.2fM", volume / 1_000_000.0);
            if (volume >= 1_000)      return String.format("%.1fK", volume / 1_000.0);
            return String.valueOf(volume);
        }
    }
}
