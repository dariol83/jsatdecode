package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.DistributionConfigurationNormalizer;

/** Normalizes shared JTDP settings before runtime components are created. */
public final class TcpConfigurationNormalizer implements DistributionConfigurationNormalizer {
    /** Creates a normalizer for service discovery. */
    public TcpConfigurationNormalizer() { }

    @Override
    public Configuration normalize(Configuration configuration) {
        return TcpSettings.normalizeConfiguration(configuration);
    }
}
