package eu.dariolucia.jsatdecode.api.distribution;

/**
 * Transport-neutral runtime event severity.
 */
public enum EventSeverity {
    /**
     * Fine diagnostic detail.
     */
    TRACE,
    /**
     * Debugging information.
     */
    DEBUG,
    /**
     * Normal operation.
     */
    INFO,
    /**
     * Degraded operation.
     */
    WARNING,
    /**
     * Recoverable or component error.
     */
    ERROR,
    /**
     * Runtime-threatening failure.
     */
    CRITICAL
}