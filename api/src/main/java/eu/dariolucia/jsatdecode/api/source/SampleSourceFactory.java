package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.List;

/**
 * Factory called on a management thread; discoverable using ServiceLoader.
 */
public interface SampleSourceFactory {
    /**
     * Returns stable metadata.
     *
     * @return immutable descriptor
     */
    SourceDescriptor descriptor();

    /**
     * Returns ordered source-specific configuration metadata without opening any
     * resources. An empty list means metadata is unavailable, preserving existing
     * providers. Keys use the same unprefixed namespace as {@link #create}.
     *
     * @return immutable property descriptors
     */
    default List<SourcePropertyDescriptor> configurationProperties() {
        return List.of();
    }

    /**
     * Validates settings and creates a CONFIGURED component without starting workers.
     *
     * @param configuration immutable component settings
     * @return newly owned component
     * @throws ConfigurationException if settings are invalid
     */
    SampleSource create(Configuration configuration) throws ConfigurationException;
}
