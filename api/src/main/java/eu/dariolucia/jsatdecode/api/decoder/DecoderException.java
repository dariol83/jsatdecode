package eu.dariolucia.jsatdecode.api.decoder;

/**
 * Component failure surfaced to the runtime; source/decoder failures enter FAILED state.
 */
public class DecoderException extends RuntimeException {
    /**
     * Creates a failure diagnostic.
     *
     * @param message explanation
     */
    public DecoderException(String message) {
        super(message);
    }

    /**
     * Retains the underlying failure.
     *
     * @param message explanation
     * @param cause   original failure
     */
    public DecoderException(String message, Throwable cause) {
        super(message, cause);
    }
}