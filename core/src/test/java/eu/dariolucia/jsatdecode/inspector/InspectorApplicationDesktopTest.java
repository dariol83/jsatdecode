package eu.dariolucia.jsatdecode.inspector;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Optional real-application smoke test with preferences isolated in a child JVM. */
public final class InspectorApplicationDesktopTest {
    private InspectorApplicationDesktopTest() { }

    /**
     * Launches the actual application with a temporary home and AppData folder,
     * verifies restored settings and disconnected startup, then closes the window
     * and checks saved settings and normal process termination. Uses no radio.
     *
     * @param args {@code --child} is reserved for the isolated test process
     * @throws Exception on application, persistence or lifecycle failure
     */
    public static void main(String[] args) throws Exception {
        if (GraphicsEnvironment.isHeadless()) throw new IllegalStateException("Desktop smoke requires a graphical display");
        if (args.length == 1 && args[0].equals("--child")) {
            exerciseApplication();
            return;
        }
        Path target = Path.of(InspectorSettings.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        Files.createDirectories(target);
        Path isolatedHome = Files.createTempDirectory(target, "inspector-app-desktop-");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        Path settingsFile = isolatedHome.resolve(windows ? "JSatInspector" : ".jsatinspector").resolve("settings.properties");
        Files.createDirectories(settingsFile.getParent());
        Properties initial = new Properties();
        initial.setProperty("frequency_hz", "145125000");
        initial.setProperty("gain", "49");
        initial.setProperty("sample_rate", "2000000");
        initial.setProperty("decimation", "2");
        initial.setProperty("rx_buffer_bytes", "262144");
        initial.setProperty("waterfall_rate", "7");
        initial.setProperty("storage_format", "CS16");
        initial.setProperty("storage_folder", isolatedHome.resolve("recordings").toString());
        try (var out = Files.newBufferedWriter(settingsFile)) { initial.store(out, "Isolated desktop smoke input"); }
        String executable = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java").toString();
        ArrayList<String> command = new ArrayList<>(List.of(executable, "-Djava.awt.headless=false",
                "-Duser.home=" + isolatedHome, "-cp", System.getProperty("java.class.path"),
                InspectorApplicationDesktopTest.class.getName(), "--child"));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("APPDATA", isolatedHome.toString());
        Process child = builder.start();
        if (!child.waitFor(20, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            throw new AssertionError("JSatInspector did not terminate after window close");
        }
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        check(child.exitValue() == 0, "Isolated application failed:\n" + output);
        check(Files.isRegularFile(settingsFile), "Application did not save preferences");
        Properties saved = new Properties();
        try (var in = Files.newBufferedReader(settingsFile)) { saved.load(in); }
        check(Double.parseDouble(saved.getProperty("frequency_hz")) == 146_250_000, "Tuning frequency was not saved");
        check(saved.getProperty("gain").equals("57"), "Gain was not saved");
        check(saved.getProperty("rx_buffer_samples").equals("65535") && !saved.containsKey("rx_buffer_bytes"),
                "RX buffer was not saved as samples after migrating legacy bytes");
        check(saved.getProperty("fft_size").equals("32768"), "FFT settings were not saved");
        check(saved.getProperty("waterfall_rate").equals("12"), "Waterfall rate was not saved");
        check(saved.getProperty("storage_format").equals("CF32"), "Recording format was not saved");
        check(saved.getProperty("sidebar_collapsed").equals("true"), "Sidebar state was not saved");
        check(saved.getProperty("width").equals("1100") && saved.getProperty("height").equals("760"), "Window size was not saved");
        check(saved.getProperty("storage_folder").equals(isolatedHome.resolve("recordings").toString()), "Storage folder changed");
        System.out.println("Inspector application desktop smoke passed: actual startup, restored settings, disconnected state, saved settings and normal process exit.");
        System.out.println("Isolated preferences: " + settingsFile);
    }

    private static void exerciseApplication() throws Exception {
        AtomicReference<InspectorWindow> view = new AtomicReference<>();
        JSatInspector.main(new String[0]);
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            while (view.get() == null && System.nanoTime() < deadline) {
                SwingUtilities.invokeAndWait(() -> {
                    for (Window candidate : Window.getWindows())
                        if (candidate instanceof InspectorWindow window && window.isShowing()) view.set(window);
                });
                if (view.get() == null) Thread.sleep(25);
            }
            check(view.get() != null, "JSatInspector.main did not open its window");
            check(workerAlive("jsatinspector-management"), "Application management worker did not start");
            check(!workerAlive("jsatinspector-fft") && !workerAlive("jsatdecode-source-fmcomms2")
                    && !workerAlive("jsatinspector-cf32-writer") && !workerAlive("jsatinspector-cs16-writer"),
                    "Application started reception or recording automatically");
            SwingUtilities.invokeAndWait(() -> {
                InspectorWindow window = view.get();
                InspectorSettings settings = window.settings();
                check(settings.frequencyHz() == 145_125_000 && settings.gain() == 49
                        && settings.effectiveSampleRate() == 1_000_000, "Application did not restore preferences");
                check(settings.waterfallRate() == 7 && settings.storageFormat() == RecordingFormat.CS16,
                        "Application did not restore waterfall and recording format preferences");
                check(settings.rxBufferSamples() == 65536, "Application did not convert legacy RX bytes to samples");
                AbstractButton connect = button(window, "Connect");
                check(connect.isEnabled() && !connect.isSelected(), "Application did not start disconnected");
                check(!button(window, "Start storage").isEnabled(), "Storage enabled before connecting");
                labeled(window, "Tuning (MHz)", JSpinner.class).setValue(146.25);
                labeled(window, "Manual gain (dB)", JSpinner.class).setValue(57);
                labeled(window, "RX buffer (samples)", JSpinner.class).setValue(65535);
                labeled(window, "Samples", JComboBox.class).setSelectedItem(32768);
                labeled(window, "Lines per second", JSpinner.class).setValue(12);
                labeled(window, "Format", JComboBox.class).setSelectedItem(RecordingFormat.CF32);
                button(window, "Hide settings").doClick();
                window.setSize(1100, 760);
                window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
            });
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
            AtomicReference<Boolean> disposed = new AtomicReference<>(false);
            do {
                SwingUtilities.invokeAndWait(() -> disposed.set(!view.get().isDisplayable()));
                if (disposed.get() && !workerAlive("jsatinspector-management")) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            check(disposed.get() && !workerAlive("jsatinspector-management"), "Window close did not release application resources");
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                InspectorWindow window = view.get();
                if (window != null && window.isDisplayable())
                    window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
            });
        }
    }

    private static boolean workerAlive(String name) {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(thread -> thread.isAlive() && thread.getName().equals(name));
    }

    private static <T extends Component> T labeled(Container root, String label, Class<T> type) {
        for (Component component : descendants(root))
            if (component instanceof JLabel text && text.getText().equals(label) && type.isInstance(text.getLabelFor()))
                return type.cast(text.getLabelFor());
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

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
