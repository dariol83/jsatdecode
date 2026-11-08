package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.ui.ApplicationIcon;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.LockState;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.Quality;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.StatusRow;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.List;
import java.util.Locale;

/* All construction, model access, display mutation and disposal are EDT-only.
   No method holds a live source/decoder/tap reference or computes signal data. */
final class MonitoringWindow extends JFrame {
    private final StatusModel status = new StatusModel();
    private final JProgressBar quality = new JProgressBar(0, 100);
    private final JLabel snr = new JLabel("SNR N/A");
    private final JLabel carrierLock = new JLabel(), symbolLock = new JLabel(), frameLock = new JLabel();
    private final JLabel confidence = new JLabel("Unconfirmed SNR estimate");
    private final JLabel footer = new JLabel("Waiting for observations");
    private final ConstellationPanel constellation;
    private final SpectrumPanel rf, baseband;
    private java.util.function.IntConsumer smoothingChanged = level -> {
    };

    void onRfSmoothing(java.util.function.IntConsumer listener) {
        requireEdt();
        smoothingChanged = listener;
    }

    MonitoringWindow(UiSettings settings, String title, Runnable onClose) {
        this(settings, SpectrumSettings.read(new eu.dariolucia.jsatdecode.api.config.Configuration(java.util.Map.of())), title, onClose, w -> {
        }, w -> {
        });
    }

    MonitoringWindow(UiSettings settings, SpectrumSettings spectra, String title, Runnable onClose,
                     java.util.function.IntConsumer rfWidth, java.util.function.IntConsumer baseWidth) {
        this(settings, spectra, ConstellationSettings.read(new eu.dariolucia.jsatdecode.api.config.Configuration(java.util.Map.of())), title, onClose, rfWidth, baseWidth);
    }

    MonitoringWindow(UiSettings settings, SpectrumSettings spectra, ConstellationSettings symbols, String title, Runnable onClose,
                     java.util.function.IntConsumer rfWidth, java.util.function.IntConsumer baseWidth) {
        super(title);
        requireEdt();
        ApplicationIcon.DECODE.applyTo(this);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                onClose.run();
                dispose();
            }
        });
        JPanel content = new JPanel(new BorderLayout(12, 12));
        content.setBorder(new EmptyBorder(16, 16, 12, 16));
        quality.setStringPainted(true);
        quality.setString("Unavailable");
        quality.setPreferredSize(new Dimension(500, 32));
        quality.getAccessibleContext().setAccessibleName("Signal quality percentage");
        quality.setToolTipText(String.format(Locale.ROOT, "Display profile (dB): %.1f / %.1f / %.1f / %.1f / %.1f",
                settings.qualityProfile().unusableSnrDb(), settings.qualityProfile().poorSnrDb(), settings.qualityProfile().fairSnrDb(),
                settings.qualityProfile().goodSnrDb(), settings.qualityProfile().excellentSnrDb()));
        // Calculate preferred width for SNR label based on "SNR -XX.X dB" + 8px margin
        FontMetrics fm = snr.getFontMetrics(snr.getFont());
        int width = fm.stringWidth("SNR -XX.X dB") + 8;
        snr.setPreferredSize(new Dimension(width, snr.getPreferredSize().height));
        snr.setHorizontalAlignment(SwingConstants.LEFT);
        updateLock(carrierLock, "Carrier", LockState.UNKNOWN);
        updateLock(symbolLock, "Symbol", LockState.UNKNOWN);
        updateLock(frameLock, "Frame", LockState.UNKNOWN);
        
        // Create a panel for the quality bar and SNR label
        JPanel reading = new JPanel(new BorderLayout(12, 4));
        reading.add(quality, BorderLayout.CENTER);
        reading.add(snr, BorderLayout.EAST);
        
        // Create a panel for the lock indicators
        JPanel lockIndicators = new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 0));
        lockIndicators.add(carrierLock);
        lockIndicators.add(symbolLock);
        lockIndicators.add(frameLock);
        
        // Panel for reading and lock indicators on the same line with vertical centering
        // Use GridBagLayout to ensure both panels have the same height and are vertically centered
        JPanel readingAndLocks = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        readingAndLocks.add(reading, gbc);
        gbc.gridx = 1;
        gbc.weightx = 0;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.WEST;
        readingAndLocks.add(lockIndicators, gbc);
        
        // Top panel containing the quality bar, SNR, and lock indicators
        JPanel topPanel = new JPanel(new BorderLayout(8, 6));
        topPanel.add(readingAndLocks, BorderLayout.CENTER);
        
        // Add confidence label below
        JPanel northPanel = new JPanel(new BorderLayout(8, 6));
        northPanel.add(topPanel, BorderLayout.CENTER);
        northPanel.add(confidence, BorderLayout.SOUTH);
        
        content.add(northPanel, BorderLayout.NORTH);
        JTable table = new JTable(status);
        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(true);
        table.getAccessibleContext().setAccessibleName("Source and decoder monitoring metrics");
        table.setRowHeight(20);
        table.setIntercellSpacing(new Dimension(1, 1));
        table.getColumnModel().getColumn(0).setPreferredWidth(230);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(60);
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createTitledBorder("Status"));
        JPanel plots = new JPanel(new GridLayout(2, 1, 8, 8));
        rf = new SpectrumPanel("RF spectrum", spectra, rfWidth);
        baseband = new SpectrumPanel("Baseband spectrum", spectra, baseWidth);
        JPanel rfContainer = new JPanel(new BorderLayout());
        rfContainer.add(rf, BorderLayout.CENTER);
        rfContainer.add(rfControls(), BorderLayout.SOUTH);
        plots.add(rfContainer);
        constellation = new ConstellationPanel(symbols);
        JPanel lowerPlots = new JPanel(new GridLayout(1, 2, 8, 8));
        lowerPlots.add(baseband);
        lowerPlots.add(constellation);
        plots.add(lowerPlots);
        scroll.setMinimumSize(new Dimension(0, 0));
        plots.setMinimumSize(new Dimension(0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scroll, plots);
        split.setResizeWeight(1.0 / 3.0);
        // Apply the proportion after layout, including the initial maximisation.
        // Resize weight alone distributes only changes from preferred widths.
        split.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                split.setDividerLocation(1.0 / 3.0);
            }
        });
        content.add(split, BorderLayout.CENTER);
        content.add(footer, BorderLayout.SOUTH);
        setContentPane(content);
        setMinimumSize(new Dimension(640, 480));
        setSize(settings.width(), settings.height());
        setLocationByPlatform(true);
        setExtendedState(getExtendedState() | JFrame.MAXIMIZED_BOTH);
    }

    private JPanel rfControls() {
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        JToggleButton automatic = new JToggleButton("Auto range", true);
        JSlider minimum = new JSlider(-200, 100, -150), maximum = new JSlider(-200, 100, 0);
        minimum.setPreferredSize(new Dimension(110, 24));
        maximum.setPreferredSize(new Dimension(110, 24));
        minimum.getAccessibleContext().setAccessibleName("RF minimum dB");
        maximum.getAccessibleContext().setAccessibleName("RF maximum dB");
        JLabel minLabel = new JLabel(), maxLabel = new JLabel();
        Runnable update = () -> {
            minimum.setEnabled(!automatic.isSelected());
            maximum.setEnabled(!automatic.isSelected());
            minLabel.setText("Min " + minimum.getValue() + " dB");
            maxLabel.setText("Max " + maximum.getValue() + " dB");
            rf.range(automatic.isSelected(), minimum.getValue(), maximum.getValue());
        };
        minimum.addChangeListener(e -> {
            if (minimum.getValue() >= maximum.getValue()) minimum.setValue(maximum.getValue() - 1);
            update.run();
        });
        maximum.addChangeListener(e -> {
            if (maximum.getValue() <= minimum.getValue()) maximum.setValue(minimum.getValue() + 1);
            update.run();
        });
        automatic.addActionListener(e -> update.run());
        update.run();
        JComboBox<String> smoothing = new JComboBox<>(new String[]{"Lowest", "Low", "Medium", "High", "Highest"});
        smoothing.setSelectedIndex(SpectrumSettings.DEFAULT_RF_SMOOTHING_LEVEL);
        smoothing.getAccessibleContext().setAccessibleName("RF FFT smoothing");
        smoothing.setToolTipText("Each level doubles averaging: 1x, 2x, 4x, 8x, 16x");
        smoothing.addActionListener(e -> smoothingChanged.accept(smoothing.getSelectedIndex()));
        controls.add(automatic);
        controls.add(minLabel);
        controls.add(minimum);
        controls.add(maxLabel);
        controls.add(maximum);
        controls.add(new JLabel("FFT smoothing"));
        controls.add(smoothing);
        return controls;
    }

    void apply(UiViewModel model) {
        requireEdt();
        status.update(model.rows());
        Quality value = model.quality();
        quality.setValue(value.snrValid() ? (int) Math.round(value.percent()) : 0);
        quality.setString(value.snrValid() ? String.format(Locale.ROOT, "%.0f %%", value.percent()) : "Unavailable");
        quality.setForeground(value.snrValid() ? new Color(30, 128, 110) : Color.GRAY);
        snr.setText(value.snrValid() ? String.format(Locale.ROOT, "SNR %.1f dB", value.snrDb()) : "SNR N/A");
        updateLock(carrierLock, "Carrier", value.carrier());
        updateLock(symbolLock, "Symbol", value.symbol());
        updateLock(frameLock, "Frame", value.frame());
        String state = value.carrier() != LockState.LOCKED ? "Unconfirmed SNR estimate" : value.symbol() != LockState.LOCKED ?
                "Carrier acquired" : value.frame() != LockState.LOCKED ? "Symbols acquired; frame unconfirmed" : "Frame lock reported";
        confidence.setText(state);
        if (model.spectra().size() == 2) {
            rf.apply(model.spectra().get(0));
            baseband.apply(model.spectra().get(1));
        }
        if (model.constellation() != null) constellation.apply(model.constellation());
        footer.setText("Observing " + model.rows().size() + " metrics" + (model.omittedMetrics() > 0 ? "  |  " + model.omittedMetrics() + " additional metrics omitted" : "") +
                "  |  Closing this window leaves decoding running");
    }

    String qualityText() {
        requireEdt();
        return quality.getString();
    }

    String locksText() {
        requireEdt();
        return carrierLock.getToolTipText() + " | " + symbolLock.getToolTipText() + " | " + frameLock.getToolTipText();
    }

    int rowCount() {
        requireEdt();
        return status.getRowCount();
    }

    SpectrumFrame rfFrame() {
        return rf.frame();
    }

    SpectrumFrame basebandFrame() {
        return baseband.frame();
    }

    ConstellationFrame constellationFrame() {
        return constellation.frame();
    }

    static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Swing view access requires EDT");
    }

    private static void updateLock(JLabel label, String name, LockState state) {
        label.setText(name);
        label.setIcon(switch (state) {
            case UNLOCKED -> LockLed.RED;
            case SEARCHING -> LockLed.YELLOW;
            case LOCKED -> LockLed.GREEN;
            case UNKNOWN -> LockLed.GRAY;
        });
        label.setToolTipText(name + " lock: " + state);
        label.getAccessibleContext().setAccessibleName(name + " lock: " + state);
    }

    private enum LockLed implements Icon {
        RED(Color.RED), YELLOW(Color.YELLOW), GREEN(Color.GREEN), GRAY(Color.GRAY);
        private final Color color;

        LockLed(Color color) {
            this.color = color;
        }

        @Override
        public int getIconWidth() {
            return 18;
        }

        @Override
        public int getIconHeight() {
            return 18;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                g.fillOval(x + 1, y + 1, 15, 15);
                g.setColor(Color.DARK_GRAY);
                g.drawOval(x + 1, y + 1, 15, 15);
            } finally {
                g.dispose();
            }
        }
    }

    private static final class StatusModel extends AbstractTableModel {
        private List<StatusRow> rows = List.of();

        void update(List<StatusRow> value) {
            requireEdt();
            if (rows != value) {
                rows = value;
                fireTableDataChanged();
            }
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Metric";
                case 1 -> "Value";
                default -> "Unit";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            StatusRow value = rows.get(row);
            return switch (column) {
                case 0 -> value.metric();
                case 1 -> value.value();
                default -> value.unit();
            };
        }
    }
}
