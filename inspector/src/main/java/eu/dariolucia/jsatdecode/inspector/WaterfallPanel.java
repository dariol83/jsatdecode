package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumPanel;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.event.MouseEvent;
import java.text.DecimalFormat;
import java.util.Locale;

/* EDT-owned bounded raster history. The newest completed row is at
   the top; resizing scales the raster without allocating any sample history. */
final class WaterfallPanel extends JPanel {
    private static final int HISTORY_ROWS = 1024;
    private static final int TOP = 24, BOTTOM = 8;
    private static final int[] PALETTE = palette();
    private final long[] timestamps = new long[HISTORY_ROWS];
    private final double[] centersHz = new double[HISTORY_ROWS], sampleRates = new double[HISTORY_ROWS];
    private final boolean[] absoluteFrequencies = new boolean[HISTORY_ROWS];
    private final DecimalFormat seconds = new DecimalFormat("0.#");
    private BufferedImage history;
    private int rows, nextTimestamp;
    private double minimum = Double.NaN, maximum = Double.NaN;

    WaterfallPanel() {
        requireEdt();
        setBorder(BorderFactory.createTitledBorder("Waterfall"));
        setBackground(new Color(246, 248, 250));
        getAccessibleContext().setAccessibleName("RF waterfall, newest observations at top");
        ToolTipManager.sharedInstance().registerComponent(this);
    }

    void range(double minDb, double maxDb) {
        requireEdt();
        if (!Double.isFinite(minDb) || !Double.isFinite(maxDb) || minDb >= maxDb)
            throw new IllegalArgumentException("Invalid waterfall range");
        minimum = minDb;
        maximum = maxDb;
    }

    void append(SpectrumFrame frame) {
        requireEdt();
        if (frame == null || frame.state() != SpectrumFrame.State.READY || frame.columns() == 0) return;
        int width = Math.min(4096, frame.columns());
        if (history == null || history.getWidth() != width) {
            BufferedImage next = new BufferedImage(width, HISTORY_ROWS, BufferedImage.TYPE_INT_RGB);
            if (history != null) {
                Graphics2D graphics = next.createGraphics();
                try {
                    graphics.drawImage(history, 0, 0, width, HISTORY_ROWS, null);
                } finally {
                    graphics.dispose();
                }
            }
            history = next;
        }
        Graphics2D graphics = history.createGraphics();
        try {
            graphics.copyArea(0, 0, width, Math.min(rows, HISTORY_ROWS - 1), 0, 1);
        } finally {
            graphics.dispose();
        }
        double low = Double.isNaN(minimum) ? frame.floorDb() : minimum;
        double high = Double.isNaN(maximum) ? frame.ceilingDb() : maximum;
        for (int x = 0; x < width; x++) {
            double value = frame.dbAt(x * frame.columns() / width);
            int index = Double.isFinite(value) ? (int) Math.round(255 * Math.max(0, Math.min(1, (value - low) / (high - low)))) : 0;
            history.setRGB(x, 0, PALETTE[index]);
        }
        timestamps[nextTimestamp] = frame.timestampNanos();
        centersHz[nextTimestamp] = frame.centerHz();
        sampleRates[nextTimestamp] = frame.sampleRate();
        absoluteFrequencies[nextTimestamp] = frame.absoluteFrequency();
        nextTimestamp = (nextTimestamp + 1) % HISTORY_ROWS;
        rows = Math.min(HISTORY_ROWS, rows + 1);
        repaint();
    }

    void clear() {
        requireEdt();
        history = null;
        rows = 0;
        nextTimestamp = 0;
        repaint();
    }

    // Relative source-observation age of the bottom visible row. Timestamp
    // history follows the raster through column resizing and cadence changes.
    double visibleAgeSeconds() {
        requireEdt();
        int visible = Math.min(rows, Math.max(0, getHeight() - TOP - BOTTOM));
        if (visible <= 1) return 0;
        int newest = (nextTimestamp + HISTORY_ROWS - 1) % HISTORY_ROWS;
        int oldest = (newest + HISTORY_ROWS - visible + 1) % HISTORY_ROWS;
        return Math.max(0, (timestamps[newest] - timestamps[oldest]) / 1_000_000_000.0);
    }

    /**
     * Reads the hovered historical row's frequency axis on the EDT.
     * @param event component-relative cursor position, or null
     * @return absolute MHz and signed offset kHz, or null outside valid row data
     * @throws IllegalStateException when called outside the EDT
     */
    @Override
    public String getToolTipText(MouseEvent event) {
        requireEdt();
        int width = getWidth() - SpectrumPanel.LEFT - SpectrumPanel.RIGHT;
        int visible = Math.min(rows, Math.max(0, getHeight() - TOP - BOTTOM));
        if (event == null || width <= 0 || event.getX() < SpectrumPanel.LEFT
                || event.getX() >= SpectrumPanel.LEFT + width
                || event.getY() < TOP || event.getY() >= TOP + visible) return null;
        int row = (nextTimestamp + HISTORY_ROWS - 1 - (event.getY() - TOP)) % HISTORY_ROWS;
        double center = centersHz[row], rate = sampleRates[row];
        if (!absoluteFrequencies[row] || !Double.isFinite(center) || !Double.isFinite(rate) || rate <= 0) return null;
        double offset = rate * ((event.getX() - SpectrumPanel.LEFT) / (double) width - 0.5);
        return String.format(Locale.ROOT, "Frequency: %.6f MHz | Offset from center: %+.3f kHz",
                (center + offset) / 1_000_000, offset / 1000);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        int width = getWidth() - SpectrumPanel.LEFT - SpectrumPanel.RIGHT;
        int height = getHeight() - TOP - BOTTOM;
        if (width <= 0 || height <= 0) return;
        graphics.setColor(new Color(PALETTE[0]));
        graphics.fillRect(SpectrumPanel.LEFT, TOP, width, height);
        if (history != null && rows > 0) {
            int visible = Math.min(rows, height);
            graphics.drawImage(history, SpectrumPanel.LEFT, TOP, SpectrumPanel.LEFT + width, TOP + visible,
                    0, 0, history.getWidth(), visible, null);
        }
        graphics.setColor(Color.DARK_GRAY);
        drawAge(graphics, "0 s", TOP + 11);
        if (rows > 1) {
            double age = visibleAgeSeconds();
            String label = age == 0 ? "0 s" : "−" + (age < 1 ? Math.round(age * 1000) + " ms" : seconds.format(age) + " s");
            drawAge(graphics, label, TOP + Math.min(rows, height));
        }
    }

    private static void drawAge(Graphics graphics, String label, int baseline) {
        graphics.drawString(label, Math.max(0, SpectrumPanel.LEFT - 5 - graphics.getFontMetrics().stringWidth(label)), baseline);
    }

    private static int[] palette() {
        int[] colors = new int[256];
        Color[] stops = {new Color(3, 6, 24), new Color(18, 37, 112), new Color(0, 170, 202),
                new Color(244, 231, 72), new Color(240, 62, 25), Color.WHITE};
        for (int i = 0; i < colors.length; i++) {
            double position = i * (stops.length - 1) / 255.0;
            int from = Math.min(stops.length - 2, (int) position);
            double fraction = position - from;
            Color a = stops[from], b = stops[from + 1];
            colors[i] = new Color((int) Math.round(a.getRed() + (b.getRed() - a.getRed()) * fraction),
                    (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * fraction),
                    (int) Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * fraction)).getRGB();
        }
        return colors;
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Waterfall rendering requires the EDT");
    }
}
