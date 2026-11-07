package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;

/* Application-owned configuration; never changes decoder/runtime settings. */
record InspectorSettings(String ipAddress, double frequencyHz, int bandwidth, int sampleRate, int decimation,
                         int gain, int rxBufferSamples, double minDb, double maxDb, WindowFunction window,
                         double fftRate, int fftSize, int averaging, int waterfallRate,
                         Path storageFolder, RecordingFormat storageFormat, boolean sidebarCollapsed,
                         int windowWidth, int windowHeight) {
    private static final Set<Integer> RATES = Set.of(500_000, 1_000_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000);

    InspectorSettings {
        ipAddress = Objects.requireNonNull(ipAddress, "IP address").strip();
        if (ipAddress.isEmpty()) throw new IllegalArgumentException("Enter the PlutoPlus IP address or host[:port]");
        if (!Double.isFinite(frequencyHz) || frequencyHz < 70_000_000 || frequencyHz > 6_000_000_000L)
            throw new IllegalArgumentException("Tuning frequency must be between 70 MHz and 6 GHz");
        if (!RATES.contains(bandwidth) || !RATES.contains(sampleRate))
            throw new IllegalArgumentException("Select a supported RF bandwidth and sample rate");
        if (!Set.of(1, 2, 4, 8).contains(decimation)) throw new IllegalArgumentException("Invalid decimation factor");
        if (gain < 1 || gain > 70) throw new IllegalArgumentException("Manual gain must be 1..70 dB");
        if (rxBufferSamples < 1 || rxBufferSamples > 1_048_576)
            throw new IllegalArgumentException("RX buffer must be 1..1048576 complex samples");
        if (!Double.isFinite(minDb) || !Double.isFinite(maxDb) || minDb >= maxDb || minDb < -1000 || maxDb > 1000)
            throw new IllegalArgumentException("FFT minimum dB must be below maximum dB, within -1000..1000");
        Objects.requireNonNull(window, "FFT window type");
        if (!Double.isFinite(fftRate) || fftRate < 0.5 || fftRate > 30)
            throw new IllegalArgumentException("FFT rate must be 0.5..30 Hz");
        if (fftSize < 1024 || fftSize > 131072 || (fftSize & (fftSize - 1)) != 0)
            throw new IllegalArgumentException("FFT samples must be a power of two from 1024 through 131072");
        if (averaging < 0 || averaging > 4) throw new IllegalArgumentException("Invalid FFT averaging level");
        if (waterfallRate < 1 || waterfallRate > 15)
            throw new IllegalArgumentException("Waterfall rate must be 1..15 lines per second");
        storageFolder = Objects.requireNonNull(storageFolder, "Storage folder").toAbsolutePath().normalize();
        Objects.requireNonNull(storageFormat, "Storage format");
        windowWidth = Math.max(800, Math.min(16384, windowWidth));
        windowHeight = Math.max(600, Math.min(16384, windowHeight));
    }

    // Existing application-local callers retain the defaults for the added controls.
    InspectorSettings(String ipAddress, double frequencyHz, int bandwidth, int sampleRate, int decimation,
                      int gain, int rxBufferSamples, double minDb, double maxDb, WindowFunction window,
                      double fftRate, int fftSize, int averaging, Path storageFolder, boolean sidebarCollapsed,
                      int windowWidth, int windowHeight) {
        this(ipAddress, frequencyHz, bandwidth, sampleRate, decimation, gain, rxBufferSamples, minDb, maxDb,
                window, fftRate, fftSize, averaging, 4, storageFolder, RecordingFormat.CF32,
                sidebarCollapsed, windowWidth, windowHeight);
    }

    static InspectorSettings defaults() {
        return new InspectorSettings("192.168.2.1", 137_100_000, 2_000_000, 3_000_000, 1, 40, 16384,
                -140, 80, WindowFunction.HANN, 15, 4096, 2, 4, Path.of(System.getProperty("user.home")),
                RecordingFormat.CF32, false, 1280, 850);
    }

    int effectiveSampleRate() {
        return sampleRate / decimation;
    }
}
