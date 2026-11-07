package eu.dariolucia.jsatdecode.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * EDT-owned spectrum renderer accepting immutable reduced frames. Construction,
 * configuration and frame publication must run on the EDT; painting performs no
 * provider reads or analysis. Drawable-width notifications also run on the EDT.
 *
 * @serial exclude
 */
public final class SpectrumPanel extends JPanel {
    /** Left plot inset in pixels, shared by aligned companion plots. */
    public static final int LEFT = 64;
    /** Right plot inset in pixels, shared by aligned companion plots. */
    public static final int RIGHT = 16;
    static final int TOP = 28, BOTTOM = 38;
    private final Path2D.Double path;
    private SpectrumFrame frame;
    private boolean autoRange = true;
    private double minimumDb = -150, maximumDb = 0;

    void range(boolean automatic, int minimum, int maximum) {
        range(automatic, (double) minimum, maximum);
    }

    /**
     * Selects the frame's automatic range or explicit relative-dB limits.
     *
     * @param automatic whether to use the immutable frame range
     * @param minimum finite lower manual limit
     * @param maximum finite upper manual limit, greater than minimum
     * @throws IllegalArgumentException for invalid manual limits
     * @throws IllegalStateException when called outside the EDT
     */
    public void range(boolean automatic, double minimum, double maximum) {
        MonitoringWindow.requireEdt();
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum) || minimum >= maximum)
            throw new IllegalArgumentException("Invalid spectrum range");
        autoRange = automatic;
        minimumDb = minimum;
        maximumDb = maximum;
        repaint();
    }

    /**
     * Creates an empty plot and reports its drawable width after resizing.
     *
     * @param name visible plot title
     * @param settings initial bounded rendering settings
     * @param width EDT callback receiving nonnegative drawable pixel widths
     * @throws IllegalStateException when called outside the EDT
     */
    public SpectrumPanel(String name, SpectrumSettings settings, IntConsumer width) {
        MonitoringWindow.requireEdt();
        path = new Path2D.Double(Path2D.WIND_NON_ZERO, settings.maximumColumns());
        setBorder(BorderFactory.createTitledBorder(name));
        setBackground(new Color(246, 248, 250));
        getAccessibleContext().setAccessibleName(name + ", relative decibels");
        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                width.accept(Math.max(0, getWidth() - LEFT - RIGHT));
            }
        });
    }

    /**
     * Publishes an immutable frame, or null to show the waiting state. The frame
     * may be shared safely with analysis workers and other renderers.
     *
     * @param value frame to retain until the next publication
     * @throws IllegalStateException when called outside the EDT
     */
    public void apply(SpectrumFrame value) {
        MonitoringWindow.requireEdt();
        frame = value;
        repaint();
    }

    SpectrumFrame frame() {
        MonitoringWindow.requireEdt();
        return frame;
    }

    /**
     * Describes the cursor's frequency using the displayed frame and plot axis.
     * Swing shows these tooltips when the component is registered with
     * {@link ToolTipManager}; registration is left to the owning application.
     * This method reads only EDT-owned display state and performs no analysis.
     *
     * @param event component-relative cursor position, or null
     * @return absolute MHz and signed offset kHz, or null outside a ready plot
     *         with a known absolute center
     * @throws IllegalStateException when called outside the EDT
     */
    @Override
    public String getToolTipText(MouseEvent event) {
        MonitoringWindow.requireEdt();
        int width = getWidth() - LEFT - RIGHT;
        int height = getHeight() - TOP - BOTTOM;
        if (event == null || width <= 0 || height <= 0 || frame == null
                || frame.state() != SpectrumFrame.State.READY || frame.columns() == 0
                || !frame.absoluteFrequency() || !Double.isFinite(frame.centerHz())
                || !Double.isFinite(frame.sampleRate()) || frame.sampleRate() <= 0
                || event.getX() < LEFT || event.getX() > LEFT + width
                || event.getY() < TOP || event.getY() > TOP + height) return null;
        double offset = frame.sampleRate() * ((event.getX() - LEFT) / (double) width - 0.5);
        return String.format(Locale.ROOT, "Frequency: %.6f MHz | Offset from center: %+.3f kHz",
                (frame.centerHz() + offset) / 1_000_000, offset / 1000);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        int w = getWidth() - LEFT - RIGHT, h = getHeight() - TOP - BOTTOM;
        if (w <= 0 || h <= 0) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setFont(getFont().deriveFont(10f));
            g.setColor(Color.DARK_GRAY);
            if (frame == null || frame.state() != SpectrumFrame.State.READY) {
                g.drawString(frame == null ? "Waiting for observations" : switch (frame.state()) {
                    case UNAVAILABLE -> "Tap unavailable";
                    case INVALID -> "Invalid samples or frequency axis";
                    case ERROR -> "Spectrum disabled after observation failure";
                    default -> "Waiting for a complete FFT snapshot";
                }, LEFT, TOP + h / 2);
                return;
            }
            double low = autoRange ? frame.floorDb() : minimumDb, high = autoRange ? frame.ceilingDb() : maximumDb;
            for (int i = 0; i <= 4; i++) {
                int y = TOP + h * i / 4;
                g.setColor(new Color(215, 222, 228));
                g.drawLine(LEFT, y, LEFT + w, y);
                g.setColor(Color.DARK_GRAY);
                g.drawString(String.format(Locale.ROOT, "%.0f", high - (high - low) * i / 4), 20, y + 4);
            }
            double scale = frame.absoluteFrequency() && frame.centerHz() >= 1e6 ? 1e6 : frame.sampleRate() >= 2000 ? 1000 : 1;
            String unit = scale == 1e6 ? "MHz" : scale == 1000 ? "kHz" : "Hz";
            for (int i = 0; i <= 4; i++) {
                int x = LEFT + w * i / 4;
                g.setColor(new Color(215, 222, 228));
                g.drawLine(x, TOP, x, TOP + h);
                double f = (frame.centerHz() + frame.sampleRate() * (i / 4.0 - 0.5)) / scale;
                String label = String.format(Locale.ROOT, scale == 1e6 ? "%.3f" : "%.1f", f);
                int labelWidth = g.getFontMetrics().stringWidth(label);
                int labelX = Math.max(4, Math.min(getWidth() - 4 - labelWidth, x - labelWidth / 2));
                g.setColor(Color.DARK_GRAY);
                g.drawString(label, labelX, TOP + h + 14);
            }
            g.drawString("Relative dB", LEFT, TOP - 7);
            String detail = frame.fftSize() + " / " + frame.window();
            g.drawString(detail, LEFT + w - g.getFontMetrics().stringWidth(detail), TOP - 7);
            String axis = (frame.absoluteFrequency() ? "Frequency" : "Offset") + " (" + unit + ")";
            g.drawString(axis, LEFT + (w - g.getFontMetrics().stringWidth(axis)) / 2, getHeight() - 7);
            g.clipRect(LEFT, TOP, w + 1, h + 1);
            path.reset();
            for (int i = 0; i < frame.columns(); i++) {
                double x = LEFT + frame.binAtColumn(i) * w / frame.fftSize();
                double y = TOP + (high - Math.max(low, Math.min(high, frame.dbAt(i)))) * h / (high - low);
                if (i == 0) path.moveTo(x, y);
                else path.lineTo(x, y);
            }
            g.setColor(new Color(0, 118, 160));
            g.draw(path);
        } finally {
            g.dispose();
        }
    }
}
