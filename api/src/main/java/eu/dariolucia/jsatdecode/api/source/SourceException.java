package eu.dariolucia.jsatdecode.api.source;

/**
 * Component failure surfaced to the runtime; source/decoder failures enter FAILED state.
 */
public class SourceException extends RuntimeException {
    /**
     * Creates a failure diagnostic.
     *
     * @param message explanation
     */
    public SourceException(String message) {
        super(message);
    }

    /**
     * Retains the underlying failure.
     *
     * @param message explanation
     * @param cause   original failure
     */
    public SourceException(String message, Throwable cause) {
        super(message, cause);
    }
}