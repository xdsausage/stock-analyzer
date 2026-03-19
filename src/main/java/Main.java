import javax.swing.*;
import javax.swing.border.*;
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
import java.util.*;
import java.util.List;
import java.util.Locale;

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

    // --- Colours --------------------------------------------------------------

    /** Main window/panel background — very dark navy. */
    private static final Color DARK_BACKGROUND   = new Color(18, 18, 28);

    /** Card/inset panel background — slightly lighter navy. */
    private static final Color CARD_BACKGROUND   = new Color(28, 28, 44);

    /** Primary accent colour used for highlights, links, and selected states. */
    private static final Color ACCENT_COLOR      = new Color(99, 179, 237);

    /** Default text colour for values and headings. */
    private static final Color PRIMARY_TEXT      = new Color(240, 240, 255);

    /** Subdued text colour for labels, hints, and secondary information. */
    private static final Color MUTED_TEXT        = new Color(140, 140, 170);

    /** Colour for positive price changes and gains. */
    private static final Color GAIN_COLOR        = new Color(72, 199, 142);

    /** Colour for negative price changes and losses. */
    private static final Color LOSS_COLOR        = new Color(252, 100, 100);

    /** Colour for card borders and dividers. */
    private static final Color BORDER_COLOR      = new Color(50, 50, 75);

    /** Semi-transparent fill used for volume bars in the chart. */
    private static final Color VOLUME_BAR_COLOR  = new Color(99, 179, 237, 55);

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

    // =========================================================================
    // UI component references
    // =========================================================================

    // --- Top-level window and layout ------------------------------------------

    private JFrame     mainWindow;
    private JPanel     topBar;              // NORTH panel; alert banner lives here
    private FadePanel  resultsPanel;        // hidden until a stock is successfully loaded
    private JLabel     statusLabel;         // status bar at the bottom of the window

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

    // --- Portfolio -----------------------------------------------------------

    private PortfolioManager portfolioManager;       // handles persistence
    private JPanel           portfolioRowsContainer; // BoxLayout Y panel of position rows
    private JLabel           portfolioTotalLabel;    // shows total value + overall P&L

    // --- Commodities tab -----------------------------------------------------

    private JPanel                commoditiesGrid;         // 4×2 GridLayout, rebuilt on refresh
    private JLabel                commoditiesLastUpdated;  // "Updated HH:mm:ss"
    private javax.swing.Timer     commoditiesRefreshTimer; // 60 s auto-refresh

    /** Snapshot of a single commodity's live data used to build a card. */
    private record CommoditySnapshot(String ticker, String name,
            double price, double changePercent, double dayHigh, double dayLow,
            String currency, double[] sparkPrices) {}

    // --- Options tab ---------------------------------------------------------

    private JTextField         optionsTickerField;     // ticker input
    private JComboBox<String>  expirationCombo;        // populated after load
    private JPanel             optionsChainContainer;  // BoxLayout Y, holds header + contract rows
    private JLabel             optionsStatusLabel;     // shows underlying price / error
    private OptionsChain       currentOptionsChain;    // most recently loaded chain
    private long               selectedExpiration = 0; // Unix timestamp of chosen expiry

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
        // Use the system look-and-feel so native widgets (scroll bars, dialogs)
        // blend in with the OS; if unavailable the default Swing L&F is used.
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        // All Swing work must happen on the Event Dispatch Thread
        SwingUtilities.invokeLater(() -> new Main().buildUI());
    }

    // =========================================================================
    // UI construction
    // =========================================================================

    /**
     * Builds the complete window hierarchy and makes the window visible.
     * Called once on the EDT at startup.
     */
    private void buildUI() {
        // Load persisted data before building the UI so sidebars are populated immediately.
        watchlistManager = new WatchlistManager();
        watchlistManager.load();
        portfolioManager = new PortfolioManager();
        portfolioManager.load();

        mainWindow = new JFrame("Stock Analyzer \u2014 NASDAQ & NYSE");
        mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainWindow.setMinimumSize(new Dimension(760, 600));

        // Root panel: NORTH = top bar, CENTER = scrollable content, EAST = watchlist
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(DARK_BACKGROUND);

        // --- Alert banner (slides in at the very top when a price alert fires) ---
        alertBannerPanel = new JPanel(new BorderLayout());
        alertBannerPanel.setBackground(new Color(30, 100, 50));
        alertBannerPanel.setVisible(false);
        alertBannerPanel.setPreferredSize(new Dimension(0, 0));
        alertBannerLabel = new JLabel("", SwingConstants.CENTER);
        alertBannerLabel.setFont(BODY_FONT);
        alertBannerLabel.setForeground(Color.WHITE);
        alertBannerPanel.add(alertBannerLabel, BorderLayout.CENTER);

        // Top bar: alert banner + header + search bar stacked
        topBar = new JPanel(new BorderLayout());
        topBar.setBackground(DARK_BACKGROUND);
        topBar.add(alertBannerPanel, BorderLayout.NORTH);

        JPanel topBarContent = new JPanel(new BorderLayout());
        topBarContent.setBackground(DARK_BACKGROUND);
        topBarContent.setBorder(new EmptyBorder(24, 28, 0, 8));
        topBarContent.add(buildHeaderPanel(),    BorderLayout.NORTH);
        topBarContent.add(buildSearchBarPanel(), BorderLayout.CENTER);
        topBar.add(topBarContent, BorderLayout.CENTER);
        rootPanel.add(topBar, BorderLayout.NORTH);

        // Results panel sits inside a scroll pane so all content is reachable.
        resultsPanel = buildResultsPanel();
        resultsPanel.setVisible(false);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(CAPTION_FONT);
        statusLabel.setForeground(MUTED_TEXT);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        statusLabel.setBorder(new EmptyBorder(6, 28, 10, 8));
        statusLabel.setBackground(DARK_BACKGROUND);
        statusLabel.setOpaque(true);

        JScrollPane resultsScrollPane = new JScrollPane(resultsPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultsScrollPane.setBorder(null);
        resultsScrollPane.getViewport().setBackground(DARK_BACKGROUND);
        resultsScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // CardLayout lets the Portfolio view replace the results scroll pane
        centerCardLayout = new CardLayout();
        centerCardPanel  = new JPanel(centerCardLayout);
        centerCardPanel.setBackground(DARK_BACKGROUND);
        centerCardPanel.add(resultsScrollPane, "results");
        centerCardPanel.add(buildPortfolioViewPanel(), "portfolio");
        centerCardPanel.add(buildCommoditiesPanel(), "commodities");
        centerCardPanel.add(buildOptionsPanel(),     "options");

        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.setBackground(DARK_BACKGROUND);
        centerContainer.setBorder(new EmptyBorder(0, 28, 0, 8));
        centerContainer.add(centerCardPanel, BorderLayout.CENTER);
        centerContainer.add(statusLabel,     BorderLayout.SOUTH);
        rootPanel.add(centerContainer,       BorderLayout.CENTER);

        rootPanel.add(buildWatchlistSidebar(), BorderLayout.EAST);

        mainWindow.setContentPane(rootPanel);
        mainWindow.setExtendedState(JFrame.MAXIMIZED_BOTH);
        mainWindow.setVisible(true);

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
        panel.setBackground(DARK_BACKGROUND);
        panel.setBorder(new EmptyBorder(0, 0, 18, 0));

        JLabel titleLabel    = new JLabel("Stock Analyzer");
        titleLabel.setFont(HEADING_FONT);
        titleLabel.setForeground(ACCENT_COLOR);

        JLabel subtitleLabel = new JLabel("Real-time data \u00B7 NASDAQ & NYSE");
        subtitleLabel.setFont(CAPTION_FONT);
        subtitleLabel.setForeground(MUTED_TEXT);

        JPanel textStack = new JPanel(new GridLayout(2, 1, 0, 2));
        textStack.setBackground(DARK_BACKGROUND);
        textStack.add(titleLabel);
        textStack.add(subtitleLabel);
        panel.add(textStack, BorderLayout.WEST);

        // Portfolio toggle button — switches the center view between stock results
        // and the portfolio tracker panel.
        JButton portfolioButton = makeActionButton("\uD83D\uDCC8 Portfolio");
        portfolioButton.addActionListener(e -> {
            String current = centerCardLayout == null ? "results" : "results"; // default
            // Toggle between "results" and "portfolio" cards
            centerCardLayout.show(centerCardPanel, "portfolio");
            refreshPortfolioPricesInBackground();
        });

        // Back-to-results button shown alongside the portfolio button for navigation
        JButton backToStocksButton = makeActionButton("\u2190 Stocks");
        backToStocksButton.addActionListener(e -> centerCardLayout.show(centerCardPanel, "results"));

        JButton commoditiesBtn = makeActionButton("\uD83C\uDFED Commodities");
        commoditiesBtn.addActionListener(e -> {
            centerCardLayout.show(centerCardPanel, "commodities");
            refreshCommoditiesInBackground();
        });

        JButton optionsBtn = makeActionButton("\uD83D\uDCCA Options");
        optionsBtn.addActionListener(e -> centerCardLayout.show(centerCardPanel, "options"));

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        navButtons.setBackground(DARK_BACKGROUND);
        navButtons.add(backToStocksButton);
        navButtons.add(portfolioButton);
        navButtons.add(commoditiesBtn);
        navButtons.add(optionsBtn);
        panel.add(navButtons, BorderLayout.EAST);

        return panel;
    }

    // ---- Search bar ----------------------------------------------------------

    /**
     * Builds the search bar: a text field for the ticker, an "Analyze" button
     * that triggers the fetch, and a "← New" button that clears the results.
     */
    private JPanel buildSearchBarPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(DARK_BACKGROUND);
        panel.setBorder(new EmptyBorder(0, 0, 16, 0));

        tickerInputField = new JTextField();
        tickerInputField.setFont(MONOSPACE_FONT);
        tickerInputField.setBackground(CARD_BACKGROUND);
        tickerInputField.setForeground(PRIMARY_TEXT);
        tickerInputField.setCaretColor(ACCENT_COLOR);
        tickerInputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(10, 14, 10, 14)));
        tickerInputField.putClientProperty("JTextField.placeholderText",
                "Enter ticker, e.g. AAPL");
        tickerInputField.addActionListener(e -> triggerStockFetch()); // Enter key triggers fetch

        analyzeButton = new JButton("Analyze");
        analyzeButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        analyzeButton.setBackground(ACCENT_COLOR);
        analyzeButton.setForeground(new Color(10, 20, 40));
        analyzeButton.setOpaque(true);
        analyzeButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(150, 210, 255), 1, true),
                new EmptyBorder(10, 22, 10, 22)));
        analyzeButton.setFocusPainted(false);
        analyzeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        analyzeButton.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { analyzeButton.setBackground(new Color(140, 210, 255)); }
            @Override public void mouseExited(MouseEvent e)  { analyzeButton.setBackground(ACCENT_COLOR); }
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
    private FadePanel buildResultsPanel() {
        FadePanel panel = new FadePanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(DARK_BACKGROUND);

        // --- Back button ------------------------------------------------------
        newSearchButton = new JButton("\u2190 Back to Search");
        newSearchButton.setFont(CAPTION_FONT);
        newSearchButton.setBackground(new Color(40, 40, 65));
        newSearchButton.setForeground(ACCENT_COLOR);
        newSearchButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(7, 14, 7, 14)));
        newSearchButton.setFocusPainted(false);
        newSearchButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        newSearchButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        newSearchButton.addActionListener(e -> clearResultsAndReset());

        JPanel backRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        backRow.setBackground(DARK_BACKGROUND);
        backRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        backRow.add(newSearchButton);
        panel.add(backRow);
        panel.add(verticalSpacer(8));

        // --- Hero card: company name, price, change, action buttons -----------
        JPanel heroCard = cardPanel(150);
        heroCard.setLayout(new BorderLayout(0, 4));

        JPanel nameRow = new JPanel(new BorderLayout());
        nameRow.setBackground(CARD_BACKGROUND);
        companyNameLabel  = makeLabel("\u2014", STAT_VALUE_FONT, PRIMARY_TEXT);
        exchangeNameLabel = makeLabel("\u2014", CAPTION_FONT, MUTED_TEXT);
        nameRow.add(companyNameLabel,  BorderLayout.CENTER);
        nameRow.add(exchangeNameLabel, BorderLayout.EAST);

        JPanel priceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        priceRow.setBackground(CARD_BACKGROUND);
        heroPriceLabel  = makeLabel("\u2014", HERO_PRICE_FONT, PRIMARY_TEXT);
        dailyChangeLabel = makeLabel("", BODY_FONT, GAIN_COLOR);
        dailyChangeLabel.setBorder(new EmptyBorder(10, 12, 0, 0));
        priceRow.add(heroPriceLabel);
        priceRow.add(dailyChangeLabel);

        // Action buttons are hidden until a stock is loaded
        JPanel actionButtonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        actionButtonRow.setBackground(CARD_BACKGROUND);
        actionButtonRow.setBorder(new EmptyBorder(6, 0, 0, 0));

        addToWatchlistButton = makeActionButton("\u2605 Watchlist");
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
        panel.add(verticalSpacer(10));

        // --- Stat cards row 1: market data ------------------------------------
        marketCapLabel      = statValueLabel();
        currentVolumeLabel  = statValueLabel();
        averageVolumeLabel  = statValueLabel();
        panel.add(statsRow(
            statCard("Market Cap",  marketCapLabel),
            statCard("Volume",      currentVolumeLabel),
            statCard("Avg Volume",  averageVolumeLabel)
        ));
        panel.add(verticalSpacer(8));

        // --- Stat cards row 2: valuation --------------------------------------
        peRatioLabel   = statValueLabel();
        forwardPELabel = statValueLabel();
        epsLabel       = statValueLabel();
        panel.add(statsRow(
            statCard("P/E (TTM)", peRatioLabel),
            statCard("Fwd P/E",   forwardPELabel),
            statCard("EPS (TTM)", epsLabel)
        ));
        panel.add(verticalSpacer(8));

        // --- Stat cards row 3: risk & income ----------------------------------
        betaLabel          = statValueLabel();
        dividendYieldLabel = statValueLabel();
        priceToBookLabel   = statValueLabel();
        panel.add(statsRow(
            statCard("Beta",       betaLabel),
            statCard("Div Yield",  dividendYieldLabel),
            statCard("Price/Book", priceToBookLabel)
        ));
        panel.add(verticalSpacer(8));

        // --- Stat cards row 4: technicals ------------------------------------
        weekHighLabel         = statValueLabel(GAIN_COLOR);
        weekLowLabel          = statValueLabel(LOSS_COLOR);
        fiftyDayAvgLabel      = statValueLabel();
        twoHundredDayAvgLabel = statValueLabel();
        panel.add(statsRow(
            statCard("52W High",  weekHighLabel),
            statCard("52W Low",   weekLowLabel),
            statCard("50D Avg",   fiftyDayAvgLabel),
            statCard("200D Avg",  twoHundredDayAvgLabel)
        ));
        panel.add(verticalSpacer(10));

        // --- Chart card -------------------------------------------------------
        panel.add(buildChartCard());
        panel.add(verticalSpacer(10));

        // --- News section (populated asynchronously after each stock fetch) ---
        panel.add(buildNewsSection());
        panel.add(verticalSpacer(16));

        return panel;
    }

    /**
     * Builds the chart card: comparison input, interval selector, indicator
     * toggles, and the custom chart panel.
     */
    private JPanel buildChartCard() {
        JPanel chartCard = new JPanel(new BorderLayout(0, 6));
        chartCard.setBackground(CARD_BACKGROUND);
        chartCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(10, 12, 10, 12)));
        chartCard.setAlignmentX(Component.LEFT_ALIGNMENT);

        // --- Comparison ticker row -------------------------------------------
        // The user types a second ticker here to overlay a normalised % chart.
        JPanel comparisonRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        comparisonRow.setBackground(CARD_BACKGROUND);

        comparisonTickerField = new JTextField(8);
        comparisonTickerField.setFont(MONOSPACE_FONT);
        comparisonTickerField.setBackground(DARK_BACKGROUND);
        comparisonTickerField.setForeground(PRIMARY_TEXT);
        comparisonTickerField.setCaretColor(ACCENT_COLOR);
        comparisonTickerField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(4, 8, 4, 8)));
        comparisonTickerField.putClientProperty("JTextField.placeholderText", "Compare: TSLA");
        comparisonTickerField.addActionListener(e -> triggerComparisonChartFetch());

        JButton clearComparisonButton = new JButton("Clear");
        clearComparisonButton.setFont(CAPTION_FONT);
        clearComparisonButton.setBackground(DARK_BACKGROUND);
        clearComparisonButton.setForeground(MUTED_TEXT);
        clearComparisonButton.setBorder(new EmptyBorder(4, 10, 4, 10));
        clearComparisonButton.setFocusPainted(false);
        clearComparisonButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearComparisonButton.addActionListener(e -> {
            comparisonTickerField.setText("");
            chartPanel.clearComparison();
            // Re-fetch the main chart to restore normal price scale
            if (currentTicker != null) {
                triggerChartFetch(currentTicker, currentBarInterval, currentTimeRange);
            }
        });

        comparisonRow.add(makeLabel("vs", CAPTION_FONT, MUTED_TEXT));
        comparisonRow.add(comparisonTickerField);
        comparisonRow.add(clearComparisonButton);

        // --- Controls row: interval buttons + indicator toggles --------------
        JPanel controlsRow = new JPanel(new BorderLayout());
        controlsRow.setBackground(CARD_BACKGROUND);

        // Interval buttons (1D, 5D, 1M, 3M, 6M, 1Y)
        JPanel intervalButtonBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        intervalButtonBar.setBackground(CARD_BACKGROUND);
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
        indicatorToggleBar.setBackground(CARD_BACKGROUND);

        JToggleButton ma20Toggle = makeIndicatorToggle("MA 20");
        JToggleButton ma50Toggle = makeIndicatorToggle("MA 50");
        JToggleButton rsiToggle  = makeIndicatorToggle("RSI");
        ma20Toggle.addActionListener(e -> chartPanel.setShowMovingAverage20(ma20Toggle.isSelected()));
        ma50Toggle.addActionListener(e -> chartPanel.setShowMovingAverage50(ma50Toggle.isSelected()));
        rsiToggle.addActionListener(e  -> chartPanel.setShowRSI(rsiToggle.isSelected()));

        indicatorToggleBar.add(ma20Toggle);
        indicatorToggleBar.add(ma50Toggle);
        indicatorToggleBar.add(rsiToggle);

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
        JPanel sidebar = new JPanel(new BorderLayout(0, 8));
        sidebar.setBackground(DARK_BACKGROUND);
        sidebar.setBorder(new EmptyBorder(0, 12, 0, 8));
        sidebar.setPreferredSize(new Dimension(210, 0));

        JPanel sidebarHeader = new JPanel(new BorderLayout());
        sidebarHeader.setBackground(DARK_BACKGROUND);
        sidebarHeader.setBorder(new EmptyBorder(0, 0, 8, 0));
        sidebarHeader.add(makeLabel("Watchlist", STAT_VALUE_FONT, ACCENT_COLOR), BorderLayout.WEST);
        sidebar.add(sidebarHeader, BorderLayout.NORTH);

        // watchlistItemsContainer is a BoxLayout panel — rows are added/removed
        // by rebuildWatchlistRows() every time the list changes.
        watchlistItemsContainer = new JPanel();
        watchlistItemsContainer.setLayout(new BoxLayout(watchlistItemsContainer, BoxLayout.Y_AXIS));
        watchlistItemsContainer.setBackground(DARK_BACKGROUND);

        JScrollPane scrollPane = new JScrollPane(watchlistItemsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scrollPane.setBackground(DARK_BACKGROUND);
        scrollPane.getViewport().setBackground(DARK_BACKGROUND);
        scrollPane.setBorder(BorderFactory.createLineBorder(BORDER_COLOR, 1, true));

        sidebar.add(scrollPane, BorderLayout.CENTER);
        rebuildWatchlistRows();
        return sidebar;
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
            JLabel emptyHint = makeLabel("No tickers saved", CAPTION_FONT, MUTED_TEXT);
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
        row.setBackground(CARD_BACKGROUND);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(7, 10, 7, 8)));
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JLabel tickerLabel = makeLabel(entry.ticker, STAT_VALUE_FONT, ACCENT_COLOR);

        // Right side: price on top row, change % on bottom row
        JPanel priceChangeStack = new JPanel(new GridLayout(2, 1, 0, 1));
        priceChangeStack.setBackground(CARD_BACKGROUND);

        String priceText = entry.lastKnownPrice == 0
                ? "\u2014"
                : String.format("%.2f", entry.lastKnownPrice);
        JLabel priceLabel = makeLabel(priceText, CAPTION_FONT, PRIMARY_TEXT);
        priceLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        String changeText;
        Color  changeColor;
        if (Double.isNaN(entry.lastKnownChangePercent) || entry.lastKnownPrice == 0) {
            changeText  = "";
            changeColor = MUTED_TEXT;
        } else {
            String sign = entry.lastKnownChangePercent >= 0 ? "+" : "";
            changeText  = String.format("%s%.2f%%", sign, entry.lastKnownChangePercent);
            changeColor = entry.lastKnownChangePercent >= 0 ? GAIN_COLOR : LOSS_COLOR;
        }
        JLabel changePercentLabel = makeLabel(changeText, CAPTION_FONT, changeColor);
        changePercentLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        priceChangeStack.add(priceLabel);
        priceChangeStack.add(changePercentLabel);

        // Bell button — set / clear a price alert for this ticker
        boolean hasAlert = !Double.isNaN(entry.alertPrice);
        JButton bellButton = new JButton(hasAlert ? "\uD83D\uDD14" : "\uD83D\uDD15");
        bellButton.setFont(CAPTION_FONT);
        bellButton.setForeground(hasAlert ? GAIN_COLOR : MUTED_TEXT);
        bellButton.setBackground(CARD_BACKGROUND);
        bellButton.setBorder(new EmptyBorder(2, 4, 2, 2));
        bellButton.setFocusPainted(false);
        bellButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        bellButton.setToolTipText(hasAlert
                ? "Alert set at " + String.format("%.2f", entry.alertPrice) + " — click to clear"
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
        removeButton.setFont(CAPTION_FONT);
        removeButton.setForeground(MUTED_TEXT);
        removeButton.setBackground(CARD_BACKGROUND);
        removeButton.setBorder(new EmptyBorder(2, 4, 2, 2));
        removeButton.setFocusPainted(false);
        removeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeButton.addActionListener(e -> {
            watchlistManager.remove(entry.ticker);
            rebuildWatchlistRows();
        });

        JPanel buttonStack = new JPanel(new GridLayout(1, 2, 2, 0));
        buttonStack.setBackground(CARD_BACKGROUND);
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
                row.setBackground(new Color(38, 38, 60));
                priceChangeStack.setBackground(new Color(38, 38, 60));
            }
            @Override public void mouseExited(MouseEvent e) {
                row.setBackground(CARD_BACKGROUND);
                priceChangeStack.setBackground(CARD_BACKGROUND);
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

        SwingWorker<Void, WatchlistManager.WatchlistEntry> worker = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                // Fetch prices sequentially to avoid hammering the API
                for (WatchlistManager.WatchlistEntry entry : entriesToRefresh) {
                    try {
                        StockData freshData = YahooFinanceFetcher.fetch(entry.ticker);
                        watchlistManager.updateEntry(
                                entry.ticker,
                                freshData.currentPrice,
                                freshData.priceChangePercent,
                                freshData.currency);
                        publish(entry); // triggers process() on the EDT
                    } catch (Exception ignored) {
                        // Skip entries that fail (e.g. network error) silently
                    }
                }
                return null;
            }

            @Override
            protected void process(List<WatchlistManager.WatchlistEntry> updatedEntries) {
                // Called on the EDT after each publish() — check alerts, then refresh sidebar
                for (WatchlistManager.WatchlistEntry entry : updatedEntries) {
                    if (!Double.isNaN(entry.alertPrice)
                            && entry.lastKnownPrice > 0
                            && entry.lastKnownPrice >= entry.alertPrice) {
                        fireAlert(entry);
                        entry.alertPrice = Double.NaN;      // consume the alert
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
        JPanel row = new JPanel(new GridLayout(1, cards.length, 8, 0));
        row.setBackground(DARK_BACKGROUND);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 68));
        for (JPanel card : cards) row.add(card);
        return row;
    }

    /** Creates a labelled metric card containing a label above a value label. */
    private JPanel statCard(String metricName, JLabel valueLabel) {
        JPanel card = new JPanel(new GridLayout(2, 1, 0, 2));
        card.setBackground(CARD_BACKGROUND);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(10, 14, 10, 14)));
        JLabel nameLabel = makeLabel(metricName, CAPTION_FONT, MUTED_TEXT);
        card.add(nameLabel);
        card.add(valueLabel);

        // Hover highlight — background brightens when the mouse is over the card.
        // The exit check uses the card's screen rectangle to avoid false exits
        // when the cursor moves between the card panel and its child labels.
        Color hoverBg = new Color(30, 40, 70);
        MouseAdapter hover = new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { card.setBackground(hoverBg); }
            @Override public void mouseExited(MouseEvent e) {
                // Only reset if the cursor actually left the card's bounding box
                Point p = e.getPoint();
                SwingUtilities.convertPointToScreen(p, e.getComponent());
                SwingUtilities.convertPointFromScreen(p, card);
                if (!card.contains(p)) card.setBackground(CARD_BACKGROUND);
            }
        };
        card.addMouseListener(hover);
        nameLabel.addMouseListener(hover);
        valueLabel.addMouseListener(hover);
        return card;
    }

    /** Default stat value label (white text, em-dash placeholder). */
    private JLabel statValueLabel() {
        return makeLabel("\u2014", STAT_VALUE_FONT, PRIMARY_TEXT);
    }

    /** Coloured stat value label (used for 52W High in green and 52W Low in red). */
    private JLabel statValueLabel(Color textColor) {
        return makeLabel("\u2014", STAT_VALUE_FONT, textColor);
    }

    /** Transparent spacer with a fixed height, used to add vertical gaps in BoxLayout. */
    private Component verticalSpacer(int heightPx) {
        JPanel spacer = new JPanel();
        spacer.setBackground(DARK_BACKGROUND);
        spacer.setMaximumSize(new Dimension(Integer.MAX_VALUE, heightPx));
        spacer.setPreferredSize(new Dimension(0, heightPx));
        return spacer;
    }

    /** Creates a plain interval button (e.g. "1M") in the unselected state. */
    private JButton makeIntervalButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(CAPTION_FONT);
        btn.setBackground(DARK_BACKGROUND);
        btn.setForeground(MUTED_TEXT);
        btn.setBorder(new EmptyBorder(4, 10, 4, 10));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /**
     * Creates an indicator toggle button (MA 20 / MA 50 / RSI).
     * The button changes colour when toggled on/off via an ItemListener.
     */
    private JToggleButton makeIndicatorToggle(String label) {
        JToggleButton btn = new JToggleButton(label);
        btn.setFont(CAPTION_FONT);
        btn.setBackground(DARK_BACKGROUND);
        btn.setForeground(MUTED_TEXT);
        btn.setBorder(new EmptyBorder(4, 10, 4, 10));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addItemListener(e -> {
            if (btn.isSelected()) {
                btn.setBackground(new Color(60, 60, 90));
                btn.setForeground(ACCENT_COLOR);
            } else {
                btn.setBackground(DARK_BACKGROUND);
                btn.setForeground(MUTED_TEXT);
            }
        });
        return btn;
    }

    /** Creates an "action" button with a subtle outlined style and hover highlight. */
    private JButton makeActionButton(String label) {
        Color normalBg  = new Color(35, 35, 60);
        Color hoverBg   = new Color(55, 60, 100);
        Color borderNormal = new Color(65, 65, 105);
        Color borderHover  = ACCENT_COLOR;

        JButton btn = new JButton(label);
        btn.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        btn.setBackground(normalBg);
        btn.setForeground(ACCENT_COLOR);
        btn.setOpaque(true);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderNormal, 1, true),
                new EmptyBorder(6, 14, 6, 14)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) {
                btn.setBackground(hoverBg);
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderHover, 1, true),
                        new EmptyBorder(6, 14, 6, 14)));
            }
            @Override public void mouseExited(MouseEvent e) {
                btn.setBackground(normalBg);
                btn.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(borderNormal, 1, true),
                        new EmptyBorder(6, 14, 6, 14)));
            }
        });
        return btn;
    }

    /** Highlights an interval button as the currently selected one. */
    private void applySelectedIntervalStyle(JButton btn) {
        btn.setBackground(ACCENT_COLOR);
        btn.setForeground(new Color(10, 20, 40));
    }

    /** Switches the highlighted interval button and resets the previous one. */
    private void setActiveIntervalButton(JButton btn) {
        if (selectedIntervalBtn != null) {
            selectedIntervalBtn.setBackground(DARK_BACKGROUND);
            selectedIntervalBtn.setForeground(MUTED_TEXT);
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
            selectedIntervalBtn.setBackground(DARK_BACKGROUND);
            selectedIntervalBtn.setForeground(MUTED_TEXT);
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
        panel.setBackground(CARD_BACKGROUND);
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(14, 16, 14, 16)));
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, maxHeightPx));
        panel.setAlignmentX(Component.LEFT_ALIGNMENT);
        return panel;
    }

    /** Convenience factory for a styled {@link JLabel}. */
    private JLabel makeLabel(String text, Font font, Color color) {
        JLabel label = new JLabel(text);
        label.setFont(font);
        label.setForeground(color);
        return label;
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
            showStatus("Please enter a ticker symbol.", LOSS_COLOR);
            return;
        }

        // Disable controls while the request is in flight
        analyzeButton.setEnabled(false);
        tickerInputField.setEnabled(false);
        showStatus("Fetching data for " + ticker + "\u2026", MUTED_TEXT);
        resultsPanel.setVisible(false);

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
                    showStatus("Last updated: " + new Date(), MUTED_TEXT);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    showStatus("Error: " + cause.getMessage(), LOSS_COLOR);
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
                    showStatus("Comparison error: " + ex.getMessage(), LOSS_COLOR);
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
        dailyChangeLabel.setForeground(stockData.priceChange >= 0 ? GAIN_COLOR : LOSS_COLOR);

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
            JLabel loading = makeLabel("Loading news\u2026", CAPTION_FONT, MUTED_TEXT);
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

        // Switch to the results card (in case portfolio was showing)
        if (centerCardLayout != null) centerCardLayout.show(centerCardPanel, "results");

        resultsPanel.setVisible(true);
        resultsPanel.fadeIn();
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
        showStatus(" ", MUTED_TEXT);
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
            showStatus("Exported to " + outputFile.getName(), GAIN_COLOR);
        } catch (IOException e) {
            showStatus("Export failed: " + e.getMessage(), LOSS_COLOR);
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
        card.setBackground(CARD_BACKGROUND);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(12, 14, 12, 14)));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);

        card.add(makeLabel("Latest News", STAT_VALUE_FONT, PRIMARY_TEXT), BorderLayout.NORTH);

        newsSectionContainer = new JPanel();
        newsSectionContainer.setLayout(new BoxLayout(newsSectionContainer, BoxLayout.Y_AXIS));
        newsSectionContainer.setBackground(CARD_BACKGROUND);

        JLabel placeholder = makeLabel("Loading news\u2026", CAPTION_FONT, MUTED_TEXT);
        placeholder.setBorder(new EmptyBorder(6, 0, 0, 0));
        newsSectionContainer.add(placeholder);

        card.add(newsSectionContainer, BorderLayout.CENTER);
        return card;
    }

    /**
     * Builds a single news article card showing the title (clickable), publisher,
     * and relative publish time.
     */
    private JPanel buildNewsCard(NewsItem item) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(CARD_BACKGROUND);
        card.setBorder(new EmptyBorder(8, 0, 8, 0));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 80));

        // Title — rendered as a read-only multi-line text area so long headlines wrap
        JTextArea titleArea = new JTextArea(item.title());
        titleArea.setFont(BODY_FONT);
        titleArea.setForeground(PRIMARY_TEXT);
        titleArea.setBackground(CARD_BACKGROUND);
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
            @Override public void mouseEntered(MouseEvent e) { titleArea.setForeground(ACCENT_COLOR); }
            @Override public void mouseExited(MouseEvent e)  { titleArea.setForeground(PRIMARY_TEXT); }
        });

        // Publisher + relative time (e.g. "Reuters · 3h ago")
        String relTime = formatRelativeTime(item.publishedAt());
        JLabel metaLabel = makeLabel(item.publisher() + " \u00B7 " + relTime, CAPTION_FONT, MUTED_TEXT);

        card.add(titleArea,  BorderLayout.CENTER);
        card.add(metaLabel,  BorderLayout.SOUTH);
        return card;
    }

    /**
     * Clears the news section container and populates it with fresh news cards.
     * Shows a "No news available" hint when the list is empty.
     * Must be called on the EDT.
     */
    private void populateNewsSection(List<NewsItem> items) {
        if (newsSectionContainer == null) return;
        newsSectionContainer.removeAll();
        if (items.isEmpty()) {
            JLabel empty = makeLabel("No news available.", CAPTION_FONT, MUTED_TEXT);
            empty.setBorder(new EmptyBorder(6, 0, 0, 0));
            newsSectionContainer.add(empty);
        } else {
            for (int i = 0; i < items.size(); i++) {
                newsSectionContainer.add(buildNewsCard(items.get(i)));
                if (i < items.size() - 1) {
                    JSeparator sep = new JSeparator();
                    sep.setForeground(BORDER_COLOR);
                    sep.setBackground(BORDER_COLOR);
                    newsSectionContainer.add(sep);
                }
            }
        }
        newsSectionContainer.revalidate();
        newsSectionContainer.repaint();
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
    // Price alerts
    // =========================================================================

    /**
     * Fires a price alert for {@code entry}.  Shows an in-app sliding banner
     * when the window is focused, or a Windows system-tray notification otherwise.
     */
    private void fireAlert(WatchlistManager.WatchlistEntry entry) {
        String msg = "\uD83D\uDD14  " + entry.ticker + " reached "
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

    /** Hides the alert banner immediately. */
    private void hideAlertBanner() {
        alertBannerPanel.setVisible(false);
        alertBannerPanel.setPreferredSize(new Dimension(0, 0));
        topBar.revalidate();
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
            g.setColor(ACCENT_COLOR);
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
        view.setBackground(DARK_BACKGROUND);

        // --- Header ---
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(DARK_BACKGROUND);
        header.setBorder(new EmptyBorder(16, 0, 10, 0));

        JLabel titleLbl = makeLabel("Portfolio", HEADING_FONT, PRIMARY_TEXT);

        JButton addBtn = makeActionButton("\uFF0B Add Position");
        addBtn.addActionListener(e -> showAddPositionDialog());

        portfolioTotalLabel = makeLabel("", BODY_FONT, MUTED_TEXT);
        portfolioTotalLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        header.add(titleLbl,            BorderLayout.WEST);
        header.add(portfolioTotalLabel, BorderLayout.CENTER);
        header.add(addBtn,              BorderLayout.EAST);
        view.add(header, BorderLayout.NORTH);

        // --- Rows container ---
        portfolioRowsContainer = new JPanel();
        portfolioRowsContainer.setLayout(new BoxLayout(portfolioRowsContainer, BoxLayout.Y_AXIS));
        portfolioRowsContainer.setBackground(DARK_BACKGROUND);

        JScrollPane scroll = new JScrollPane(portfolioRowsContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DARK_BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        view.add(scroll, BorderLayout.CENTER);

        refreshPortfolioView();
        return view;
    }

    /**
     * Builds a single portfolio position row.
     * Open positions show: Ticker | Shares | Avg Buy | Price | Value | Unrealized | Realized | [Sell][×]
     * Closed positions (sharesOwned == 0) show the realized gain and only the remove button.
     */
    private JPanel buildPortfolioRow(PortfolioManager.PortfolioPosition pos) {
        boolean closed = pos.sharesOwned <= 0;

        Color rowBg = closed ? new Color(22, 22, 38) : CARD_BACKGROUND;
        JPanel row = new JPanel(new GridLayout(1, 8, 6, 0));
        row.setBackground(rowBg);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(closed ? new Color(40, 40, 60) : BORDER_COLOR, 1, true),
                new EmptyBorder(10, 12, 10, 12)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 54));

        // Col 1: Ticker (with CLOSED badge for fully-sold positions)
        String tickerText = closed ? pos.ticker + "  \u2713CLOSED" : pos.ticker;
        row.add(makeLabel(tickerText, STAT_VALUE_FONT, closed ? MUTED_TEXT : ACCENT_COLOR));

        // Col 2: Shares
        row.add(makeLabel(closed ? "\u2014" : String.format("%.4f shs", pos.sharesOwned),
                BODY_FONT, PRIMARY_TEXT));

        // Col 3: Avg buy price
        row.add(makeLabel(String.format("$%.2f avg", pos.averageBuyPrice), BODY_FONT, MUTED_TEXT));

        // Col 4: Current price
        row.add(makeLabel(closed || pos.currentPrice == 0 ? "\u2014"
                : String.format("$%.2f", pos.currentPrice), BODY_FONT, PRIMARY_TEXT));

        // Col 5: Market value
        row.add(makeLabel(closed || pos.currentPrice == 0 ? "\u2014"
                : String.format("$%.2f", pos.marketValue()), BODY_FONT, PRIMARY_TEXT));

        // Col 6: Unrealized P&L ($ + %)
        if (closed || pos.currentPrice == 0) {
            row.add(makeLabel("\u2014", BODY_FONT, MUTED_TEXT));
        } else {
            double ug   = pos.unrealizedGain();
            String sign = ug >= 0 ? "+" : "";
            Color  col  = ug >= 0 ? GAIN_COLOR : LOSS_COLOR;
            row.add(makeLabel(String.format("%s$%.2f (%s%.1f%%)",
                    sign, ug, sign, pos.unrealizedGainPercent()), BODY_FONT, col));
        }

        // Col 7: Realized P&L (from sells)
        if (pos.realizedGain == 0) {
            row.add(makeLabel("\u2014", BODY_FONT, MUTED_TEXT));
        } else {
            String sign = pos.realizedGain >= 0 ? "+" : "";
            Color  col  = pos.realizedGain >= 0 ? GAIN_COLOR : LOSS_COLOR;
            row.add(makeLabel(String.format("%s$%.2f", sign, pos.realizedGain), BODY_FONT, col));
        }

        // Col 8: Action buttons — Sell (open positions only) + Remove
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.setBackground(rowBg);

        if (!closed) {
            JButton sellBtn = new JButton("Sell");
            sellBtn.setFont(new Font("Segoe UI", Font.PLAIN, 11));
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

        JButton removeBtn = new JButton("\u00D7");
        removeBtn.setFont(CAPTION_FONT);
        removeBtn.setForeground(MUTED_TEXT);
        removeBtn.setBackground(rowBg);
        removeBtn.setBorder(new EmptyBorder(3, 6, 3, 2));
        removeBtn.setFocusPainted(false);
        removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { removeBtn.setForeground(LOSS_COLOR); }
            @Override public void mouseExited(MouseEvent e)  { removeBtn.setForeground(MUTED_TEXT); }
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
        headers.setBackground(DARK_BACKGROUND);
        headers.setBorder(new EmptyBorder(0, 12, 4, 12));
        headers.setAlignmentX(Component.LEFT_ALIGNMENT);
        headers.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        for (String col : new String[]{"Ticker", "Shares", "Avg Buy", "Price", "Value", "Unrealized", "Realized", ""}) {
            headers.add(makeLabel(col, CAPTION_FONT, MUTED_TEXT));
        }
        portfolioRowsContainer.add(headers);
        portfolioRowsContainer.add(Box.createVerticalStrut(2));

        List<PortfolioManager.PortfolioPosition> positions = portfolioManager.getPositions();
        if (positions.isEmpty()) {
            JLabel empty = makeLabel("No positions yet. Click \"\uFF0B Add Position\" to get started.",
                    BODY_FONT, MUTED_TEXT);
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
                portfolioTotalLabel.setForeground(totalGain >= 0 ? GAIN_COLOR : LOSS_COLOR);
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

        JPanel form = new JPanel(new GridLayout(3, 2, 8, 6));
        form.add(new JLabel("Ticker:"));       form.add(tickerField);
        form.add(new JLabel("Shares:"));       form.add(sharesField);
        form.add(new JLabel("Avg Buy Price:")); form.add(buyPriceField);

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

        JPanel form = new JPanel(new GridLayout(4, 2, 8, 6));
        form.add(new JLabel("Ticker:"));
        form.add(makeLabel(pos.ticker, BODY_FONT, ACCENT_COLOR));
        form.add(new JLabel("Shares owned:"));
        form.add(makeLabel(String.format("%.4f", pos.sharesOwned), BODY_FONT, PRIMARY_TEXT));
        form.add(new JLabel("Shares to sell:"));
        form.add(sharesField);
        form.add(new JLabel("Sell price ($):"));
        form.add(priceField);

        int result = JOptionPane.showConfirmDialog(mainWindow, form,
                "Sell Position — " + pos.ticker, JOptionPane.OK_CANCEL_OPTION,
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
                    "Please enter a valid share count (≤ shares owned) and price.",
                    "Invalid Input", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Fetches the current price for each portfolio position on a background
     * thread, updating {@code currentPrice} on each position, then calls
     * {@link #refreshPortfolioView()} on the EDT.
     */
    private void refreshPortfolioPricesInBackground() {
        // Only fetch for open positions — closed ones have no live price to show.
        List<PortfolioManager.PortfolioPosition> snapshot = portfolioManager.getPositions()
                .stream().filter(p -> p.sharesOwned > 0).collect(java.util.stream.Collectors.toList());
        if (snapshot.isEmpty()) return;

        new SwingWorker<Void, PortfolioManager.PortfolioPosition>() {
            @Override
            protected Void doInBackground() {
                for (PortfolioManager.PortfolioPosition pos : snapshot) {
                    try {
                        StockData data = YahooFinanceFetcher.fetch(pos.ticker);
                        pos.currentPrice = data.currentPrice;
                        if (data.currency != null) pos.currency = data.currency;
                        publish(pos);
                    } catch (Exception ignored) {}
                }
                return null;
            }
            @Override
            protected void process(List<PortfolioManager.PortfolioPosition> updated) {
                // Propagate the fetched prices back into the live portfolio list
                for (PortfolioManager.PortfolioPosition snap : updated) {
                    for (PortfolioManager.PortfolioPosition live : portfolioManager.getPositions()) {
                        if (live.ticker.equals(snap.ticker)) {
                            live.currentPrice = snap.currentPrice;
                            live.currency     = snap.currency;
                        }
                    }
                }
                refreshPortfolioView();
            }
        }.execute();
    }

    // =========================================================================
    // Commodities panel
    // =========================================================================

    /** Builds the full Commodities dashboard panel. */
    private JPanel buildCommoditiesPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(DARK_BACKGROUND);
        panel.setBorder(new EmptyBorder(16, 0, 8, 0));

        // Header row
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setBackground(DARK_BACKGROUND);
        header.setBorder(new EmptyBorder(0, 0, 10, 0));
        header.add(makeLabel("Commodities", HEADING_FONT, PRIMARY_TEXT), BorderLayout.WEST);
        commoditiesLastUpdated = makeLabel("", CAPTION_FONT, MUTED_TEXT);
        commoditiesLastUpdated.setHorizontalAlignment(SwingConstants.CENTER);
        header.add(commoditiesLastUpdated, BorderLayout.CENTER);
        JButton refreshBtn = makeActionButton("\u21BB Refresh");
        refreshBtn.addActionListener(e -> refreshCommoditiesInBackground());
        header.add(refreshBtn, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);

        // 4×2 grid of commodity cards
        commoditiesGrid = new JPanel(new GridLayout(4, 2, 12, 12));
        commoditiesGrid.setBackground(DARK_BACKGROUND);
        for (String[] comm : COMMODITIES) {
            commoditiesGrid.add(buildCommodityCard(null, comm[1]));
        }

        JScrollPane scroll = new JScrollPane(commoditiesGrid,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DARK_BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        // Start 60-second auto-refresh timer
        commoditiesRefreshTimer = new javax.swing.Timer(60_000,
                e -> refreshCommoditiesInBackground());
        commoditiesRefreshTimer.start();

        return panel;
    }

    /** Builds a single commodity card. Pass {@code null} snap for a loading placeholder. */
    private JPanel buildCommodityCard(CommoditySnapshot snap, String displayName) {
        JPanel card = new JPanel(new BorderLayout(0, 4));
        card.setBackground(CARD_BACKGROUND);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(12, 14, 12, 14)));

        String name = snap != null ? snap.name() : displayName;
        card.add(makeLabel(name, BODY_FONT, PRIMARY_TEXT), BorderLayout.NORTH);

        String priceText = snap != null ? String.format("%.2f", snap.price()) : "\u2014";
        card.add(makeLabel(priceText, STAT_VALUE_FONT, PRIMARY_TEXT), BorderLayout.CENTER);

        JPanel southRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        southRow.setBackground(CARD_BACKGROUND);
        if (snap != null) {
            String sign = snap.changePercent() >= 0 ? "+" : "";
            Color chgColor = snap.changePercent() >= 0 ? GAIN_COLOR : LOSS_COLOR;
            southRow.add(makeLabel(String.format("%s%.2f%%", sign, snap.changePercent()),
                    CAPTION_FONT, chgColor));
            southRow.add(makeLabel(String.format("H: %.2f", snap.dayHigh()),
                    CAPTION_FONT, MUTED_TEXT));
            southRow.add(makeLabel(String.format("L: %.2f", snap.dayLow()),
                    CAPTION_FONT, MUTED_TEXT));
        } else {
            southRow.add(makeLabel("Loading\u2026", CAPTION_FONT, MUTED_TEXT));
        }
        card.add(southRow, BorderLayout.SOUTH);

        if (snap != null && snap.sparkPrices() != null && snap.sparkPrices().length >= 2) {
            SparklinePanel sparkline = new SparklinePanel();
            sparkline.setData(snap.sparkPrices());
            sparkline.setPreferredSize(new Dimension(80, 50));
            card.add(sparkline, BorderLayout.EAST);
        }

        return card;
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
                    int idx = -1;
                    for (int i = 0; i < COMMODITIES.length; i++) {
                        if (COMMODITIES[i][0].equals(snap.ticker())) { idx = i; break; }
                    }
                    if (idx < 0 || commoditiesGrid == null) continue;
                    commoditiesGrid.remove(idx);
                    commoditiesGrid.add(buildCommodityCard(snap, snap.name()), idx);
                    commoditiesGrid.revalidate();
                    commoditiesGrid.repaint();
                }
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
            }
        }.execute();
    }

    // =========================================================================
    // Sparkline inner panel
    // =========================================================================

    private static class SparklinePanel extends JPanel {
        private double[] prices;

        void setData(double[] prices) {
            this.prices = prices;
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
            Color lineColor = prices[prices.length - 1] >= prices[0] ? GAIN_COLOR : LOSS_COLOR;
            g2.setColor(lineColor);
            g2.setStroke(new BasicStroke(1.5f));
            Path2D path = new Path2D.Float();
            for (int i = 0; i < prices.length; i++) {
                double x = w * i / (prices.length - 1.0);
                double y = h - h * (prices[i] - min) / range;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g2.draw(path);
        }
    }

    // =========================================================================
    // Options panel
    // =========================================================================

    /** Builds the Options chain viewer panel. */
    private JPanel buildOptionsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBackground(DARK_BACKGROUND);
        panel.setBorder(new EmptyBorder(16, 0, 8, 0));

        // Row 1: ticker input + Load button + status label
        optionsTickerField = new JTextField(8);
        optionsTickerField.setFont(MONOSPACE_FONT);
        optionsTickerField.setBackground(CARD_BACKGROUND);
        optionsTickerField.setForeground(PRIMARY_TEXT);
        optionsTickerField.setCaretColor(ACCENT_COLOR);
        optionsTickerField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(6, 10, 6, 10)));
        optionsTickerField.putClientProperty("JTextField.placeholderText", "e.g. AAPL");

        JButton loadBtn = makeActionButton("Load Options");
        loadBtn.addActionListener(e -> {
            String t = optionsTickerField.getText().trim().toUpperCase();
            if (!t.isEmpty()) loadOptionsInBackground(t);
        });
        optionsTickerField.addActionListener(e -> loadBtn.doClick());

        optionsStatusLabel = makeLabel("Enter a ticker and click Load Options.", CAPTION_FONT, MUTED_TEXT);

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row1.setBackground(DARK_BACKGROUND);
        row1.add(optionsTickerField);
        row1.add(loadBtn);
        row1.add(optionsStatusLabel);

        // Row 2: expiration picker
        expirationCombo = new JComboBox<>();
        expirationCombo.setFont(CAPTION_FONT);
        expirationCombo.setBackground(CARD_BACKGROUND);
        expirationCombo.setForeground(PRIMARY_TEXT);
        expirationCombo.addActionListener(e -> {
            if (currentOptionsChain == null || expirationCombo.getSelectedIndex() < 0) return;
            int idx = expirationCombo.getSelectedIndex();
            if (idx < currentOptionsChain.expirationDates.length) {
                selectedExpiration = currentOptionsChain.expirationDates[idx];
                displayOptionsChain(currentOptionsChain, selectedExpiration);
            }
        });

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        row2.setBackground(DARK_BACKGROUND);
        row2.add(makeLabel("Expiration:", CAPTION_FONT, MUTED_TEXT));
        row2.add(expirationCombo);

        JPanel headerRows = new JPanel(new GridLayout(2, 1, 0, 4));
        headerRows.setBackground(DARK_BACKGROUND);
        headerRows.setBorder(new EmptyBorder(0, 0, 8, 0));
        headerRows.add(row1);
        headerRows.add(row2);
        panel.add(headerRows, BorderLayout.NORTH);

        // Chain container (scrollable)
        optionsChainContainer = new JPanel();
        optionsChainContainer.setLayout(new BoxLayout(optionsChainContainer, BoxLayout.Y_AXIS));
        optionsChainContainer.setBackground(DARK_BACKGROUND);

        JScrollPane scroll = new JScrollPane(optionsChainContainer,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(null);
        scroll.getViewport().setBackground(DARK_BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    /** Loads the options chain for {@code ticker} in the background. */
    private void loadOptionsInBackground(String ticker) {
        optionsStatusLabel.setText("Loading\u2026");
        optionsChainContainer.removeAll();
        optionsChainContainer.revalidate();
        currentOptionsChain = null;

        new SwingWorker<OptionsChain, Void>() {
            @Override
            protected OptionsChain doInBackground() throws Exception {
                return YahooFinanceFetcher.fetchOptions(ticker);
            }

            @Override
            protected void done() {
                try {
                    OptionsChain chain = get();
                    currentOptionsChain = chain;
                    // Populate expiration combo
                    expirationCombo.removeAllItems();
                    SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, yyyy");
                    for (long exp : chain.expirationDates) {
                        expirationCombo.addItem(sdf.format(new Date(exp * 1000L)));
                    }
                    optionsStatusLabel.setText("Underlying: $"
                            + String.format("%.2f", chain.underlyingPrice));
                    optionsStatusLabel.setForeground(PRIMARY_TEXT);
                    if (chain.expirationDates.length > 0) {
                        selectedExpiration = chain.expirationDates[0];
                        displayOptionsChain(chain, selectedExpiration);
                    }
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    optionsStatusLabel.setText("Error: " + cause.getMessage());
                    optionsStatusLabel.setForeground(LOSS_COLOR);
                }
            }
        }.execute();
    }

    /** Renders the options chain for the given expiration date on the EDT. */
    private void displayOptionsChain(OptionsChain chain, long expiration) {
        optionsChainContainer.removeAll();

        // Filter to the chosen expiration and sort by strike
        List<OptionsContract> calls = new ArrayList<>();
        List<OptionsContract> puts  = new ArrayList<>();
        for (OptionsContract c : chain.calls) {
            if (c.expiration() == expiration) calls.add(c);
        }
        for (OptionsContract p : chain.puts) {
            if (p.expiration() == expiration) puts.add(p);
        }
        calls.sort(Comparator.comparingDouble(OptionsContract::strike));
        puts.sort(Comparator.comparingDouble(OptionsContract::strike));

        // Collect all unique strikes
        TreeSet<Double> strikeSet = new TreeSet<>();
        calls.forEach(c -> strikeSet.add(c.strike()));
        puts.forEach(p -> strikeSet.add(p.strike()));

        // Column header row
        JPanel headerRow = new JPanel(new GridLayout(1, 3, 4, 0));
        headerRow.setBackground(new Color(22, 22, 38));
        headerRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        headerRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        headerRow.setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel callHeader = new JPanel(new GridLayout(1, 6, 4, 0));
        callHeader.setBackground(new Color(22, 22, 38));
        for (String col : new String[]{"Bid", "Ask", "Last", "Vol", "OI", "IV %"}) {
            JLabel lbl = makeLabel(col, CAPTION_FONT, MUTED_TEXT);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            callHeader.add(lbl);
        }
        JLabel strikeHeader = makeLabel("Strike", CAPTION_FONT, ACCENT_COLOR);
        strikeHeader.setHorizontalAlignment(SwingConstants.CENTER);
        JPanel putHeader = new JPanel(new GridLayout(1, 6, 4, 0));
        putHeader.setBackground(new Color(22, 22, 38));
        for (String col : new String[]{"IV %", "OI", "Vol", "Last", "Ask", "Bid"}) {
            JLabel lbl = makeLabel(col, CAPTION_FONT, MUTED_TEXT);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            putHeader.add(lbl);
        }
        headerRow.add(callHeader);
        headerRow.add(strikeHeader);
        headerRow.add(putHeader);
        optionsChainContainer.add(headerRow);
        optionsChainContainer.add(Box.createVerticalStrut(2));

        // One row per strike
        for (double strike : strikeSet) {
            OptionsContract call = calls.stream()
                    .filter(c -> c.strike() == strike).findFirst().orElse(null);
            OptionsContract put = puts.stream()
                    .filter(p -> p.strike() == strike).findFirst().orElse(null);
            JPanel row = buildOptionsRow(call, strike, put);
            optionsChainContainer.add(row);
            optionsChainContainer.add(Box.createVerticalStrut(2));
        }

        optionsChainContainer.revalidate();
        optionsChainContainer.repaint();
    }

    /** Builds a single call | strike | put row for the options chain. */
    private JPanel buildOptionsRow(OptionsContract call, double strike, OptionsContract put) {
        Color callBg = (call != null && call.inTheMoney())
                ? new Color(25, 50, 30) : CARD_BACKGROUND;
        Color putBg  = (put  != null && put.inTheMoney())
                ? new Color(50, 25, 25) : CARD_BACKGROUND;

        JPanel callPanel = new JPanel(new GridLayout(1, 6, 4, 0));
        callPanel.setBackground(callBg);
        callPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        String[] callVals = call == null
                ? new String[]{"\u2014", "\u2014", "\u2014", "\u2014", "\u2014", "\u2014"}
                : new String[]{
                    String.format("$%.2f",    call.bid()),
                    String.format("$%.2f",    call.ask()),
                    String.format("$%.2f",    call.lastPrice()),
                    String.valueOf(call.volume()),
                    String.valueOf(call.openInterest()),
                    String.format("%.1f%%",   call.impliedVolatility() * 100)
                };
        for (String v : callVals) {
            JLabel lbl = makeLabel(v, CAPTION_FONT, PRIMARY_TEXT);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            callPanel.add(lbl);
        }

        JLabel strikeLabel = makeLabel(String.format("%.2f", strike), CAPTION_FONT, ACCENT_COLOR);
        strikeLabel.setHorizontalAlignment(SwingConstants.CENTER);
        strikeLabel.setFont(new Font("Segoe UI", Font.BOLD, 11));
        JPanel strikePnl = new JPanel(new BorderLayout());
        strikePnl.setBackground(CARD_BACKGROUND);
        strikePnl.add(strikeLabel, BorderLayout.CENTER);

        JPanel putPanel = new JPanel(new GridLayout(1, 6, 4, 0));
        putPanel.setBackground(putBg);
        putPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        String[] putVals = put == null
                ? new String[]{"\u2014", "\u2014", "\u2014", "\u2014", "\u2014", "\u2014"}
                : new String[]{
                    String.format("%.1f%%",  put.impliedVolatility() * 100),
                    String.valueOf(put.openInterest()),
                    String.valueOf(put.volume()),
                    String.format("$%.2f",   put.lastPrice()),
                    String.format("$%.2f",   put.ask()),
                    String.format("$%.2f",   put.bid())
                };
        for (String v : putVals) {
            JLabel lbl = makeLabel(v, CAPTION_FONT, PRIMARY_TEXT);
            lbl.setHorizontalAlignment(SwingConstants.CENTER);
            putPanel.add(lbl);
        }

        JPanel row = new JPanel(new GridLayout(1, 3, 4, 0));
        row.setBackground(CARD_BACKGROUND);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(0, 0, 0, 0)));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.add(callPanel);
        row.add(strikePnl);
        row.add(putPanel);
        return row;
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

        // ---- Indicator colours (not part of the outer class palette) --------

        /** Brighter cyan-blue for the main price line and its glow. */
        private static final Color PRICE_LINE_COLOR      = new Color(120, 200, 255);
        /** Line colour for the 20-period MA overlay. */
        private static final Color MA20_LINE_COLOR       = new Color(255, 165,   0); // orange
        /** Line colour for the 50-period MA overlay. */
        private static final Color MA50_LINE_COLOR       = new Color(180, 100, 255); // purple
        /** Line colour for the comparison series. */
        private static final Color COMPARISON_LINE_COLOR = new Color(255, 200,  50); // yellow

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
            setBackground(CARD_BACKGROUND);
            setPreferredSize(new Dimension(0, 300));
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));

            addMouseMotionListener(new MouseMotionAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    updateHoveredIndex(e.getX());
                }
            });
            addMouseListener(new MouseAdapter() {
                @Override public void mouseExited(MouseEvent e) {
                    hoveredDataIndex = -1;
                    repaint();
                }
            });
        }

        // ---- Public API -----------------------------------------------------

        void setShowMovingAverage20(boolean enabled) { showMovingAverage20 = enabled; repaint(); }
        void setShowMovingAverage50(boolean enabled) { showMovingAverage50 = enabled; repaint(); }
        void setShowRSI(boolean enabled)             { showRSIPanel = enabled;         repaint(); }

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
            int dataPointCount = mainChartData.prices.length;
            hoveredDataIndex = Math.max(0, Math.min(dataPointCount - 1,
                    (int) Math.round((double) relativeX * (dataPointCount - 1) / chartWidth)));
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
                g2.setColor(MUTED_TEXT);
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
            int rsiPanelHeight   = showRSIPanel ? 60 : 0;
            int rsiTopGap        = showRSIPanel ?  8 : 0;

            priceAreaHeight = canvasHeight
                    - topPadding - bottomPadding
                    - volumeSectionHeight - priceToVolumeGap
                    - rsiPanelHeight - rsiTopGap;
            chartWidth = canvasWidth - leftPadding - rightPadding;

            int dataPointCount = mainChartData.prices.length;

            // --- Determine Y-axis range --------------------------------------
            // In comparison mode both series are converted to % change from
            // their first data point so they share the same Y axis scale.
            boolean isComparisonMode =
                    comparisonChartData != null && comparisonChartData.prices.length >= 2;

            double[] normalizedMain       = null;
            double[] normalizedComparison = null;
            double   yMin, yMax;

            if (isComparisonMode) {
                normalizedMain = new double[dataPointCount];
                for (int i = 0; i < dataPointCount; i++) {
                    normalizedMain[i] =
                            (mainChartData.prices[i] / mainChartData.prices[0] - 1.0) * 100.0;
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
                for (double p : mainChartData.prices) { if (p < yMin) yMin = p; if (p > yMax) yMax = p; }
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
            double[] ySource = isComparisonMode ? normalizedMain : mainChartData.prices;
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
                g2.setColor(BORDER_COLOR);
                g2.drawLine(leftPadding, gridY, leftPadding + chartWidth, gridY);

                double labelValue = yMax - yRange * i / gridLineCount;
                String labelText  = isComparisonMode
                        ? String.format("%+.1f%%", labelValue)
                        : String.format("%.2f",    labelValue);
                g2.setColor(MUTED_TEXT);
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
                        new Color(PRICE_LINE_COLOR.getRed(), PRICE_LINE_COLOR.getGreen(),
                                PRICE_LINE_COLOR.getBlue(), 46),
                        0, topPadding + priceAreaHeight,
                        new Color(PRICE_LINE_COLOR.getRed(), PRICE_LINE_COLOR.getGreen(),
                                PRICE_LINE_COLOR.getBlue(), 0)));
                g2.fill(fillArea);
            }

            // --- Moving average overlays (only in normal price mode) ---------
            // MA lines are in raw price space; drawing them in comparison mode
            // (which uses a % scale) would place them in the wrong position.
            if (!isComparisonMode) {
                if (showMovingAverage20) {
                    drawMovingAverageLine(g2, xPositions, mainChartData.prices,
                            20, MA20_LINE_COLOR, yRange, yMax);
                }
                if (showMovingAverage50) {
                    drawMovingAverageLine(g2, xPositions, mainChartData.prices,
                            50, MA50_LINE_COLOR, yRange, yMax);
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
            g2.setColor(PRICE_LINE_COLOR);
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
                g2.setColor(COMPARISON_LINE_COLOR);
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
            for (long vol : mainChartData.volumes) if (vol > maxVolume) maxVolume = vol;
            int barWidth = Math.max(1, chartWidth / dataPointCount - 1);
            for (int i = 0; i < dataPointCount; i++) {
                int barHeight = (int) ((double) volumeSectionHeight * mainChartData.volumes[i] / maxVolume);
                g2.setColor(i == hoveredDataIndex
                        ? new Color(ACCENT_COLOR.getRed(), ACCENT_COLOR.getGreen(), ACCENT_COLOR.getBlue(), 120)
                        : VOLUME_BAR_COLOR);
                g2.fillRect(xPositions[i] - barWidth / 2,
                        volumeAreaTop + volumeSectionHeight - barHeight,
                        barWidth, barHeight);
            }

            // --- RSI sub-panel (optional) ------------------------------------
            if (showRSIPanel) {
                int rsiTop = volumeAreaTop + volumeSectionHeight + rsiTopGap;
                drawRSISubPanel(g2, xPositions, mainChartData.prices,
                        rsiTop, rsiPanelHeight, fontMetrics);
            }

            // Restore the clip so X-axis labels and the tooltip always render fully
            g2.setClip(savedClip);

            // --- X-axis date labels ------------------------------------------
            // Use HH:mm for intraday data and MM/dd for multi-day data.
            boolean isIntradayData =
                    (mainChartData.timestamps[dataPointCount - 1] - mainChartData.timestamps[0])
                    < 2 * 86400L;
            SimpleDateFormat xAxisDateFormat =
                    new SimpleDateFormat(isIntradayData ? "HH:mm" : "MM/dd");
            int labelCount = Math.min(6, dataPointCount);
            g2.setColor(MUTED_TEXT);
            g2.setFont(CAPTION_FONT);
            fontMetrics = g2.getFontMetrics();
            for (int i = 0; i < labelCount; i++) {
                int dataIdx = (labelCount == 1) ? 0 : (dataPointCount - 1) * i / (labelCount - 1);
                String dateLabel = xAxisDateFormat.format(
                        new Date(mainChartData.timestamps[dataIdx] * 1000L));
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
                g2.setColor(PRICE_LINE_COLOR);
                g2.fillOval(crosshairX - 4, crosshairY - 4, 8, 8);
                g2.setColor(CARD_BACKGROUND);
                g2.fillOval(crosshairX - 2, crosshairY - 2, 4, 4);

                // Tooltip text content
                boolean isIntraday = isIntradayData;
                SimpleDateFormat tooltipDateFormat =
                        new SimpleDateFormat(isIntraday ? "MMM dd, HH:mm" : "MMM dd, yyyy");
                String tooltipDate   = tooltipDateFormat.format(
                        new Date(mainChartData.timestamps[hoveredDataIndex] * 1000L));
                String tooltipPrice  = isComparisonMode
                        ? String.format("%+.2f%%", normalizedMain[hoveredDataIndex])
                        : String.format("$%.2f",   mainChartData.prices[hoveredDataIndex]);
                String tooltipVolume = "Vol: " + formatVolumeForTooltip(
                        mainChartData.volumes[hoveredDataIndex]);
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
                g2.setColor(BORDER_COLOR);
                g2.setStroke(new BasicStroke(1f));
                g2.drawRoundRect(tooltipX, tooltipY, tooltipWidth, tooltipHeight, 8, 8);

                // Draw the tooltip text lines
                int lineHeight = fontMetrics.getHeight();
                g2.setColor(PRIMARY_TEXT);
                g2.drawString(tooltipPrice,  tooltipX + 10, tooltipY + lineHeight);
                g2.setColor(MUTED_TEXT);
                g2.drawString(tooltipDate,   tooltipX + 10, tooltipY + lineHeight * 2 + 2);
                g2.drawString(tooltipVolume, tooltipX + 10, tooltipY + lineHeight * 3 + 4);
                if (tooltipCompLine != null) {
                    g2.setColor(COMPARISON_LINE_COLOR);
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
            g2.setColor(new Color(MUTED_TEXT.getRed(), MUTED_TEXT.getGreen(),
                    MUTED_TEXT.getBlue(), 60));
            g2.setStroke(new BasicStroke(1f));
            g2.drawLine(leftPadding, panelTop, leftPadding + chartWidth, panelTop);

            // Dark background to visually separate RSI from the chart above it
            g2.setColor(new Color(20, 20, 38));
            g2.fillRect(leftPadding, panelTop + 1, chartWidth, panelHeight - 1);

            // Overbought (70) and oversold (30) horizontal reference lines
            int y70 = panelTop + (int) (panelHeight * (100 - 70) / 100.0);
            int y30 = panelTop + (int) (panelHeight * (100 - 30) / 100.0);
            float[] dashPattern = {3f, 3f};
            g2.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_BEVEL, 0, dashPattern, 0));
            g2.setColor(new Color(LOSS_COLOR.getRed(), LOSS_COLOR.getGreen(), LOSS_COLOR.getBlue(), 80));
            g2.drawLine(leftPadding, y70, leftPadding + chartWidth, y70);
            g2.setColor(new Color(GAIN_COLOR.getRed(), GAIN_COLOR.getGreen(), GAIN_COLOR.getBlue(), 80));
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
            g2.setColor(MUTED_TEXT);
            g2.setStroke(new BasicStroke(1f));
            g2.drawString("RSI", leftPadding - fontMetrics.stringWidth("RSI") - 5,
                    panelTop + fontMetrics.getAscent());
            g2.drawString("70",  leftPadding - fontMetrics.stringWidth("70")  - 5,
                    y70 + fontMetrics.getAscent() / 2);
            g2.drawString("30",  leftPadding - fontMetrics.stringWidth("30")  - 5,
                    y30 + fontMetrics.getAscent() / 2);
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
            g2.setColor(BORDER_COLOR);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRoundRect(boxX, boxY, boxWidth, boxHeight, 6, 6);

            int lineHeight = fontMetrics.getHeight();

            // Main ticker row (price line colour swatch)
            g2.setColor(PRICE_LINE_COLOR);
            g2.fillRect(boxX + 8, boxY + lineHeight - fontMetrics.getAscent() + 2, 8, 8);
            g2.setColor(PRIMARY_TEXT);
            g2.drawString(mainLegendText, boxX + 20, boxY + lineHeight);

            // Comparison ticker row (yellow swatch)
            g2.setColor(COMPARISON_LINE_COLOR);
            g2.fillRect(boxX + 8, boxY + lineHeight * 2 - fontMetrics.getAscent() + 4, 8, 8);
            g2.setColor(PRIMARY_TEXT);
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
