package eu.dariolucia.jsatdecode.decoder.base;

/**
 * Shared frame annotation key literals; mission-specific values and additional keys remain local.
 */
public final class FrameAnnotationKeys {
    /** Published payload type. */
    public static final String FRAME_TYPE = "frame_type";

    /** Documented validation quality. */
    public static final String QUALITY = "quality";

    /** Published payload length in bytes. */
    public static final String FRAME_LENGTH = "frame_length";

    /** Reception timestamp in UTC epoch nanoseconds. */
    public static final String RX_TIME_NS = "rx_time_ns";

    /** Provenance of the reception timestamp. */
    public static final String RX_TIME_SOURCE = "rx_time_source";

    private FrameAnnotationKeys() {}
}
