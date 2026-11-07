package eu.dariolucia.jsatdecode.decoder.base;

/**
 * Compile-time string vocabulary; snapshots retain MonitoringType.STRING and existing wire values.
 */
public final class DecoderLockStates {
    /** Acquisition is pending or in progress. */
    public static final String SEARCHING = "SEARCHING";

    /** The decoder's documented detector reports lock. */
    public static final String LOCKED = "LOCKED";

    /** Lock is absent or has been lost. */
    public static final String UNLOCKED = "UNLOCKED";

    /** No supported or valid detector is available. */
    public static final String UNKNOWN = "UNKNOWN";

    private DecoderLockStates() {}
}
