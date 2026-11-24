package eu.dariolucia.jsatdecode.source.cs16;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;

/**
 * Java-SE-only CS16 factory, discoverable with ServiceLoader.
 * CS16 format: headerless little-endian signed 16-bit interleaved IQ samples.
 * Compatible with SatDump and SDR++ CS16 format.
 */
public final class Cs16SourceFactory implements SampleSourceFactory {
    static final SourceDescriptor DESCRIPTOR = new SourceDescriptor("cs16", "Raw CS16 IQ file", "0.1.0-SNAPSHOT", "Little-endian signed 16-bit interleaved IQ recording (SatDump/SDR++ format)");

    /**
     * Creates a stateless factory.
     */
    public Cs16SourceFactory() {
    }

    /**
     * Returns the stable cs16 identity.
     *
     * @return immutable descriptor
     */
    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * Validates settings and creates a CONFIGURED source, without starting workers.
     *
     * @param configuration unprefixed source settings
     * @return newly owned source
     * @throws eu.dariolucia.jsatdecode.api.config.ConfigurationException for invalid configuration or file geometry
     */
    @Override
    public Cs16SampleSource create(Configuration configuration) {
        return new Cs16SampleSource(configuration);
    }
}
