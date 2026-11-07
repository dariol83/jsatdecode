package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumFrame;
import eu.dariolucia.jsatdecode.ui.swing.SpectrumSettings;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Optional graphical smoke test; uses synthetic reduced spectra and no radio. */
public final class InspectorWindowDesktopTest {
    private InspectorWindowDesktopTest() { }

    /**
     * Exercises settings, connection/recording controls, live FFT changes, sidebar,
     * actual painting and close with an unfinished edit. Requires a desktop.
     *
     * @param args optional screenshot output path
     * @throws Exception if interaction, rendering or screenshot output fails
     */
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Desktop smoke requires a graphical display");
        AtomicReference<InspectorWindow> view = new AtomicReference<>();
        AtomicReference<BufferedImage> capture = new AtomicReference<>();
        FakeActions actions = new FakeActions();
        try {
            SwingUtilities.invokeAndWait(() -> {
                try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
                catch (Exception e) { throw new AssertionError(e); }
                InspectorSettings defaults = InspectorSettings.defaults();
                InspectorWindow window = new InspectorWindow(defaults, actions);
                view.set(window);
                window.setVisible(true);
                check(window.settings().equals(defaults), "Initial settings did not round-trip");
                JSpinner rxBuffer = labeled(window, "RX buffer (samples)", JSpinner.class);
                SpinnerNumberModel rxModel = (SpinnerNumberModel) rxBuffer.getModel();
                check(rxBuffer.getValue().equals(16384) && rxModel.getMinimum().equals(1)
                                && rxModel.getMaximum().equals(1048576), "RX buffer sample default or bounds failed");
                AbstractButton testMode = button(window, "Test mode");
                check(testMode.isEnabled() && testMode.getY() > button(window, "Connect").getY(),
                        "Test mode is not available below Connect while disconnected");
                testMode.doClick();
                check(actions.tests == 1, "Test mode action missing");
                window.setTesting(true);
                check(!testMode.isEnabled() && !button(window, "Connect").isEnabled()
                                && !button(window, "Hide settings").isEnabled()
                                && !labeled(window, "IP address", JTextField.class).isEnabled()
                                && !labeled(window, "Min dB", JSpinner.class).isEnabled()
                                && !labeled(window, "Format", JComboBox.class).isEnabled(),
                        "Inspector controls remain active during test mode");
                window.setTesting(false);
                check(testMode.isEnabled() && labeled(window, "IP address", JTextField.class).isEnabled(),
                        "Inspector controls did not recover after test mode");
                JSpinner waterfallRate = labeled(window, "Lines per second", JSpinner.class);
                SpinnerNumberModel waterfallModel = (SpinnerNumberModel) waterfallRate.getModel();
                check(waterfallRate.getValue().equals(4) && waterfallModel.getMinimum().equals(1)
                        && waterfallModel.getMaximum().equals(15), "Waterfall default or bounds failed");
                check(SwingUtilities.convertPoint(waterfallRate, 0, 0, window).x > window.getWidth() / 2,
                        "Waterfall settings are not on the right");
                check(labeled(window, "Format", JComboBox.class).getSelectedItem() == RecordingFormat.CF32,
                        "Default recording format failed");
                labeled(window, "Tuning (MHz)", JSpinner.class).setValue(137.25);
                labeled(window, "RF bandwidth", JComboBox.class).setSelectedItem(1_000_000);
                labeled(window, "Sample rate", JComboBox.class).setSelectedItem(2_000_000);
                labeled(window, "Decimation", JComboBox.class).setSelectedItem(4);
                labeled(window, "Manual gain (dB)", JSpinner.class).setValue(55);
                rxBuffer.setValue(4097);
                labeled(window, "Storage folder", JTextField.class).setText(Path.of("target", "inspector-recordings").toAbsolutePath().toString());
                button(window, "Connect").doClick();
                check(actions.connections == 1, "Connection action missing");
                check(actions.connectedSettings.frequencyHz() == 137_250_000, "MHz conversion failed");
                check(actions.connectedSettings.effectiveSampleRate() == 500_000, "Decimation settings failed");
                check(actions.connectedSettings.gain() == 55, "Gain settings failed");
                check(actions.connectedSettings.rxBufferSamples() == 4097, "RX buffer sample count changed before connection");
                check(!labeled(window, "IP address", JTextField.class).isEnabled(), "Hardware edits enabled during connection");
                window.setConnection(true, false);
                check(!testMode.isEnabled(), "Test mode enabled during normal radio reception");
                check(!labeled(window, "Tuning (MHz)", JSpinner.class).isEnabled(), "Hardware edits enabled while connected");
                check(labeled(window, "Min dB", JSpinner.class).isEnabled(), "FFT edits disabled while connected");
                labeled(window, "Min dB", JSpinner.class).setValue(-100.0);
                labeled(window, "Max dB", JSpinner.class).setValue(40.0);
                labeled(window, "Type", JComboBox.class).setSelectedItem(WindowFunction.HAMMING);
                labeled(window, "Rate (Hz)", JSpinner.class).setValue(7.5);
                labeled(window, "Samples", JComboBox.class).setSelectedItem(131072);
                labeled(window, "Averaging", JComboBox.class).setSelectedItem("Highest");
                waterfallRate.setValue(15);
                labeled(window, "Format", JComboBox.class).setSelectedItem(RecordingFormat.CS16);
            });
            check(actions.fftChanged.await(3, TimeUnit.SECONDS), "Live FFT change was not delivered");
            SwingUtilities.invokeAndWait(() -> {
                InspectorWindow window = view.get();
                InspectorSettings settings = window.settings();
                check(actions.fftSettings.fftSize() == 131072 && actions.fftSettings.averaging() == 4,
                        "FFT size or averaging settings failed");
                check(actions.fftSettings.window() == WindowFunction.HAMMING && actions.fftSettings.fftRate() == 7.5,
                        "FFT type or rate settings failed");
                check(actions.fftSettings.waterfallRate() == 15, "Live waterfall rate change failed");
                button(window, "Start storage").doClick();
                check(actions.recordings == 1, "Recording action missing");
                check(actions.recordedSettings.storageFormat() == RecordingFormat.CS16, "Recording format was not submitted");
                check(!labeled(window, "Storage folder", JTextField.class).isEnabled(), "Folder edits enabled during recording start");
                check(!labeled(window, "Format", JComboBox.class).isEnabled(), "Format edits enabled during recording start");
                window.setRecording(true, false);
                check(!labeled(window, "Format", JComboBox.class).isEnabled(), "Format edits enabled during recording");
                check(labeled(window, "Lines per second", JSpinner.class).isEnabled(), "Waterfall edits disabled during recording");
                button(window, "Stop storage").doClick();
                check(actions.stops == 1, "Stop recording action missing");
                window.setRecording(false, false);
                check(labeled(window, "Storage folder", JTextField.class).isEnabled(), "Folder did not unlock after recording");
                check(labeled(window, "Format", JComboBox.class).isEnabled(), "Format did not unlock after recording");
                button(window, "Disconnect").doClick();
                check(actions.disconnections == 1, "Disconnect action missing");
                window.setConnection(false, false);
                check(labeled(window, "IP address", JTextField.class).isEnabled(), "Hardware edits did not unlock after disconnect");
                button(window, "Connect").doClick();
                window.setConnection(true, false);
                button(window, "Hide settings").doClick();
                check(window.settings().sidebarCollapsed(), "Sidebar did not collapse");
                button(window, "Show settings").doClick();
                check(!window.settings().sidebarCollapsed(), "Sidebar did not expand");
                window.validate();
                window.clearPlots();
                List<SpectrumFrame> rows = syntheticFrames(settings);
                window.display(rows.get(rows.size() - 1), rows);
                window.setStatus("Synthetic desktop verification | 500000 samples/s | 500000 Hz span");
                BufferedImage image = new BufferedImage(window.getWidth(), window.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics();
                try { window.paintAll(graphics); }
                finally { graphics.dispose(); }
                capture.set(image);
                JSpinner frequency = labeled(window, "Tuning (MHz)", JSpinner.class);
                ((JSpinner.DefaultEditor) frequency.getEditor()).getTextField().setText("unfinished");
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                check(actions.closedSettings != null, "Unfinished edit prevented application close");
                check(actions.closedSettings.frequencyHz() == settings.frequencyHz(), "Close lost the last valid settings");
                check(actions.closedSettings.rxBufferSamples() == 4097, "Close converted or lost the RX sample count");
                check(actions.closedSettings.waterfallRate() == 15 && actions.closedSettings.storageFormat() == RecordingFormat.CS16,
                        "Close lost waterfall or recording format settings");
            });
            if (args.length > 0) {
                Path output = Path.of(args[0]).toAbsolutePath();
                Files.createDirectories(output.getParent());
                ImageIO.write(capture.get(), "png", output.toFile());
            }
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                if (view.get() != null) view.get().dispose();
            });
        }
        System.out.println("Inspector desktop smoke passed: settings, hardware/recording controls, live FFT, sidebar, rendering and close.");
    }

    private static List<SpectrumFrame> syntheticFrames(InspectorSettings settings) {
        SpectrumSettings spectrum = new SpectrumSettings(settings.fftSize(), settings.window(), settings.fftRate(),
                settings.fftRate(), 1, settings.minDb(), settings.maxDb(), 1024, settings.frequencyHz());
        ArrayList<SpectrumFrame> rows = new ArrayList<>();
        for (int second = 0; second < 200; second++) {
            double[] db = new double[1024];
            for (int column = 0; column < db.length; column++) {
                double carrier = 110 * Math.exp(-Math.pow((column - 512.0) / 3.5, 2));
                double left = 75 * Math.exp(-Math.pow((column - 310.0 - 0.09 * second) / 7, 2));
                double right = 68 * Math.exp(-Math.pow((column - 725.0 + 0.07 * second) / 14, 2));
                double noise = 4 * Math.sin(column * 0.78 + second * 2.1) + 3 * Math.cos(column * 1.91 + second);
                db[column] = -88 + noise + carrier + left + right;
            }
            rows.add(new SpectrumFrame("Spectrum", SpectrumFrame.State.READY, spectrum, settings.effectiveSampleRate(),
                    settings.frequencyHz(), true, 1_800_000_000_000_000_000L + second * 1_000_000_000L, db));
        }
        return rows;
    }

    private static <T extends Component> T labeled(Container root, String label, Class<T> type) {
        for (Component component : descendants(root)) {
            if (component instanceof JLabel text && text.getText().equals(label) && type.isInstance(text.getLabelFor()))
                return type.cast(text.getLabelFor());
        }
        throw new AssertionError("Missing field: " + label);
    }

    private static AbstractButton button(Container root, String text) {
        for (Component component : descendants(root))
            if (component instanceof AbstractButton button && text.equals(button.getText())) return button;
        throw new AssertionError("Missing button: " + text);
    }

    private static List<Component> descendants(Container root) {
        ArrayList<Component> result = new ArrayList<>();
        for (Component component : root.getComponents()) {
            result.add(component);
            if (component instanceof Container container) result.addAll(descendants(container));
        }
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class FakeActions implements InspectorWindow.Actions {
        final CountDownLatch fftChanged = new CountDownLatch(1);
        int connections, disconnections, recordings, stops, tests;
        InspectorSettings connectedSettings, fftSettings, recordedSettings, closedSettings;

        @Override
        public void connect(InspectorSettings settings) { connections++; connectedSettings = settings; }
        @Override
        public void disconnect() { disconnections++; }
        @Override
        public void testMode(InspectorSettings settings) { tests++; }
        @Override
        public void startRecording(InspectorSettings settings) { recordings++; recordedSettings = settings; }
        @Override
        public void stopRecording() { stops++; }
        @Override
        public void changeFft(InspectorSettings settings) { fftSettings = settings; fftChanged.countDown(); }
        @Override
        public void close(InspectorSettings settings) { closedSettings = settings; }
    }
}
