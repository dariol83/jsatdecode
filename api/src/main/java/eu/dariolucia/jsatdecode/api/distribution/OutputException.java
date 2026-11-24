package eu.dariolucia.jsatdecode.api.distribution;

/**
 * Component failure surfaced to the runtime; source/decoder failures enter FAILED state.
 */
public class OutputException extends RuntimeException {
    /**
     * Creates a failure diagnostic.
     *
     * @param message explanation
     */
    public OutputException(String message) {
        super(message);
    }

    /**
     * Retains the underlying failure.
     *
     * @param message explanation
     * @param cause   original failure
     */
    public OutputException(String message, Throwable cause) {
        super(message, cause);
    }
}