package eu.dariolucia.jsatdecode.api.distribution;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

/**
 * Factory called on a management thread; discoverable using ServiceLoader.
 */
public interface MonitoringDistributorFactory {
    /**
     * Returns stable metadata.
     *
     * @return immutable descriptor
     */
    MonitoringDistributorDescriptor descriptor();

    /**
     * Validates settings and creates a CONFIGURED component without starting workers.
     *
     * @param configuration immutable component settings
     * @return newly owned component
     * @throws ConfigurationException if settings are invalid
     */
    MonitoringDistributor create(Configuration configuration) throws ConfigurationException;
}