package eu.dariolucia.jsatdecode.api.lifecycle;

/**
 * Safely published lifecycle state. CLOSED is terminal.
 */
public enum ComponentState {
    /**
     * Not configured.
     */
    CREATED,
    /**
     * Immutable configuration validated.
     */
    CONFIGURED,
    /**
     * Startup in progress.
     */
    STARTING,
    /**
     * Processing active.
     */
    RUNNING,
    /**
     * Shutdown in progress.
     */
    STOPPING,
    /**
     * Processing stopped.
     */
    STOPPED,
    /**
     * Operation failed.
     */
    FAILED,
    /**
     * All resources released.
     */
    CLOSED
}
