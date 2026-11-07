package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Self-contained settings validation and persistence checks, without touching user preferences. */
public final class InspectorSettingsTest {
    private InspectorSettingsTest() { }

    /**
     * Runs settings boundary and atomic persistence checks.
     * @param args ignored
     * @throws Exception on failure
     */
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("jsatinspector-settings-");
        Path file = directory.resolve("hidden").resolve("settings.properties");
        InspectorPreferences preferences = new InspectorPreferences(file);
        try {
            check(preferences.load().equals(InspectorSettings.defaults()), "Missing file restores defaults");
            check(preferences.load().waterfallRate() == 4 && preferences.load().storageFormat() == RecordingFormat.CF32,
                    "Waterfall and recording format have the requested defaults");
            check(preferences.load().rxBufferSamples() == 16384, "Default RX buffer retains its previous sample capacity");
            InspectorSettings settings = new InspectorSettings("[::1]:30431", 2_203_682_000.125, 500_000,
                    500_000, 8, 70, 65535, -123.75, 12.25, WindowFunction.BLACKMAN,
                    0.5, 131072, 4, 15, directory.resolve("IQ space è"), RecordingFormat.CS16, true, 1420, 940);
            check(settings.effectiveSampleRate() == 62500, "Effective sample rate includes decimation");
            preferences.save(settings);
            check(preferences.load().equals(settings), "Every setting round trips including fractional frequency and Unicode folder");
            Properties saved = readProperties(file);
            check("65535".equals(saved.getProperty("rx_buffer_samples")) && !saved.containsKey("rx_buffer_bytes"),
                    "Preferences store the RX buffer exclusively as a sample count");
            saved.remove("rx_buffer_samples");
            saved.setProperty("rx_buffer_bytes", Integer.toString(settings.rxBufferSamples() * 4));
            writeProperties(file, saved);
            check(preferences.load().equals(settings), "Legacy byte preferences migrate while preserving all other preferences");
            preferences.save(preferences.load());
            check(preferences.load().equals(settings), "Migrated preferences preserve sample capacity after save and reload");
            saved = readProperties(file);
            check("65535".equals(saved.getProperty("rx_buffer_samples")) && !saved.containsKey("rx_buffer_bytes"),
                    "Saving a migrated configuration removes its legacy byte key");
            Files.writeString(file, "gain=55\nrx_buffer_samples=65535\nrx_buffer_bytes=invalid\n");
            check(preferences.load().gain() == 55 && preferences.load().rxBufferSamples() == 65535,
                    "The sample key takes precedence without parsing an invalid legacy key");
            for (String invalid : new String[]{"0", "1048577", "not-a-number"}) {
                Files.writeString(file, "gain=55\nrx_buffer_samples=" + invalid + "\nrx_buffer_bytes=65536\n");
                check(preferences.load().equals(InspectorSettings.defaults()),
                        "Invalid sample preferences retain the existing complete-default fallback instead of using the legacy key");
            }
            for (String invalid : new String[]{"0", "3", "65535", "4194308", "not-a-number"}) {
                Files.writeString(file, "gain=55\nrx_buffer_bytes=" + invalid + "\n");
                check(preferences.load().equals(InspectorSettings.defaults()),
                        "Invalid legacy RX buffer sizes restore defaults without truncating the conversion");
            }
            Files.writeString(file, "gain=55\nrx_buffer_bytes=65536\n");
            check(preferences.load().gain() == 55 && preferences.load().rxBufferSamples() == 16384,
                    "The previous default byte count migrates to the same sample capacity");
            for (int samples : new int[]{1, 65535, 1048576}) {
                Files.writeString(file, "gain=55\nrx_buffer_samples=" + samples + "\n");
                check(preferences.load().gain() == 55 && preferences.load().rxBufferSamples() == samples,
                        "Sample preferences accept both bounds and counts indivisible by four");
            }
            for (int samples : new int[]{1, 1048576}) {
                Files.writeString(file, "rx_buffer_bytes=" + samples * 4 + "\n");
                check(preferences.load().rxBufferSamples() == samples, "Legacy byte bounds migrate exactly to sample bounds");
            }
            preferences.save(InspectorSettings.defaults());
            check(preferences.load().equals(InspectorSettings.defaults()), "Existing preferences can be replaced");
            Files.writeString(file, "gain=55\n");
            check(preferences.load().gain() == 55 && preferences.load().waterfallRate() == 4
                    && preferences.load().storageFormat() == RecordingFormat.CF32,
                    "Older preferences retain existing settings and default the added settings");
            for (String invalid : new String[]{"waterfall_rate=0\n", "waterfall_rate=16\n", "storage_format=invalid\n"}) {
                Files.writeString(file, invalid);
                check(preferences.load().equals(InspectorSettings.defaults()), "Invalid new preferences restore defaults");
            }
            try (var children = Files.list(file.getParent())) {
                check(children.count() == 1, "Atomic save leaves no temporary files");
            }
            Files.writeString(file, "fft_size=not-a-number\n");
            check(preferences.load().equals(InspectorSettings.defaults()), "Malformed preferences recover with defaults");
            for (int rate : new int[]{500000, 1000000, 2000000, 3000000, 4000000, 5000000})
                for (int decimation : new int[]{1, 2, 4, 8})
                    check(settings(rate, decimation, 65536, 4096, -140, 80).effectiveSampleRate() == rate / decimation,
                            "All requested rate/decimation combinations accepted");
            rejects(() -> settings(500000, 3, 65536, 4096, -140, 80));
            check(settings(500000, 1, 1, 4096, -140, 80).rxBufferSamples() == 1,
                    "RX buffer accepts one sample");
            check(settings(500000, 1, 1048576, 4096, -140, 80).rxBufferSamples() == 1048576,
                    "RX buffer accepts the maximum sample count");
            check(settings(500000, 1, 65535, 4096, -140, 80).rxBufferSamples() == 65535,
                    "RX buffer accepts sample counts indivisible by four");
            rejects(() -> settings(500000, 1, 0, 4096, -140, 80));
            rejects(() -> settings(500000, 1, 1048577, 4096, -140, 80));
            rejects(() -> settings(500000, 1, 65536, 131073, -140, 80));
            rejects(() -> settings(500000, 1, 65536, 4096, 1, 1));
            rejects(() -> settings(500000, 1, 65536, 4096, Double.NaN, 80));
            System.out.println("JSatInspector settings: persistence and boundary checks passed");
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(directory);
        }
    }

    private static InspectorSettings settings(int rate, int decimation, int samples, int fft, double min, double max) {
        return new InspectorSettings("192.168.2.1", 137100000, 2000000, rate, decimation, 40, samples,
                min, max, WindowFunction.HANN, 15, fft, 2, Path.of(System.getProperty("java.io.tmpdir")), false, 1200, 800);
    }

    private static Properties readProperties(Path file) throws Exception {
        Properties properties = new Properties();
        try (var in = Files.newBufferedReader(file)) { properties.load(in); }
        return properties;
    }

    private static void writeProperties(Path file, Properties properties) throws Exception {
        try (var out = Files.newBufferedWriter(file)) { properties.store(out, "Test legacy settings"); }
    }

    private static void rejects(Runnable operation) {
        try { operation.run(); throw new AssertionError("Invalid configuration accepted"); }
        catch (IllegalArgumentException expected) { }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
