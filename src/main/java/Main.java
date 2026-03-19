import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
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
    private static final String[][] CHART_INTERVALS = {
        {"1D",  "5m",  "1d" },
        {"5D",  "15m", "5d" },
        {"1M",  "1d",  "1mo"},
        {"3M",  "1d",  "3mo"},
        {"6M",  "1d",  "6mo"},
        {"1Y",  "1d",  "1y" },
    };

    /** Index into {@link #CHART_INTERVALS} shown by default when a ticker loads. */
    private static final int DEFAULT_INTERVAL_INDEX = 2; // 1M

    // =========================================================================
    // UI component references
    // =========================================================================

    // --- Top-level window and layout ------------------------------------------

    private JFrame mainWindow;
    private JPanel resultsPanel;   // hidden until a stock is successfully loaded
    private JLabel statusLabel;    // status bar at the bottom of the window

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

    private WatchlistManager watchlistManager;        // handles persistence
    private JPanel           watchlistItemsContainer; // BoxLayout panel rebuilt on each refresh
    private javax.swing.Timer watchlistRefreshTimer;  // fires every 60 s to update prices

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
        // Load the persisted watchlist before building the UI so the sidebar
        // is immediately populated when the window opens.
        watchlistManager = new WatchlistManager();
        watchlistManager.load();

        mainWindow = new JFrame("Stock Analyzer \u2014 NASDAQ & NYSE");
        mainWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        mainWindow.setMinimumSize(new Dimension(760, 600));

        // Root panel uses BorderLayout:
        //   NORTH  — header + search bar (always visible, never scrolls away)
        //   CENTER — JScrollPane wrapping the results panel + status bar
        //   EAST   — watchlist sidebar
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.setBackground(DARK_BACKGROUND);

        // Top bar: header and search bar stacked, with padding
        JPanel topBar = new JPanel(new BorderLayout());
        topBar.setBackground(DARK_BACKGROUND);
        topBar.setBorder(new EmptyBorder(24, 28, 0, 8));
        topBar.add(buildHeaderPanel(),    BorderLayout.NORTH);
        topBar.add(buildSearchBarPanel(), BorderLayout.CENTER);
        rootPanel.add(topBar, BorderLayout.NORTH);

        // Results panel sits inside a scroll pane so all content is reachable
        // regardless of window size. The status bar is pinned below the scroll area.
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

        JPanel centerContainer = new JPanel(new BorderLayout());
        centerContainer.setBackground(DARK_BACKGROUND);
        centerContainer.setBorder(new EmptyBorder(0, 28, 0, 8));
        centerContainer.add(resultsScrollPane, BorderLayout.CENTER);
        centerContainer.add(statusLabel,       BorderLayout.SOUTH);
        rootPanel.add(centerContainer,         BorderLayout.CENTER);

        rootPanel.add(buildWatchlistSidebar(), BorderLayout.EAST);

        mainWindow.setContentPane(rootPanel);
        // Start maximized so all content is visible without manual resizing
        mainWindow.setExtendedState(JFrame.MAXIMIZED_BOTH);
        mainWindow.setVisible(true);

        // Start the background price-refresh timer.  Fires every 60 seconds and
        // silently updates prices for all watchlist entries.
        watchlistRefreshTimer = new javax.swing.Timer(60_000,
                e -> refreshWatchlistPricesInBackground());
        watchlistRefreshTimer.start();

        // Kick off an immediate refresh so prices show straight away on startup.
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
        analyzeButton.setBorder(new EmptyBorder(10, 22, 10, 22));
        analyzeButton.setFocusPainted(false);
        analyzeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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

        // Remove button ("×") — removes this ticker from the watchlist
        JButton removeButton = new JButton("\u00D7");
        removeButton.setFont(CAPTION_FONT);
        removeButton.setForeground(MUTED_TEXT);
        removeButton.setBackground(CARD_BACKGROUND);
        removeButton.setBorder(new EmptyBorder(2, 6, 2, 2));
        removeButton.setFocusPainted(false);
        removeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        removeButton.addActionListener(e -> {
            watchlistManager.remove(entry.ticker);
            rebuildWatchlistRows();
        });

        row.add(tickerLabel,      BorderLayout.WEST);
        row.add(priceChangeStack, BorderLayout.CENTER);
        row.add(removeButton,     BorderLayout.EAST);

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
                // Called on the EDT after each publish() — update the sidebar incrementally
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
        card.add(makeLabel(metricName, CAPTION_FONT, MUTED_TEXT));
        card.add(valueLabel);
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

    /** Creates an "action" button (Watchlist, Export CSV) with a subtle bordered style. */
    private JButton makeActionButton(String label) {
        JButton btn = new JButton(label);
        btn.setFont(CAPTION_FONT);
        btn.setBackground(new Color(40, 40, 65));
        btn.setForeground(ACCENT_COLOR);
        btn.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(5, 12, 5, 12)));
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
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
                    chartPanel.setChartData(get());
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

        /** Line colour for the 20-period MA overlay. */
        private static final Color MA20_LINE_COLOR = new Color(255, 165,   0); // orange
        /** Line colour for the 50-period MA overlay. */
        private static final Color MA50_LINE_COLOR = new Color(180, 100, 255); // purple
        /** Line colour for the comparison series. */
        private static final Color COMPARISON_LINE_COLOR = new Color(255, 200,  50); // yellow

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

        /** Installs new chart data and triggers a repaint. */
        void setChartData(ChartData data) {
            mainChartData = data;
            placeholderMessage = null;
            hoveredDataIndex = -1;
            repaint();
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
                        new Color(ACCENT_COLOR.getRed(), ACCENT_COLOR.getGreen(), ACCENT_COLOR.getBlue(), 60),
                        0, topPadding + priceAreaHeight,
                        new Color(ACCENT_COLOR.getRed(), ACCENT_COLOR.getGreen(), ACCENT_COLOR.getBlue(), 0)));
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

            // --- Main price line --------------------------------------------
            g2.setColor(ACCENT_COLOR);
            g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int i = 0; i < dataPointCount - 1; i++) {
                g2.drawLine(xPositions[i], yPositions[i], xPositions[i + 1], yPositions[i + 1]);
            }

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
                g2.setColor(ACCENT_COLOR);
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

            // Dark background to visually separate RSI from the chart above it
            g2.setColor(new Color(20, 20, 38));
            g2.fillRect(leftPadding, panelTop, chartWidth, panelHeight);

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

            // Main ticker row (accent blue swatch)
            g2.setColor(ACCENT_COLOR);
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
