package eu.dariolucia.jsatdecode.distribution.zmq.client;

import java.util.Map;

/**
 * Standard keys in SatDecode v1 monitoring information maps. The common keys
 * apply to both status and event envelopes; the remaining constants document
 * which envelope requires them. Additional envelope keys are preserved.
 * Metric names inside {@link #METRICS} are separate from this envelope
 * vocabulary; mandatory decoder metric names are defined by
 * {@link eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys}.
 */
public enum MonitoringKey {
    /** {@link Long}: protocol schema version, exactly {@code 1}; common key. */
    SCHEMA_VERSION("schema_version", Long.class),
    /**
     * {@link String}: {@code monitor.status} for status or
     * {@code monitor.event} for events; common key.
     */
    MESSAGE_TYPE("message_type", String.class),
    /** {@link String}: nonblank configured receiver identity; common key. */
    RECEIVER_ID("receiver_id", String.class),
    /** {@link String}: nonblank configured decoder identity; common key. */
    DECODER("decoder", String.class),
    /**
     * {@link Long}: nonnegative sequence; common key. Status and event streams
     * have independent counters, reset to zero for a new publisher.
     */
    SEQUENCE("sequence", Long.class),
    /**
     * {@link Long}: nonnegative UTC epoch nanoseconds; common key. This is
     * publication time for status and occurrence time for an event.
     */
    TIMESTAMP_NS("timestamp_ns", Long.class),
    /**
     * {@code Map<String, Object>}: complete latest metric state; status only.
     * Unknown numerical measurements may be Double.NaN. Containers are
     * recursively unmodifiable and byte strings are caller-owned arrays.
     */
    METRICS("metrics", Map.class),
    /**
     * {@code Map<String, Long>}: metric observation times in nonnegative UTC
     * epoch nanoseconds; status only. These are supplied observation times,
     * not necessarily the instants when values last changed.
     */
    METRIC_TIMESTAMPS_NS("metric_timestamps_ns", Map.class),
    /** {@link String}: nonblank event name, for example {@code source.eof}; event only. */
    EVENT("event", String.class),
    /**
     * {@link String}: {@code TRACE}, {@code DEBUG}, {@code INFO},
     * {@code WARNING}, {@code ERROR} or {@code CRITICAL}; event only.
     */
    SEVERITY("severity", String.class),
    /**
     * {@code Map<String, Object>}: extensible event context; event only.
     * Containers are recursively unmodifiable; leaves may be Long, Double,
     * String, Boolean, caller-owned byte arrays or null.
     */
    DETAILS("details", Map.class);

    private final String key;
    private final Class<?> valueType;

    MonitoringKey(String key, Class<?> valueType) {
        this.key = key;
        this.valueType = valueType;
    }

    /**
     * Returns the exact case-sensitive wire/map key without allocating.
     *
     * @return standardized key
     */
    public String key() {
        return key;
    }

    /**
     * Returns the runtime class of a decoded value; map type arguments are
     * described by the corresponding constant's documentation.
     *
     * @return decoded value class
     */
    public Class<?> valueType() {
        return valueType;
    }
}
