package eu.dariolucia.jsatdecode.api.decoder;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

/**
 * Factory called on a management thread; discoverable using ServiceLoader.
 */
public interface DecoderFactory {
    /**
     * Returns stable metadata.
     *
     * @return immutable descriptor
     */
    DecoderDescriptor descriptor();

    /**
     * Validates settings and creates a CONFIGURED component without starting workers.
     *
     * @param configuration immutable component settings
     * @return newly owned component
     * @throws ConfigurationException if settings are invalid
     */
    Decoder create(Configuration configuration) throws ConfigurationException;
}