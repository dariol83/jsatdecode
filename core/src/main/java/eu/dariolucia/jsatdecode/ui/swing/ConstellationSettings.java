package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.Locale;
import java.util.Objects;

/**
 * Immutable bounded observer settings; no setting changes symbol recovery DSP.
 *
 * @param points        maximum symbols requested per observation, 1..16384
 * @param historyFrames history capacity multiplier, 1..8; storage is points times this value
 * @param refreshHz     target observation cadence, 0.5..30 Hz
 * @param autoScale     true to fit retained finite points with ten-percent headroom
 * @param scale         fixed symmetric I/Q half-range, positive and finite
 * @param reference     optional unit-energy reference markers
 */
public record ConstellationSettings(int points, int historyFrames, double refreshHz, boolean autoScale, double scale,
                                    Reference reference) {
    /**
     * Optional display markers; they do not classify or demodulate symbols.
     */
    public enum Reference {
        /**
         * No reference.
         */
        NONE,
        /**
         * Real-axis unit BPSK.
         */
        BPSK,
        /**
         * Diagonal unit-energy QPSK.
         */
        QPSK
    }

    /**
     * Validates all bounds before allocating history.
     *
     * @param points        symbols per observation
     * @param historyFrames history capacity multiplier
     * @param refreshHz     target cadence
     * @param autoScale     automatic display scaling
     * @param scale         fixed half-range
     * @param reference     reference marker selection
     */
    public ConstellationSettings {
        Objects.requireNonNull(reference);
        if (points < 1 || points > 16384 || historyFrames < 1 || historyFrames > 8 || !Double.isFinite(refreshHz) || refreshHz < 0.5 || refreshHz > 30 ||
                !Double.isFinite(scale) || scale < 1e-12 || scale > 1e30)
            throw new ConfigurationException("Invalid constellation bounds, cadence or scale");
    }

    /**
     * Reads the unprefixed ui section. Defaults: 1024 points, one history unit,
     * 15 Hz, automatic scaling and no reference markers.
     *
     * @param c immutable UI configuration
     * @return validated settings
     */
    public static ConstellationSettings read(Configuration c) {
        String mode = c.asMap().containsKey("constellation.scale_mode") ? c.getString("constellation.scale_mode").toUpperCase(Locale.ROOT) : "AUTO";
        if (!mode.equals("AUTO") && !mode.equals("FIXED"))
            throw new ConfigurationException("Invalid ui.constellation.scale_mode");
        Reference reference;
        try {
            reference = Reference.valueOf(c.asMap().containsKey("constellation.reference") ? c.getString("constellation.reference").toUpperCase(Locale.ROOT) : "NONE");
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid ui.constellation.reference", e);
        }
        return new ConstellationSettings(integer(c, "constellation.points", 1024), integer(c, "constellation.history_frames", 1),
                c.asMap().containsKey("refresh.constellation_hz") ? c.getDouble("refresh.constellation_hz") : 15, mode.equals("AUTO"),
                c.asMap().containsKey("constellation.scale") ? c.getDouble("constellation.scale") : 1.5, reference);
    }

    private static int integer(Configuration c, String key, int fallback) {
        return c.asMap().containsKey(key) ? c.getInt(key) : fallback;
    }
}
