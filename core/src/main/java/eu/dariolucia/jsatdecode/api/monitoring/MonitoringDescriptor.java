package eu.dariolucia.jsatdecode.api.monitoring;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Objects;

/**
 * Immutable metric metadata.
 *
 * @param key         nonblank metric name
 * @param type        value category
 * @param unit        unit, empty for dimensionless values
 * @param description nonblank meaning
 */
public record MonitoringDescriptor(String key, MonitoringType type, String unit, String description) {
    /**
     * Validates metric metadata.
     *
     * @param key         metric name
     * @param type        value category
     * @param unit        unit or empty string
     * @param description metric meaning
     */
    public MonitoringDescriptor {
        ImmutableValues.text(key, "key");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(unit, "unit");
        ImmutableValues.text(description, "description");
    }
}