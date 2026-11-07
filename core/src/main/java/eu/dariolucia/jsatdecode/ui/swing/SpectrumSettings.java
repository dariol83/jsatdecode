package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable display-analysis settings, independent of decoder DSP configuration.
 *
 * @param fftSize        power-of-two size, 512 through 131072
 * @param window         symmetric analysis window
 * @param rfHz           RF target refresh, 0.5 through 30 Hz
 * @param basebandHz     baseband target refresh, 0.5 through 30 Hz
 * @param averageAlpha   base linear-power EMA weight in (0,1]; RF smoothing applies an additional divisor
 * @param floorDb        finite relative-dB floor
 * @param ceilingDb      finite display ceiling greater than the floor
 * @param maximumColumns display storage bound, 1 through 4096
 * @param rfCenterHz     explicit nonnegative RF center or NaN for monitoring metadata
 */
public record SpectrumSettings(int fftSize, WindowFunction window, double rfHz, double basebandHz, double averageAlpha,
                               double floorDb, double ceilingDb, int maximumColumns, double rfCenterHz) {
    static final int DEFAULT_RF_SMOOTHING_LEVEL = 3; // High: eightfold averaging

    /**
     * Validates limits before any analysis buffers are allocated.
     *
     * @param fftSize        FFT size
     * @param window         window kind
     * @param rfHz           RF rate
     * @param basebandHz     baseband rate
     * @param averageAlpha   averaging weight
     * @param floorDb        relative-dB floor
     * @param ceilingDb      display ceiling
     * @param maximumColumns bounded columns
     * @param rfCenterHz     explicit RF center, or NaN when unspecified
     */
    public SpectrumSettings {
        Objects.requireNonNull(window, "window");
        if (fftSize < 512 || fftSize > 131072 || (fftSize & (fftSize - 1)) != 0 ||
                !Double.isFinite(rfHz) || rfHz < 0.5 || rfHz > 30 || !Double.isFinite(basebandHz) || basebandHz < 0.5 || basebandHz > 30 ||
                !Double.isFinite(averageAlpha) || averageAlpha <= 0 || averageAlpha > 1 ||
                !Double.isFinite(floorDb) || !Double.isFinite(ceilingDb) || floorDb >= ceilingDb ||
                floorDb < -1000 || ceilingDb > 1000 || maximumColumns < 1 || maximumColumns > 4096 ||
                (!Double.isNaN(rfCenterHz) && (!Double.isFinite(rfCenterHz) || rfCenterHz < 0)))
            throw new ConfigurationException("Invalid spectrum size, cadence, averaging, axis or column bounds");
    }

    /**
     * Reads an unprefixed ui section. Defaults are 4096/Hann/15 Hz with base EMA weight one.
     *
     * @param configuration immutable UI settings
     * @return validated spectrum configuration
     */
    public static SpectrumSettings read(Configuration configuration) {
        WindowFunction window;
        try {
            window = WindowFunction.valueOf(configuration.asMap().containsKey("fft.window") ?
                    configuration.getString("fft.window").toUpperCase(Locale.ROOT) : "HANN");
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid ui.fft.window", e);
        }
        return new SpectrumSettings(integer(configuration, "fft.size", 4096), window, number(configuration, "refresh.spectrum_hz", 15),
                number(configuration, "refresh.baseband_hz", 15), number(configuration, "spectrum.average_alpha", 1),
                number(configuration, "spectrum.floor_db", -140), number(configuration, "spectrum.ceiling_db", 80),
                integer(configuration, "spectrum.maximum_columns", 2048), number(configuration, "spectrum.rf_center_frequency_hz", Double.NaN));
    }

    private static double number(Configuration c, String key, double fallback) {
        return c.asMap().containsKey(key) ? c.getDouble(key) : fallback;
    }

    private static int integer(Configuration c, String key, int fallback) {
        return c.asMap().containsKey(key) ? c.getInt(key) : fallback;
    }
}
