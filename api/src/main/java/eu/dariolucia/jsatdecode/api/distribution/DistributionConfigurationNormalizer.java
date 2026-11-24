package eu.dariolucia.jsatdecode.api.distribution;

import eu.dariolucia.jsatdecode.api.config.Configuration;

/**
 * Optional service for distribution providers that normalize and validate
 * shared settings before the runtime constructs any components.
 */
public interface DistributionConfigurationNormalizer {
    /**
     * Normalizes shared distribution settings.
     *
     * @param configuration merged global configuration
     * @return configuration with provider aliases resolved
     */
    Configuration normalize(Configuration configuration);
}
