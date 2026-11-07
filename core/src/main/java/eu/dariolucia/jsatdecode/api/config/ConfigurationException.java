package eu.dariolucia.jsatdecode.api.config;

/**
 * Invalid configuration detected before processing starts.
 */
public class ConfigurationException extends IllegalArgumentException {
    /**
     * Creates a diagnostic.
     *
     * @param message actionable description
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a diagnostic retaining its cause.
     *
     * @param message actionable description
     * @param cause   validation failure
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
