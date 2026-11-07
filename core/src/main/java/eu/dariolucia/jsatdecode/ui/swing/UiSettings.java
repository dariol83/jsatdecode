package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

/**
 * Immutable observer limits and cadence. UI settings never tune source/DSP.
 *
 * @param statusHz              status/table/tap-availability refresh rate
 * @param qualityHz             SNR/quality refresh rate
 * @param maximumMetrics        table row bound
 * @param maximumTextCharacters bound for each displayed text field
 * @param tapSamples            maximum samples requested from each tap
 * @param smoothingAlpha        exponential quality smoothing weight
 * @param qualityProfile        SNR display thresholds
 * @param width                 initial window width in pixels
 * @param height                initial window height in pixels
 */
public record UiSettings(double statusHz, double qualityHz, int maximumMetrics, int maximumTextCharacters,
                         int tapSamples, double smoothingAlpha, SignalQualityProfile qualityProfile, int width,
                         int height) {
    /**
     * Validates bounds before any window/worker is created.
     *
     * @param statusHz              status rate, 0.5 through 20 Hz
     * @param qualityHz             quality rate, 0.5 through 30 Hz
     * @param maximumMetrics        positive row bound, at most 4096
     * @param maximumTextCharacters text bound, 16 through 4096
     * @param tapSamples            sample request bound, 0 through 65536
     * @param smoothingAlpha        finite weight in (0,1]
     * @param qualityProfile        non-null profile
     * @param width                 width, 640 through 4096
     * @param height                height, 480 through 2160
     */
    public UiSettings {
        if (!Double.isFinite(statusHz) || statusHz < 0.5 || statusHz > 20 || !Double.isFinite(qualityHz) || qualityHz < 0.5 || qualityHz > 30 ||
                maximumMetrics < 1 || maximumMetrics > 4096 || maximumTextCharacters < 16 || maximumTextCharacters > 4096 ||
                tapSamples < 0 || tapSamples > 65536 || !Double.isFinite(smoothingAlpha) || smoothingAlpha <= 0 || smoothingAlpha > 1 ||
                width < 640 || width > 4096 || height < 480 || height > 2160)
            throw new ConfigurationException("Invalid UI refresh, bounds, smoothing or window dimensions");
        java.util.Objects.requireNonNull(qualityProfile, "qualityProfile");
    }

    /**
     * Reads an unprefixed ui section, applying conservative display defaults.
     * Unused later-phase spectrum settings are left for their owning phase.
     *
     * @param configuration immutable UI section
     * @return validated settings
     */
    public static UiSettings read(Configuration configuration) {
        try {
            return new UiSettings(number(configuration, "refresh.status_hz", 4), number(configuration, "refresh.quality_hz", 8),
                    integer(configuration, "maximum_metrics", 256), integer(configuration, "maximum_text_characters", 256),
                    integer(configuration, "tap_samples", 1024), number(configuration, "quality.alpha", 0.25),
                    new SignalQualityProfile(number(configuration, "quality.unusable_snr_db", -5), number(configuration, "quality.poor_snr_db", 0),
                            number(configuration, "quality.fair_snr_db", 5), number(configuration, "quality.good_snr_db", 10),
                            number(configuration, "quality.excellent_snr_db", 15)), integer(configuration, "width", 1180), integer(configuration, "height", 800));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid UI signal-quality profile", e);
        }
    }

    private static double number(Configuration c, String key, double fallback) {
        return c.asMap().containsKey(key) ? c.getDouble(key) : fallback;
    }

    private static int integer(Configuration c, String key, int fallback) {
        return c.asMap().containsKey(key) ? c.getInt(key) : fallback;
    }
}
