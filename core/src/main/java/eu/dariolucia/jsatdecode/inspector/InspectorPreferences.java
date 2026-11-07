package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.io.IOException;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;

/* Management-thread persistence. A failed/corrupt preferences file cannot prevent startup. */
final class InspectorPreferences {
    private static final System.Logger LOG = System.getLogger(InspectorPreferences.class.getName());
    private final Path file;

    InspectorPreferences(Path file) {
        this.file = file;
    }

    static InspectorPreferences forUser() {
        Path home = Path.of(System.getProperty("user.home"));
        String appData = System.getenv("APPDATA");
        Path folder = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                && appData != null && !appData.isBlank() ? Path.of(appData).resolve("JSatInspector") : home.resolve(".jsatinspector");
        return new InspectorPreferences(folder.resolve("settings.properties"));
    }

    InspectorSettings load() {
        InspectorSettings d = InspectorSettings.defaults();
        if (!Files.exists(file)) return d;
        Properties p = new Properties();
        try (var in = Files.newBufferedReader(file)) {
            p.load(in);
            return new InspectorSettings(p.getProperty("ip", d.ipAddress()), number(p, "frequency_hz", d.frequencyHz()),
                    integer(p, "bandwidth", d.bandwidth()), integer(p, "sample_rate", d.sampleRate()),
                    integer(p, "decimation", d.decimation()), integer(p, "gain", d.gain()), rxBufferSamples(p, d.rxBufferSamples()),
                    number(p, "min_db", d.minDb()), number(p, "max_db", d.maxDb()),
                    WindowFunction.valueOf(p.getProperty("window", d.window().name())), number(p, "fft_rate", d.fftRate()),
                    integer(p, "fft_size", d.fftSize()), integer(p, "averaging", d.averaging()),
                    integer(p, "waterfall_rate", d.waterfallRate()),
                    Path.of(p.getProperty("storage_folder", d.storageFolder().toString())),
                    RecordingFormat.valueOf(p.getProperty("storage_format", d.storageFormat().name())),
                    Boolean.parseBoolean(p.getProperty("sidebar_collapsed", Boolean.toString(d.sidebarCollapsed()))),
                    integer(p, "width", d.windowWidth()), integer(p, "height", d.windowHeight()));
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "Cannot restore JSatInspector settings from " + file + "; using defaults", e);
            return d;
        }
    }

    void save(InspectorSettings s) throws IOException {
        Properties p = new Properties();
        p.setProperty("ip", s.ipAddress());
        p.setProperty("frequency_hz", Double.toString(s.frequencyHz()));
        p.setProperty("bandwidth", Integer.toString(s.bandwidth()));
        p.setProperty("sample_rate", Integer.toString(s.sampleRate()));
        p.setProperty("decimation", Integer.toString(s.decimation()));
        p.setProperty("gain", Integer.toString(s.gain()));
        p.setProperty("rx_buffer_samples", Integer.toString(s.rxBufferSamples()));
        p.setProperty("min_db", Double.toString(s.minDb()));
        p.setProperty("max_db", Double.toString(s.maxDb()));
        p.setProperty("window", s.window().name());
        p.setProperty("fft_rate", Double.toString(s.fftRate()));
        p.setProperty("fft_size", Integer.toString(s.fftSize()));
        p.setProperty("averaging", Integer.toString(s.averaging()));
        p.setProperty("waterfall_rate", Integer.toString(s.waterfallRate()));
        p.setProperty("storage_folder", s.storageFolder().toString());
        p.setProperty("storage_format", s.storageFormat().name());
        p.setProperty("sidebar_collapsed", Boolean.toString(s.sidebarCollapsed()));
        p.setProperty("width", Integer.toString(s.windowWidth()));
        p.setProperty("height", Integer.toString(s.windowHeight()));
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(file.toAbsolutePath().getParent(), "settings-", ".tmp");
        try {
            try (var out = Files.newBufferedWriter(temporary)) { p.store(out, "JSatInspector"); }
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static double number(Properties p, String key, double fallback) {
        return Double.parseDouble(p.getProperty(key, Double.toString(fallback)));
    }

    private static int integer(Properties p, String key, int fallback) {
        return Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
    }

    private static int rxBufferSamples(Properties p, int fallback) {
        if (p.containsKey("rx_buffer_samples")) return integer(p, "rx_buffer_samples", fallback);
        if (!p.containsKey("rx_buffer_bytes")) return fallback;
        int bytes = integer(p, "rx_buffer_bytes", 0);
        // Migrate only valid former settings, preserving their exact complex-sample capacity.
        if (bytes < 4 || bytes > 4 * 1_048_576 || bytes % 4 != 0)
            throw new IllegalArgumentException("Invalid legacy RX buffer byte count");
        return bytes / 4;
    }
}
