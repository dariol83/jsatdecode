package eu.dariolucia.jsatdecode.api.monitoring;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Map;

/**
 * Immutable individual observation.
 *
 * @param key            nonblank metric name
 * @param value          immutable value, NaN allowed
 * @param timestampNanos UTC epoch-nanosecond observation time
 */
public record MonitoringValue(String key, Object value, long timestampNanos) {
    /**
     * Snapshots a value.
     *
     * @param key            metric name
     * @param value          supported immutable value
     * @param timestampNanos UTC epoch-nanosecond timestamp
     */
    public MonitoringValue {
        ImmutableValues.text(key, "key");
        value = ImmutableValues.copy(Map.of(key, value)).get(key);
    }
}