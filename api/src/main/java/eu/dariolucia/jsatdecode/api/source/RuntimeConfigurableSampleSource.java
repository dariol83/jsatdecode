package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

/** Optional source capability; existing sources and owners remain compatible. */
public interface RuntimeConfigurableSampleSource extends SampleSource {
    /**
     * Applies a partial configuration to a RUNNING source. The owner serializes
     * this potentially blocking call with lifecycle operations on a management
     * thread, never on a sample callback or UI thread. Omitted keys retain their
     * values. Only keys advertised as runtime-updatable by the factory may occur.
     * Implementations validate the whole request before performing I/O. Hardware
     * errors may leave a partially applied request; implementations must report
     * the error rather than acknowledge success, and document recovery behavior.
     *
     * @param changes immutable unprefixed settings to change
     * @throws ConfigurationException for unsupported keys or invalid values
     * @throws IllegalStateException when the source is not running
     * @throws SourceException when applying the request fails
     */
    void updateConfiguration(Configuration changes);
}
