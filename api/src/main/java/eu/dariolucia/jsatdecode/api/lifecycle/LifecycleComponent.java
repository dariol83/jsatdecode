package eu.dariolucia.jsatdecode.api.lifecycle;

/**
 * Runtime-owned component configured by its factory. The owner serializes lifecycle
 * calls on a management thread; state reads must be thread-safe. Start transitions
 * CONFIGURED through STARTING to RUNNING, or FAILED on error. Restart from STOPPED
 * is optional. Stop transitions through STOPPING to STOPPED. Stop and close are
 * idempotent, including before start and after failure. Close releases resources
 * exactly once and ends in CLOSED. Starting after close is rejected.
 */
public interface LifecycleComponent extends AutoCloseable {
    /**
     * Starts processing, cleaning partial resources on failure.
     *
     * @throws Exception if startup fails
     */
    void start() throws Exception;

    /**
     * Stops and joins owned workers; may block the management caller.
     */
    void stop();

    /**
     * Returns safely published state.
     *
     * @return current state
     */
    ComponentState state();

    /**
     * Reports active processing.
     *
     * @return true only in RUNNING
     */
    default boolean isRunning() {
        return state() == ComponentState.RUNNING;
    }

    /**
     * Stops and releases resources, including after failure; may block.
     */
    @Override
    void close();
}
