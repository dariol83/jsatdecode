package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, implementation-independent description of an unprefixed source setting.
 * A null default means that the setting is omitted unless supplied. Bounds are
 * inclusive; providers still validate relationships between settings and hardware.
 * Descriptors inform editors and do not change factory configuration semantics.
 *
 * @param name existing configuration key, without {@code source.}
 * @param label display label
 * @param type value type
 * @param unit measurement unit, empty for dimensionless or textual values
 * @param defaultValue textual default, or null for no constant default
 * @param required whether a value must be supplied when no default exists
 * @param minimum inclusive numeric lower bound, or null
 * @param maximum inclusive numeric upper bound, or null
 * @param allowedValues allowed textual values, empty when unrestricted
 * @param runtimeUpdatable whether the running source accepts this key through
 *                         {@link RuntimeConfigurableSampleSource}
 * @param description help text, including conditional requirements
 * @param advanced whether the setting belongs in an advanced editor
 */
public record SourcePropertyDescriptor(String name, String label, Type type, String unit, String defaultValue,
                                       boolean required, Double minimum, Double maximum,
                                       List<String> allowedValues, boolean runtimeUpdatable, String description, boolean advanced) {
    /** Types understood by generic editors; paths remain strings in Configuration. */
    public enum Type {
        /** Text value. */ STRING,
        /** File or directory path, carried as text. */ PATH,
        /** Boolean value. */ BOOLEAN,
        /** Exact signed 32-bit integer. */ INTEGER,
        /** Exact signed 64-bit integer. */ LONG,
        /** Finite floating-point number. */ DOUBLE
    }

    /** Creates a non-advanced descriptor. */
    public SourcePropertyDescriptor(String name, String label, Type type, String unit, String defaultValue,
                                    boolean required, Double minimum, Double maximum, List<String> allowedValues,
                                    boolean runtimeUpdatable, String description) {
        this(name, label, type, unit, defaultValue, required, minimum, maximum, allowedValues, runtimeUpdatable, description, false);
    }

    /** Validates metadata and snapshots allowed values. */
    public SourcePropertyDescriptor {
        ImmutableValues.text(name, "name");
        ImmutableValues.text(label, "label");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(unit, "unit");
        Objects.requireNonNull(description, "description");
        allowedValues = List.copyOf(allowedValues);
        if (minimum != null && !Double.isFinite(minimum) || maximum != null && !Double.isFinite(maximum)
                || minimum != null && maximum != null && minimum > maximum)
            throw new IllegalArgumentException("Invalid property bounds: " + name);
    }

    /**
     * Parses an explicitly supplied editor value, without substituting defaults.
     * Absence, conditional requirements and cross-property validation remain the
     * factory's responsibility. Existing configuration consumers need not call this.
     *
     * @param value textual value
     * @return a value accepted by Configuration
     * @throws ConfigurationException for a type, bound or allowed-value violation
     */
    public Object parse(String value) {
        Configuration c = new Configuration(Map.of(name, Objects.requireNonNull(value)));
        Object parsed = switch (type) {
            case STRING, PATH -> value;
            case BOOLEAN -> c.getBoolean(name);
            case INTEGER -> c.getInt(name);
            case LONG -> c.getLong(name);
            case DOUBLE -> c.getDouble(name);
        };
        if (!allowedValues.isEmpty() && !allowedValues.contains(parsed.toString()))
            throw new ConfigurationException(name + " must be one of " + allowedValues);
        if (parsed instanceof Number number && (minimum != null && number.doubleValue() < minimum
                || maximum != null && number.doubleValue() > maximum))
            throw new ConfigurationException(name + " must be within " + minimum + ".." + maximum);
        return parsed;
    }
}
