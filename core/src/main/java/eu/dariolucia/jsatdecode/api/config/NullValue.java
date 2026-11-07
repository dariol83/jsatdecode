package eu.dariolucia.jsatdecode.api.config;

/**
 * Explicit, immutable null metadata. Raw Java null remains invalid in the
 * immutable configuration/frame/snapshot contracts; this marker allows a real
 * null value to cross transport-neutral boundaries without weakening validation.
 */
public enum NullValue {
    /**
     * A present metadata field whose value is explicitly null.
     */
    INSTANCE
}
