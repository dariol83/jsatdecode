package eu.dariolucia.jsatdecode.distribution.zmq.client;

import java.util.Map;

/**
 * Standard keys in a SatDecode v1 frame information map. Every key is mandatory
 * in a valid frame envelope; application-specific values are carried inside
 * {@link #METADATA}. The reference client preserves additional envelope keys.
 * Integers are exposed as {@link Long}, including the schema and frame length.
 */
public enum FrameKey {
    /** {@link Long}: protocol schema version, exactly {@code 1}. */
    SCHEMA_VERSION("schema_version", Long.class),
    /** {@link String}: envelope category, exactly {@code tm.frame}. */
    MESSAGE_TYPE("message_type", String.class),
    /** {@link String}: nonblank configured receiver identity. */
    RECEIVER_ID("receiver_id", String.class),
    /** {@link String}: nonblank configured decoder identity. */
    DECODER("decoder", String.class),
    /**
     * {@link Long}: nonnegative frame sequence, starting at zero for a new
     * publisher. Gaps can indicate loss; a restarted publisher resets it.
     */
    SEQUENCE("sequence", Long.class),
    /** {@link Long}: nonnegative reception time in UTC epoch nanoseconds. */
    RX_TIME_NS("rx_time_ns", Long.class),
    /**
     * {@link String}: reception clock source, one of {@code hardware},
     * {@code sample_clock}, {@code host}, {@code recording}, {@code unknown}.
     */
    RX_TIME_SOURCE("rx_time_source", String.class),
    /** {@link Long}: nonnegative publisher time in UTC epoch nanoseconds. */
    PUBLISH_TIME_NS("publish_time_ns", Long.class),
    /** {@link String}: nonblank frame representation, normally {@code RAW}. */
    FRAME_TYPE("frame_type", String.class),
    /** {@link Long}: positive byte length of the separately supplied payload. */
    FRAME_LENGTH("frame_length", Long.class),
    /** {@link String}: frame validity, {@code GOOD}, {@code BAD}, or {@code UNKNOWN}. */
    QUALITY("quality", String.class),
    /**
     * {@code Map<String, Object>}: application-specific annotations. Containers
     * are recursively unmodifiable; byte strings are caller-owned byte arrays.
     * Leaves may also be Long, Double, String, Boolean or null.
     */
    METADATA("metadata", Map.class);

    private final String key;
    private final Class<?> valueType;

    FrameKey(String key, Class<?> valueType) {
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
