package eu.dariolucia.jsatdecode.ui.swing;

import javax.swing.*;
import java.awt.*;
import java.util.Locale;

/* One EDT component, no per-symbol objects, analysis, provider access or history. */
final class ConstellationPanel extends JPanel {
    private static final Color[] INK = {new Color(170, 207, 220), new Color(110, 180, 203), new Color(50, 150, 180), new Color(0, 118, 160)};
    private final ConstellationSettings settings;
    private ConstellationFrame frame;

    ConstellationPanel(ConstellationSettings settings) {
        MonitoringWindow.requireEdt();
        this.settings = settings;
        setBorder(BorderFactory.createTitledBorder("Constellation"));
        setBackground(new Color(246, 248, 250));
        getAccessibleContext().setAccessibleName("Constellation: in-phase I horizontally, quadrature Q vertically");
    }

    void apply(ConstellationFrame value) {
        MonitoringWindow.requireEdt();
        frame = value;
        repaint();
    }

    ConstellationFrame frame() {
        MonitoringWindow.requireEdt();
        return frame;
    }

    static int side(int width, int height) {
        return Math.max(0, Math.min(width - 104, height - 56));
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        int side = side(getWidth(), getHeight());
        if (side <= 0) return;
        int left = (getWidth() - side) / 2, top = 24;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setFont(getFont().deriveFont(10f));
            g.setColor(Color.DARK_GRAY);
            if (frame == null || frame.state() != ConstellationFrame.State.READY) {
                g.drawString(frame == null ? "Waiting for symbols" : switch (frame.state()) {
                    case UNAVAILABLE -> "Symbols tap unavailable";
                    case INVALID -> "No finite symbols";
                    case ERROR -> "Constellation disabled after observation failure";
                    default -> "Waiting for symbols";
                }, 16, top + side / 2);
                return;
            }
            double extent = frame.extent();
            for (int i = 0; i <= 4; i++) {
                int d = side * i / 4;
                g.setColor(i == 2 ? Color.GRAY : new Color(215, 222, 228));
                g.drawLine(left + d, top, left + d, top + side);
                g.drawLine(left, top + d, left + side, top + d);
                g.setColor(Color.DARK_GRAY);
                String x = label(extent * (i / 2.0 - 1)), y = label(extent * (1 - i / 2.0));
                g.drawString(x, left + d - g.getFontMetrics().stringWidth(x) / 2, top + side + 13);
                g.drawString(y, left - 5 - g.getFontMetrics().stringWidth(y), top + d + 3);
            }
            g.drawString("I", left + side + 12, top + side / 2 + 3);
            g.drawString("Q", left + side / 2 - 3, top - 7);
            g.drawString(frame.points() + " points / " + (settings.autoScale() ? "auto" : "fixed"), 8, getHeight() - 6);
            g.clipRect(left, top, side + 1, side + 1);
            if (settings.reference() != ConstellationSettings.Reference.NONE) {
                g.setColor(new Color(160, 70, 45));
                double v = settings.reference() == ConstellationSettings.Reference.BPSK ? 1 : Math.sqrt(0.5);
                for (int x = -1; x <= 1; x += 2)
                    for (int y = -1; y <= 1; y += 2) {
                        double q = settings.reference() == ConstellationSettings.Reference.BPSK ? 0 : y * v;
                        if (v > extent || Math.abs(q) > extent) continue;
                        int px = left + (int) Math.round((x * v / extent + 1) * side / 2), py = top + (int) Math.round((1 - q / extent) * side / 2);
                        g.drawOval(px - 4, py - 4, 8, 8);
                    }
            }
            for (int i = 0; i < frame.points(); i++) {
                double x = frame.i(i) / extent, y = frame.q(i) / extent;
                if (Math.abs(x) > 1 || Math.abs(y) > 1) continue;
                int px = left + (int) Math.round((x + 1) * side / 2), py = top + (int) Math.round((1 - y) * side / 2);
                g.setColor(INK[(int) ((long) i * INK.length / frame.points())]);
                g.fillRect(px - 1, py - 1, 3, 3);
            }
        } finally {
            g.dispose();
        }
    }

    private static String label(double value) {
        return String.format(Locale.ROOT, Math.abs(value) != 0 && (Math.abs(value) < 0.01 || Math.abs(value) >= 10000) ? "%.1e" : "%.2f", value);
    }
}
