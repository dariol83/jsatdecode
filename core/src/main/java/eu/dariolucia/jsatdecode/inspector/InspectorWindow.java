package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;
import eu.dariolucia.jsatdecode.api.source.DiagnosticSampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;
import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;
import eu.dariolucia.jsatdecode.ui.ApplicationIcon;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumPanel;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumSettings;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;

/* EDT-owned application view. Actions only hand requests to the controller. */
final class InspectorWindow extends JFrame {
    interface Actions {
        void connect(InspectorSettings settings);
        void disconnect();
        void testMode(InspectorSettings settings);
        void startRecording(InspectorSettings settings);
        void stopRecording();
        void changeFft(InspectorSettings settings);
        default void updateSource(InspectorSettings settings) { }
        void close(InspectorSettings settings);
    }

    private final Actions actions;
    private final Map<String, SampleSourceFactory> sourceFactories;
    private final Map<String, InspectorSourceSettings> sourceDrafts = new LinkedHashMap<>();
    private final JComboBox<SourceDescriptor> sourceSelector = new JComboBox<>();
    private final JComboBox<Integer> decimation = new JComboBox<>(new Integer[]{1, 2, 4, 8, 16, 32, 64});
    private final JPanel sourcePropertiesContainer = new JPanel(new BorderLayout());
    private final JButton applySource = new JButton("Apply source changes");
    private final InspectorSourceSettings legacySelection;
    private SourceConfigurationPanel sourceProperties;
    private String selectedSourceId;
    private final JSpinner minimumDb;
    private final JSpinner maximumDb;
    private final JComboBox<WindowFunction> window;
    private final JSpinner fftRate;
    private final JComboBox<Integer> fftSize;
    private final JComboBox<String> averaging;
    private final JSpinner waterfallRate;
    private final JTextField storageFolder;
    private final JComboBox<RecordingFormat> storageFormat;
    private final JButton browse = new JButton("Browse…");
    private final JToggleButton connection = new JToggleButton("Connect");
    private final JButton testMode = new JButton("Test mode");
    private final JToggleButton recording = new JToggleButton("Start storage");
    private final JToggleButton sidebarToggle = new JToggleButton("Hide settings");
    private final JLabel effectiveRate = new JLabel();
    private final JLabel status = new JLabel("Disconnected");
    private final JPanel sidebarContainer = new JPanel(new BorderLayout());
    private final List<JComponent> fftControls = new ArrayList<>();
    private final SpectrumPanel spectrum;
    private final WaterfallPanel waterfall = new WaterfallPanel();
    private final Timer fftChanges;
    private final JSplitPane horizontalSplit;
    private InspectorSettings lastValidSettings;
    private boolean connected;
    private boolean connectionBusy;
    private boolean storing;
    private boolean recordingBusy;
    private boolean closing;
    private boolean collecting;
    private boolean testing;
    private boolean sourceUpdating;
    private int sidebarWidth = 325;
    private volatile int columns = 2048;

    InspectorWindow(InspectorSettings initial, Actions actions) {
        this(initial, actions, InspectorSources.available());
    }

    InspectorWindow(InspectorSettings initial, Actions actions, Map<String, SampleSourceFactory> factories) {
        super("JSatInspector");
        requireEdt();
        ApplicationIcon.INSPECTOR.applyTo(this);
        this.actions = Objects.requireNonNull(actions);
        lastValidSettings = Objects.requireNonNull(initial);
        sourceFactories = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(factories));
        InspectorSourceSettings selection = InspectorSources.selection(initial);
        legacySelection = initial.source() == null ? selection : null;
        sourceFactories.values().forEach(factory -> sourceSelector.addItem(factory.descriptor()));
        sourceSelector.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object item, int index, boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, item instanceof SourceDescriptor descriptor ? descriptor.name() : item,
                        index, selected, focused);
            }
        });
        sourceSelector.setMinimumSize(new Dimension(40, sourceSelector.getPreferredSize().height));
        String initialId = sourceFactories.containsKey(selection.id()) ? selection.id()
                : sourceFactories.containsKey("fmcomms2") ? "fmcomms2" : sourceFactories.keySet().stream().findFirst().orElse(null);
        if (initialId != null) {
            sourceSelector.setSelectedItem(sourceFactories.get(initialId).descriptor());
            showSource(initialId, initialId.equals(selection.id()) ? selection : new InspectorSourceSettings(initialId, Map.of(), 1));
        }
        decimation.setToolTipText("Software decimation after acquisition; source hardware decimation is configured separately.");
        minimumDb = decimalSpinner(initial.minDb(), -1000, 1000, 5, "0.0");
        maximumDb = decimalSpinner(initial.maxDb(), -1000, 1000, 5, "0.0");
        window = new JComboBox<>(WindowFunction.values());
        window.setSelectedItem(initial.window());
        window.setToolTipText("FFT window function; spectrum levels are relative dB.");
        fftRate = decimalSpinner(initial.fftRate(), 0.5, 30, 0.5, "0.0");
        fftSize = new JComboBox<>(new Integer[]{1024, 2048, 4096, 8192, 16384, 32768, 65536, 131072});
        fftSize.setSelectedItem(initial.fftSize());
        averaging = new JComboBox<>(new String[]{"None", "Low", "Medium", "High", "Highest"});
        averaging.setSelectedIndex(initial.averaging());
        waterfallRate = integerSpinner(initial.waterfallRate(), 1, 15, 1);
        waterfallRate.setToolTipText("Waterfall lines per second, independent of the spectrum update rate.");
        storageFolder = new JTextField(initial.storageFolder().toString(), 16);
        storageFolder.setToolTipText("Folder for IQ recordings (.cf32 or .cs16).");
        storageFormat = new JComboBox<>(RecordingFormat.values());
        storageFormat.setSelectedItem(initial.storageFormat());
        storageFormat.setToolTipText("Little-endian interleaved IQ: CF32 float32 or CS16 signed 16-bit samples.");
        spectrum = new SpectrumPanel("Spectrum", new SpectrumSettings(initial.fftSize(), initial.window(),
                initial.fftRate(), initial.fftRate(), 1, initial.minDb(), initial.maxDb(), 4096,
                initial.centerFrequency()), value -> columns = Math.max(1, Math.min(4096, value)));
        ToolTipManager.sharedInstance().registerComponent(spectrum);
        fftChanges = new Timer(180, event -> applyFftChanges());
        fftChanges.setRepeats(false);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        setMinimumSize(new Dimension(800, 600));
        setSize(initial.windowWidth(), initial.windowHeight());
        setLocationByPlatform(true);

        JPanel controls = new SettingsPanel();
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        controls.setBorder(new EmptyBorder(4, 4, 4, 4));
        controls.add(sdrGroup());
        controls.add(Box.createVerticalStrut(5));
        controls.add(fftGroup());
        controls.add(Box.createVerticalStrut(5));
        controls.add(waterfallGroup());
        controls.add(Box.createVerticalStrut(5));
        controls.add(storageGroup());
        controls.add(Box.createVerticalGlue());
        JScrollPane scroll = new JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(18);
        sidebarContainer.add(scroll, BorderLayout.CENTER);
        sidebarContainer.setMinimumSize(new Dimension(290, 0));
        sidebarContainer.setPreferredSize(new Dimension(sidebarWidth, 600));

        JSplitPane plots = new JSplitPane(JSplitPane.VERTICAL_SPLIT, spectrum, waterfall);
        plots.setResizeWeight(0.5);
        plots.setContinuousLayout(true);
        plots.setBorder(BorderFactory.createEmptyBorder());
        spectrum.setMinimumSize(new Dimension(160, 170));
        waterfall.setMinimumSize(new Dimension(160, 150));
        horizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, plots, sidebarContainer);
        horizontalSplit.setDividerLocation(initial.windowWidth() - sidebarWidth);
        horizontalSplit.setResizeWeight(1);
        horizontalSplit.setContinuousLayout(true);
        horizontalSplit.setBorder(BorderFactory.createEmptyBorder());

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEADING, 6, 4));
        sidebarToggle.setSelected(!initial.sidebarCollapsed());
        sidebarToggle.setToolTipText("Show or hide source, FFT, waterfall and storage settings.");
        sidebarToggle.addActionListener(event -> updateSidebar());
        toolbar.add(sidebarToggle);
        JLabel information = new JLabel("Spectrum and IQ recorder");
        information.setBorder(new EmptyBorder(0, 7, 0, 0));
        toolbar.add(information);
        status.setBorder(new EmptyBorder(5, 10, 6, 8));
        getContentPane().setLayout(new BorderLayout());
        add(toolbar, BorderLayout.NORTH);
        add(horizontalSplit, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);

        connection.addActionListener(event -> connectionRequested());
        testMode.setToolTipText("Run PlutoPlus sample-drop tests using this IP address while disconnected.");
        testMode.addActionListener(event -> testRequested());
        recording.addActionListener(event -> recordingRequested());
        browse.addActionListener(event -> chooseFolder());
        sourceSelector.addActionListener(event -> sourceChanged());
        decimation.addActionListener(event -> updateEffectiveRate());
        applySource.addActionListener(event -> {
            try { actions.updateSource(settings()); }
            catch (IllegalArgumentException e) { inputError(e); }
        });
        for (JComponent component : fftControls) {
            if (component instanceof JSpinner spinner) spinner.addChangeListener(event -> scheduleFftChange());
            else if (component instanceof JComboBox<?> combo) combo.addActionListener(event -> scheduleFftChange());
        }
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                closeRequested();
            }
        });
        updateSidebar();
        updateEffectiveRate();
        applyRanges(initial);
        updateEnabled();
        if (initialId != null && !initialId.equals(selection.id()))
            setStatus("Source " + selection.id() + " is unavailable; selected " + initialId);
    }

    InspectorSettings settings() {
        requireEdt();
        collecting = true;
        try {
            commit(minimumDb, "minimum dB");
            commit(maximumDb, "maximum dB");
            commit(fftRate, "FFT rate");
            commit(waterfallRate, "waterfall rate");
            String folder = storageFolder.getText().strip();
            if (folder.isEmpty()) throw new IllegalArgumentException("Choose a storage folder");
            if (sourceProperties != null) sourceProperties.validateValues();
            InspectorSettings v = lastValidSettings;
            InspectorSourceSettings selection = sourceSettings();
            InspectorSettings result = new InspectorSettings(v.ipAddress(), v.frequencyHz(),
                    v.bandwidth(), v.sampleRate(), v.decimation(), v.gain(), v.rxBufferSamples(),
                    number(minimumDb), number(maximumDb), (WindowFunction) window.getSelectedItem(), number(fftRate),
                    selected(fftSize), averaging.getSelectedIndex(), integer(waterfallRate), Path.of(folder),
                    (RecordingFormat) storageFormat.getSelectedItem(), !sidebarToggle.isSelected(),
                    getWidth(), getHeight(), selection.equals(legacySelection) ? null : selection);
            lastValidSettings = result;
            return result;
        } finally {
            collecting = false;
        }
    }

    void setConnection(boolean connected, boolean busy) {
        requireEdt();
        this.connected = connected;
        connectionBusy = busy;
        connection.setSelected(connected);
        connection.setText(busy ? connected ? "Disconnecting…" : "Connecting…" : connected ? "Disconnect" : "Connect");
        updateEnabled();
    }

    void setRecording(boolean recording, boolean busy) {
        requireEdt();
        storing = recording;
        recordingBusy = busy;
        this.recording.setSelected(recording);
        this.recording.setText(busy ? recording ? "Stopping storage…" : "Starting storage…" : recording ? "Stop storage" : "Start storage");
        updateEnabled();
    }

    void setTesting(boolean value) {
        requireEdt();
        testing = value;
        if (value) fftChanges.stop();
        updateEnabled();
    }

    void setStatus(String message) {
        requireEdt();
        status.setForeground(UIManager.getColor("Label.foreground"));
        status.setText(message == null || message.isBlank() ? " " : message);
        status.setToolTipText(status.getText());
    }

    void display(SpectrumFrame frame, List<SpectrumFrame> rows) {
        requireEdt();
        if (closing || testing) return;
        if (frame != null) spectrum.apply(frame);
        for (SpectrumFrame row : rows) waterfall.append(row);
    }

    void clearPlots() {
        requireEdt();
        spectrum.apply(null);
        waterfall.clear();
    }

    int spectrumColumns() {
        return columns;
    }

    private JPanel sdrGroup() {
        JPanel panel = group("Source settings");
        panel.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        row(panel, 0, "Source", sourceSelector);
        row(panel, 1, "Software decimation", decimation);
        effectiveRate.setBorder(new EmptyBorder(3, 0, 3, 0));
        fullRow(panel, 2, effectiveRate);
        fullRow(panel, 3, connection);
        fullRow(panel, 4, applySource);
        fullRow(panel, 5, testMode);
        fullRow(panel, 6, sourcePropertiesContainer);
        return panel;
    }

    void setSourceUpdating(boolean value) {
        requireEdt();
        sourceUpdating = value;
        updateEnabled();
    }

    private InspectorSourceSettings sourceSettings() {
        if (selectedSourceId == null) throw new IllegalArgumentException("No sample source is available");
        return new InspectorSourceSettings(selectedSourceId, sourceProperties.values(), selected(decimation));
    }

    private void sourceChanged() {
        if (selectedSourceId != null) sourceDrafts.put(selectedSourceId, sourceSettings());
        SourceDescriptor selected = (SourceDescriptor) sourceSelector.getSelectedItem();
        if (selected == null) return;
        showSource(selected.id(), sourceDrafts.getOrDefault(selected.id(), new InspectorSourceSettings(selected.id(), Map.of(), 1)));
        updateEffectiveRate();
        updateEnabled();
    }

    private void showSource(String id, InspectorSourceSettings selection) {
        selectedSourceId = id;
        SampleSourceFactory factory = sourceFactories.get(id);
        sourceProperties = new SourceConfigurationPanel(factory.configurationProperties(), selection.properties());
        sourceProperties.onChange(this::updateEffectiveRate);
        sourcePropertiesContainer.removeAll();
        sourcePropertiesContainer.add(sourceProperties, BorderLayout.CENTER);
        decimation.setSelectedItem(selection.softwareDecimation());
        testMode.setVisible(id.equals("fmcomms2") && factory instanceof DiagnosticSampleSourceFactory);
        sourcePropertiesContainer.revalidate();
        sourcePropertiesContainer.repaint();
    }

    private JPanel fftGroup() {
        JPanel panel = group("FFT settings");
        row(panel, 0, "Min dB", minimumDb);
        row(panel, 1, "Max dB", maximumDb);
        row(panel, 2, "Type", window);
        row(panel, 3, "Rate (Hz)", fftRate);
        row(panel, 4, "Samples", fftSize);
        row(panel, 5, "Averaging", averaging);
        fftControls.addAll(List.of(minimumDb, maximumDb, window, fftRate, fftSize, averaging));
        return panel;
    }

    private JPanel waterfallGroup() {
        JPanel panel = group("Waterfall settings");
        row(panel, 0, "Lines per second", waterfallRate);
        fftControls.add(waterfallRate);
        return panel;
    }

    private JPanel storageGroup() {
        JPanel panel = group("Storage settings");
        JLabel label = new JLabel("Storage folder");
        label.setLabelFor(storageFolder);
        fullRow(panel, 0, label);
        fullRow(panel, 1, storageFolder);
        fullRow(panel, 2, browse);
        row(panel, 3, "Format", storageFormat);
        fullRow(panel, 4, recording);
        return panel;
    }

    private void connectionRequested() {
        boolean requested = connection.isSelected();
        try {
            if (requested) {
                InspectorSettings value = settings();
                setConnection(false, true);
                actions.connect(value);
            } else {
                setConnection(true, true);
                actions.disconnect();
            }
        } catch (IllegalArgumentException e) {
            setConnection(connected, false);
            inputError(e);
        }
    }

    private void testRequested() {
        try {
            actions.testMode(settings());
        } catch (IllegalArgumentException e) {
            inputError(e);
        }
    }

    private void recordingRequested() {
        boolean requested = recording.isSelected();
        try {
            if (requested) {
                InspectorSettings value = settings();
                setRecording(false, true);
                actions.startRecording(value);
            } else {
                setRecording(true, true);
                actions.stopRecording();
            }
        } catch (IllegalArgumentException e) {
            setRecording(storing, false);
            inputError(e);
        }
    }

    private void closeRequested() {
        if (closing) return;
        InspectorSettings saved;
        try {
            saved = settings();
        } catch (IllegalArgumentException e) {
            // An unfinished edit must not trap the user in the application.
            InspectorSettings v = lastValidSettings;
            saved = new InspectorSettings(v.ipAddress(), v.frequencyHz(), v.bandwidth(), v.sampleRate(),
                    v.decimation(), v.gain(), v.rxBufferSamples(), v.minDb(), v.maxDb(), v.window(), v.fftRate(),
                    v.fftSize(), v.averaging(), v.waterfallRate(), v.storageFolder(), v.storageFormat(),
                    !sidebarToggle.isSelected(), getWidth(), getHeight(), v.source());
        }
        closing = true;
        fftChanges.stop();
        updateEnabled();
        setStatus("Closing…");
        actions.close(saved);
    }

    private void scheduleFftChange() {
        if (!collecting && !closing && !testing) fftChanges.restart();
    }

    private void applyFftChanges() {
        try {
            InspectorSettings value = settings();
            applyRanges(value);
            actions.changeFft(value);
        } catch (IllegalArgumentException e) {
            inputError(e);
        }
    }

    private void applyRanges(InspectorSettings value) {
        spectrum.range(false, value.minDb(), value.maxDb());
        waterfall.range(value.minDb(), value.maxDb());
    }

    private void chooseFolder() {
        Path folder = lastValidSettings.storageFolder();
        try {
            if (!storageFolder.getText().isBlank()) folder = Path.of(storageFolder.getText());
        } catch (IllegalArgumentException ignored) {
            // The chooser can replace a malformed manually entered path.
        }
        JFileChooser chooser = new JFileChooser(folder.toFile());
        chooser.setDialogTitle("Select recording folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setAcceptAllFileFilterUsed(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION)
            storageFolder.setText(chooser.getSelectedFile().toPath().toAbsolutePath().normalize().toString());
    }

    private void updateEnabled() {
        boolean hardwareEnabled = !connected && !connectionBusy && !closing && !testing;
        sourceSelector.setEnabled(hardwareEnabled);
        decimation.setEnabled(hardwareEnabled);
        if (sourceProperties != null) sourceProperties.updateEnabled(connected, connectionBusy || sourceUpdating || closing || testing);
        for (JComponent component : fftControls) component.setEnabled(!closing && !testing);
        connection.setEnabled(selectedSourceId != null && !connectionBusy && !closing && !testing);
        testMode.setEnabled(testMode.isVisible() && hardwareEnabled && !recordingBusy && !storing);
        boolean live = selectedSourceId != null && sourceFactories.get(selectedSourceId).configurationProperties().stream()
                .anyMatch(SourcePropertyDescriptor::runtimeUpdatable);
        applySource.setEnabled(live && connected && !connectionBusy && !sourceUpdating && !closing && !testing);
        recording.setEnabled(connected && !connectionBusy && !recordingBusy && !closing && !testing);
        boolean folderEnabled = !storing && !recordingBusy && !closing && !testing;
        storageFolder.setEnabled(folderEnabled);
        storageFormat.setEnabled(folderEnabled);
        browse.setEnabled(folderEnabled);
        sidebarToggle.setEnabled(!closing && !testing);
    }

    private void updateEffectiveRate() {
        if (selectedSourceId == null) { effectiveRate.setText("No sample sources available"); return; }
        try {
            Map<String, String> values = sourceProperties.values();
            String rateValue = values.get("sample_rate");
            if (rateValue == null) rateValue = sourceFactories.get(selectedSourceId).configurationProperties().stream()
                    .filter(p -> p.name().equals("sample_rate")).map(p -> p.defaultValue() == null ? "" : p.defaultValue()).findFirst().orElse("");
            if (rateValue.isBlank()) { effectiveRate.setText("Sample rate: from source"); return; }
            double rate = Double.parseDouble(rateValue) / Integer.parseInt(values.getOrDefault("fpga_decimation", "1")) / selected(decimation);
            effectiveRate.setText("Effective sample rate: " + rateLabel(rate, true));
            effectiveRate.setToolTipText("Spectrum and recording sample rate after software decimation; plotted span " + rateLabel(rate, false) + ".");
        } catch (IllegalArgumentException e) { effectiveRate.setText("Enter a valid source sample rate"); }
    }

    private void updateSidebar() {
        boolean visible = sidebarToggle.isSelected();
        if (!visible && sidebarContainer.isVisible() && sidebarContainer.getWidth() > 0)
            sidebarWidth = sidebarContainer.getWidth();
        sidebarContainer.setVisible(visible);
        horizontalSplit.setDividerSize(visible ? UIManager.getInt("SplitPane.dividerSize") : 0);
        int splitWidth = horizontalSplit.getWidth() > 0 ? horizontalSplit.getWidth() : getWidth();
        horizontalSplit.setDividerLocation(visible
                ? Math.max(0, splitWidth - horizontalSplit.getDividerSize() - sidebarWidth) : splitWidth);
        sidebarToggle.setText(visible ? "Hide settings" : "Show settings");
        horizontalSplit.revalidate();
    }

    private void inputError(IllegalArgumentException failure) {
        status.setForeground(new Color(165, 35, 35));
        status.setText(failure.getMessage());
        status.setToolTipText(failure.getMessage());
    }

    private static JPanel group(String title) {
        JPanel result = new JPanel(new GridBagLayout());
        result.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title), new EmptyBorder(3, 5, 5, 5)));
        result.setAlignmentX(Component.LEFT_ALIGNMENT);
        result.setMaximumSize(new Dimension(Integer.MAX_VALUE, 500));
        return result;
    }

    private static void row(JPanel panel, int row, String text, JComponent field) {
        JLabel label = new JLabel(text);
        label.setLabelFor(field);
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.anchor = GridBagConstraints.LINE_START;
        constraints.insets = new Insets(3, 1, 3, 9);
        panel.add(label, constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(3, 0, 3, 1);
        panel.add(field, constraints);
    }

    private static void fullRow(JPanel panel, int row, JComponent component) {
        GridBagConstraints constraints = new GridBagConstraints();
        constraints.gridx = 0;
        constraints.gridy = row;
        constraints.gridwidth = 2;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.insets = new Insets(3, 1, 3, 1);
        panel.add(component, constraints);
    }

    private static JSpinner integerSpinner(int value, int minimum, int maximum, int step) {
        JSpinner result = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        result.setEditor(new JSpinner.NumberEditor(result, "0"));
        return result;
    }

    private static JSpinner decimalSpinner(double value, double minimum, double maximum, double step, String format) {
        JSpinner result = new JSpinner(new SpinnerNumberModel(value, minimum, maximum, step));
        result.setEditor(new JSpinner.NumberEditor(result, format));
        return result;
    }

    private static String rateLabel(double rate, boolean samples) {
        boolean mega = rate >= 1_000_000;
        return BigDecimal.valueOf(rate).movePointLeft(mega ? 6 : 3).stripTrailingZeros().toPlainString()
                + (mega ? samples ? " Msps" : " MHz" : samples ? " ksps" : " kHz");
    }

    private static double number(JSpinner spinner) {
        return ((Number) spinner.getValue()).doubleValue();
    }

    private static int integer(JSpinner spinner) {
        return ((Number) spinner.getValue()).intValue();
    }

    private static int selected(JComboBox<Integer> combo) {
        return (Integer) Objects.requireNonNull(combo.getSelectedItem());
    }

    private static void commit(JSpinner spinner, String name) {
        try {
            spinner.commitEdit();
        } catch (ParseException e) {
            throw new IllegalArgumentException("Enter a valid " + name, e);
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Inspector window requires the Swing EDT");
    }

    /** Long provider names and choices must fit the sidebar instead of clipping controls. */
    private static final class SettingsPanel extends JPanel implements Scrollable {
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 18; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(18, visible.height - 18); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }
}
