package eu.dariolucia.jsatdecode.source.cf32;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;
import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;
import eu.dariolucia.jsatdecode.source.file.FileSourceProperties;

import java.util.List;

/**
 * Java-SE-only CF32 factory, discoverable with ServiceLoader.
 */
public final class Cf32SourceFactory implements SampleSourceFactory {
    static final SourceDescriptor DESCRIPTOR = new SourceDescriptor("cf32", "Raw CF32 IQ file", "0.1.0-SNAPSHOT", "Little-endian float32 interleaved IQ recording");

    /**
     * Creates a stateless factory.
     */
    public Cf32SourceFactory() {
    }

    /**
     * Returns the stable cf32 identity.
     *
     * @return immutable descriptor
     */
    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public List<SourcePropertyDescriptor> configurationProperties() {
        return FileSourceProperties.descriptors(false);
    }

    /**
     * Validates settings and creates a CONFIGURED source, without starting workers.
     *
     * @param configuration unprefixed source settings
     * @return newly owned source
     * @throws eu.dariolucia.jsatdecode.api.config.ConfigurationException for invalid configuration or file geometry
     */
    @Override
    public Cf32SampleSource create(Configuration configuration) {
        return new Cf32SampleSource(configuration);
    }
}
