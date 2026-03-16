import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.text.NumberFormat;
import java.util.Locale;

public class Main {

    // ---- Colours & fonts -----------------------------------------------------
    private static final Color BG_DARK      = new Color(18, 18, 28);
    private static final Color BG_CARD      = new Color(28, 28, 44);
    private static final Color ACCENT       = new Color(99, 179, 237);
    private static final Color TEXT_PRIMARY = new Color(240, 240, 255);
    private static final Color TEXT_MUTED   = new Color(140, 140, 170);
    private static final Color GREEN        = new Color(72, 199, 142);
    private static final Color RED          = new Color(252, 100, 100);
    private static final Color BORDER_COLOR = new Color(50, 50, 75);

    private static final Font FONT_TITLE  = new Font("Segoe UI", Font.BOLD, 22);
    private static final Font FONT_LABEL  = new Font("Segoe UI", Font.PLAIN, 13);
    private static final Font FONT_VALUE  = new Font("Segoe UI", Font.BOLD, 15);
    private static final Font FONT_PRICE  = new Font("Segoe UI", Font.BOLD, 32);
    private static final Font FONT_SMALL  = new Font("Segoe UI", Font.PLAIN, 11);
    private static final Font FONT_MONO   = new Font("Consolas", Font.PLAIN, 13);

    // ---- UI components -------------------------------------------------------
    private JFrame frame;
    private JTextField tickerField;
    private JButton fetchButton;
    private JPanel resultsPanel;
    private JLabel statusLabel;

    // Dynamic labels
    private JLabel lblCompanyName, lblPrice, lblChange, lblExchange;
    private JLabel lblMarketCap, lblVolume, lblPeRatio;
    private JLabel lblWeekHigh, lblWeekLow;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new Main().buildUI());
    }

    private void buildUI() {
        frame = new JFrame("Stock Analyzer — NASDAQ & NYSE");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(620, 680);
        frame.setMinimumSize(new Dimension(520, 580));
        frame.setLocationRelativeTo(null);
        frame.getContentPane().setBackground(BG_DARK);

        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBackground(BG_DARK);
        root.setBorder(new EmptyBorder(24, 28, 24, 28));

        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildSearchBar(), BorderLayout.CENTER);

        resultsPanel = buildResultsPanel();
        resultsPanel.setVisible(false);

        JPanel south = new JPanel(new BorderLayout());
        south.setBackground(BG_DARK);
        south.add(resultsPanel, BorderLayout.CENTER);

        statusLabel = new JLabel(" ");
        statusLabel.setFont(FONT_SMALL);
        statusLabel.setForeground(TEXT_MUTED);
        statusLabel.setHorizontalAlignment(SwingConstants.CENTER);
        south.add(statusLabel, BorderLayout.SOUTH);

        root.add(south, BorderLayout.SOUTH);
        frame.setContentPane(root);
        frame.setVisible(true);
    }

    // ---- Header --------------------------------------------------------------

    private JPanel buildHeader() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(0, 0, 20, 0));

        JLabel title = new JLabel("Stock Analyzer");
        title.setFont(FONT_TITLE);
        title.setForeground(ACCENT);

        JLabel sub = new JLabel("Real-time data · NASDAQ & NYSE");
        sub.setFont(FONT_SMALL);
        sub.setForeground(TEXT_MUTED);

        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setBackground(BG_DARK);
        text.add(title);
        text.add(sub);

        panel.add(text, BorderLayout.WEST);
        return panel;
    }

    // ---- Search bar ----------------------------------------------------------

    private JPanel buildSearchBar() {
        JPanel panel = new JPanel(new BorderLayout(10, 0));
        panel.setBackground(BG_DARK);
        panel.setBorder(new EmptyBorder(0, 0, 18, 0));

        tickerField = new JTextField();
        tickerField.setFont(FONT_MONO);
        tickerField.setBackground(BG_CARD);
        tickerField.setForeground(TEXT_PRIMARY);
        tickerField.setCaretColor(ACCENT);
        tickerField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(10, 14, 10, 14)));
        tickerField.putClientProperty("JTextField.placeholderText", "Enter ticker, e.g. AAPL");

        fetchButton = new JButton("Analyze");
        fetchButton.setFont(new Font("Segoe UI", Font.BOLD, 13));
        fetchButton.setBackground(ACCENT);
        fetchButton.setForeground(new Color(10, 20, 40));
        fetchButton.setBorder(new EmptyBorder(10, 22, 10, 22));
        fetchButton.setFocusPainted(false);
        fetchButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        fetchButton.addActionListener(e -> triggerFetch());
        tickerField.addActionListener(e -> triggerFetch());

        panel.add(tickerField, BorderLayout.CENTER);
        panel.add(fetchButton, BorderLayout.EAST);
        return panel;
    }

    // ---- Results panel -------------------------------------------------------

    private JPanel buildResultsPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(BG_DARK);

        // Hero card — company + price
        JPanel heroCard = card();
        heroCard.setLayout(new BorderLayout(0, 6));

        JPanel nameRow = new JPanel(new BorderLayout());
        nameRow.setBackground(BG_CARD);
        lblCompanyName = styledLabel("—", FONT_VALUE, TEXT_PRIMARY);
        lblExchange     = styledLabel("—", FONT_SMALL, TEXT_MUTED);
        nameRow.add(lblCompanyName, BorderLayout.CENTER);
        nameRow.add(lblExchange,    BorderLayout.EAST);

        JPanel priceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        priceRow.setBackground(BG_CARD);
        lblPrice  = styledLabel("—", FONT_PRICE, TEXT_PRIMARY);
        lblChange = styledLabel("", FONT_LABEL, GREEN);
        lblChange.setBorder(new EmptyBorder(10, 12, 0, 0));
        priceRow.add(lblPrice);
        priceRow.add(lblChange);

        heroCard.add(nameRow, BorderLayout.NORTH);
        heroCard.add(priceRow, BorderLayout.CENTER);
        panel.add(heroCard);
        panel.add(Box.createVerticalStrut(12));

        // Stats grid
        JPanel grid = new JPanel(new GridLayout(2, 3, 12, 12));
        grid.setBackground(BG_DARK);

        lblMarketCap = styledLabel("—", FONT_VALUE, TEXT_PRIMARY);
        lblVolume    = styledLabel("—", FONT_VALUE, TEXT_PRIMARY);
        lblPeRatio   = styledLabel("—", FONT_VALUE, TEXT_PRIMARY);
        lblWeekHigh  = styledLabel("—", FONT_VALUE, GREEN);
        lblWeekLow   = styledLabel("—", FONT_VALUE, RED);

        grid.add(statCard("Market Cap",     lblMarketCap));
        grid.add(statCard("Volume",         lblVolume));
        grid.add(statCard("P/E Ratio",      lblPeRatio));
        grid.add(statCard("52-Week High",   lblWeekHigh));
        grid.add(statCard("52-Week Low",    lblWeekLow));
        grid.add(new JPanel() {{ setBackground(BG_DARK); }}); // spacer

        panel.add(grid);
        return panel;
    }

    private JPanel card() {
        JPanel p = new JPanel();
        p.setBackground(BG_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(16, 18, 16, 18)));
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JPanel statCard(String labelText, JLabel valueLabel) {
        JPanel p = new JPanel(new GridLayout(2, 1, 0, 4));
        p.setBackground(BG_CARD);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR, 1, true),
                new EmptyBorder(14, 16, 14, 16)));

        JLabel lbl = styledLabel(labelText, FONT_LABEL, TEXT_MUTED);
        p.add(lbl);
        p.add(valueLabel);
        return p;
    }

    private JLabel styledLabel(String text, Font font, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(font);
        l.setForeground(color);
        return l;
    }

    // ---- Fetch logic ---------------------------------------------------------

    private void triggerFetch() {
        String ticker = tickerField.getText().trim().toUpperCase();
        if (ticker.isEmpty()) {
            setStatus("Please enter a ticker symbol.", RED);
            return;
        }

        fetchButton.setEnabled(false);
        tickerField.setEnabled(false);
        setStatus("Fetching data for " + ticker + "…", TEXT_MUTED);
        resultsPanel.setVisible(false);

        SwingWorker<StockData, Void> worker = new SwingWorker<>() {
            @Override
            protected StockData doInBackground() throws Exception {
                return YahooFinanceFetcher.fetch(ticker);
            }

            @Override
            protected void done() {
                fetchButton.setEnabled(true);
                tickerField.setEnabled(true);
                try {
                    StockData data = get();
                    populateResults(data);
                    setStatus("Last updated: " + new java.util.Date(), TEXT_MUTED);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    setStatus("Error: " + cause.getMessage(), RED);
                }
            }
        };
        worker.execute();
    }

    private void populateResults(StockData d) {
        lblCompanyName.setText(d.companyName);
        lblExchange.setText(d.exchange != null ? "  " + d.exchange : "");

        String cur = d.currency != null ? d.currency : "USD";
        lblPrice.setText(formatPrice(d.currentPrice, cur));

        String sign = d.change >= 0 ? "+" : "";
        String changeText = String.format("%s%.2f  (%s%.2f%%)", sign, d.change, sign, d.changePercent);
        lblChange.setText(changeText);
        lblChange.setForeground(d.change >= 0 ? GREEN : RED);

        lblMarketCap.setText(formatLargeNumber(d.marketCap));
        lblVolume.setText(formatVolume(d.volume));
        lblPeRatio.setText(Double.isNaN(d.peRatio) || d.peRatio == 0 ? "N/A" : String.format("%.2f", d.peRatio));
        lblWeekHigh.setText(formatPrice(d.fiftyTwoWeekHigh, cur));
        lblWeekLow.setText(formatPrice(d.fiftyTwoWeekLow, cur));

        resultsPanel.setVisible(true);
        frame.revalidate();
    }

    private String formatPrice(double val, String currency) {
        if (val == 0) return "N/A";
        return String.format("%s %.2f", currency, val);
    }

    private String formatLargeNumber(double val) {
        if (val == 0) return "N/A";
        if (val >= 1_000_000_000_000.0) return String.format("$%.2fT", val / 1_000_000_000_000.0);
        if (val >= 1_000_000_000.0)     return String.format("$%.2fB", val / 1_000_000_000.0);
        if (val >= 1_000_000.0)         return String.format("$%.2fM", val / 1_000_000.0);
        return NumberFormat.getCurrencyInstance(Locale.US).format(val);
    }

    private String formatVolume(long vol) {
        if (vol == 0) return "N/A";
        if (vol >= 1_000_000) return String.format("%.2fM", vol / 1_000_000.0);
        if (vol >= 1_000)     return String.format("%.1fK", vol / 1_000.0);
        return String.valueOf(vol);
    }

    private void setStatus(String msg, Color color) {
        statusLabel.setText(msg);
        statusLabel.setForeground(color);
    }
}
