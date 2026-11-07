package eu.dariolucia.jsatdecode.api.distribution;

/**
 * Bounded frame queue policies; blocking upstream is deliberately unsupported.
 */
public enum OverflowPolicy {
    /**
     * Default: surface overflow as a runtime failure.
     */
    FAIL_RUNTIME,
    /**
     * Explicitly count and discard the incoming frame.
     */
    DROP_NEWEST,
    /**
     * Explicitly count and discard the oldest queued frame.
     */
    DROP_OLDEST
}