package eu.dariolucia.jsatdecode.api.lifecycle;

/**
 * Component failure surfaced to the runtime; source/decoder failures enter FAILED state.
 */
public class LifecycleException extends RuntimeException {
    /**
     * Creates a failure diagnostic.
     *
     * @param message explanation
     */
    public LifecycleException(String message) {
        super(message);
    }

    /**
     * Retains the underlying failure.
     *
     * @param message explanation
     * @param cause   original failure
     */
    public LifecycleException(String message, Throwable cause) {
        super(message, cause);
    }
}