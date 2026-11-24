package eu.dariolucia.jsatdecode.api.config;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration with strict typed conversion and key-specific diagnostics.
 */
public final class Configuration {
    private final Map<String, Object> values;

    /**
     * Recursively snapshots settings.
     *
     * @param values settings using the ImmutableValues vocabulary
     */
    public Configuration(Map<String, ?> values) {
        this.values = ImmutableValues.copy(values);
    }

    /**
     * Returns a required value.
     *
     * @param key property name
     * @return immutable value
     * @throws ConfigurationException if absent
     */
    public Object get(String key) {
        Objects.requireNonNull(key, "key");
        if (!values.containsKey(key)) throw new ConfigurationException("Missing configuration: " + key);
        return values.get(key);
    }

    /**
     * Reads a string without coercing other types.
     *
     * @param key property name
     * @return string value
     */
    public String getString(String key) {
        if (get(key) instanceof String s) return s;
        throw invalid(key, "string", null);
    }

    /**
     * Reads an exact signed 32-bit integer from a number or decimal string.
     *
     * @param key property name
     * @return integer value
     */
    public int getInt(String key) {
        try {
            return decimal(key).intValueExact();
        } catch (ArithmeticException e) {
            throw invalid(key, "32-bit integer", e);
        }
    }

    /**
     * Reads an exact signed 64-bit integer from a number or decimal string.
     *
     * @param key property name
     * @return long value
     */
    public long getLong(String key) {
        try {
            return decimal(key).longValueExact();
        } catch (ArithmeticException e) {
            throw invalid(key, "64-bit integer", e);
        }
    }

    /**
     * Reads a finite double; NaN/infinity are invalid configuration values.
     *
     * @param key property name
     * @return finite double
     */
    public double getDouble(String key) {
        Object v = get(key);
        if (!(v instanceof Number || v instanceof String)) throw invalid(key, "finite double", null);
        try {
            double d = Double.parseDouble(v.toString().trim());
            if (!Double.isFinite(d)) throw invalid(key, "finite double", null);
            return d;
        } catch (NumberFormatException e) {
            throw invalid(key, "finite double", e);
        }
    }

    /**
     * Reads a boolean or case-insensitive true/false string.
     *
     * @param key property name
     * @return boolean value
     */
    public boolean getBoolean(String key) {
        Object v = get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof String s) {
            if (s.trim().equalsIgnoreCase("true")) return true;
            if (s.trim().equalsIgnoreCase("false")) return false;
        }
        throw invalid(key, "boolean (true/false)", null);
    }

    /**
     * Selects dot-delimited children and strips the prefix.
     *
     * @param prefix nonblank namespace, optionally ending in a dot
     * @return immutable section, empty if absent
     */
    public Configuration getSection(String prefix) {
        String p = ImmutableValues.text(prefix, "prefix");
        String start = p.endsWith(".") ? p : p + ".";
        Map<String, Object> section = new LinkedHashMap<>();
        values.forEach((k, v) -> {
            if (k.startsWith(start) && k.length() > start.length()) section.put(k.substring(start.length()), v);
        });
        return new Configuration(section);
    }

    /**
     * Returns immutable settings.
     *
     * @return detached immutable map
     */
    public Map<String, Object> asMap() {
        return values;
    }

    private BigDecimal decimal(String key) {
        Object v = get(key);
        if (!(v instanceof Number || v instanceof String)) throw invalid(key, "integer", null);
        try {
            return new BigDecimal(v.toString().trim());
        } catch (NumberFormatException e) {
            throw invalid(key, "integer", e);
        }
    }

    private ConfigurationException invalid(String key, String expected, Throwable cause) {
        return new ConfigurationException("Configuration '" + key + "' requires " + expected, cause);
    }
}