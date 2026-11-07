package eu.dariolucia.jsatdecode.decoder.base.config;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.Set;

/**
 * Setup-only decoder configuration helpers. Defaults, bounds, aliases and routing stay
 * mission-owned.
 */
public final class DecoderConfiguration {
    private DecoderConfiguration() {}

    /**
     * Rejects the first unknown key in the configuration's existing iteration order.
     *
     * @param configuration unprefixed decoder section
     * @param allowed mission's accepted keys
     * @param messagePrefix existing mission diagnostic prefix, including spacing
     * @throws ConfigurationException if a key is unsupported
     */
    public static void knownKeys(
            Configuration configuration, Set<String> allowed, String messagePrefix) {
        for (String key : configuration.asMap().keySet()) {
            if (!allowed.contains(key)) throw new ConfigurationException(messagePrefix + key);
        }
    }

    /**
     * Reads a strict framework boolean, applying the mission default only when absent.
     *
     * @param configuration unprefixed decoder section
     * @param key property name
     * @param fallback mission default
     * @return parsed value or fallback
     */
    public static boolean bool(Configuration configuration, String key, boolean fallback) {
        return configuration.asMap().containsKey(key) ? configuration.getBoolean(key) : fallback;
    }

    /**
     * Reads an integer with inclusive mission bounds and the existing range diagnostic.
     *
     * @param configuration unprefixed decoder section
     * @param key property name
     * @param fallback mission default
     * @param low inclusive lower bound
     * @param high inclusive upper bound
     * @return validated value
     * @throws ConfigurationException if malformed or outside the bounds
     */
    public static int integer(
            Configuration configuration, String key, int fallback, int low, int high) {
        int value = configuration.asMap().containsKey(key) ? configuration.getInt(key) : fallback;
        if (value < low || value > high) {
            throw new ConfigurationException(key + " must be in [" + low + "," + high + "]");
        }
        return value;
    }

    /**
     * Reads a finite double with inclusive mission bounds and the existing range diagnostic.
     *
     * @param configuration unprefixed decoder section
     * @param key property name
     * @param fallback mission default
     * @param low inclusive lower bound
     * @param high inclusive upper bound
     * @return validated finite value
     * @throws ConfigurationException if malformed, nonfinite or outside the bounds
     */
    public static double number(
            Configuration configuration, String key, double fallback, double low, double high) {
        double value =
                configuration.asMap().containsKey(key) ? configuration.getDouble(key) : fallback;
        if (!Double.isFinite(value) || value < low || value > high) {
            throw new ConfigurationException(key + " must be finite in [" + low + "," + high + "]");
        }
        return value;
    }
}
