package eu.dariolucia.jsatdecode.source.wav;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;

/**
 * Java-SE-only float WAV factory, discoverable with ServiceLoader.
 */
public final class WavSourceFactory implements SampleSourceFactory {
    static final SourceDescriptor DESCRIPTOR = new SourceDescriptor("wav", "Float32 stereo IQ WAV file", "0.1.0-SNAPSHOT", "RIFF/RF64 IEEE float32 stereo IQ recording");

    /**
     * Creates a stateless factory.
     */
    public WavSourceFactory() {
    }

    /**
     * Returns the stable wav identity.
     *
     * @return immutable descriptor
     */
    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    /**
     * Validates settings/header and creates a CONFIGURED source without starting workers.
     *
     * @param configuration unprefixed source settings
     * @return newly owned source
     * @throws eu.dariolucia.jsatdecode.api.config.ConfigurationException for invalid configuration or WAV headers
     */
    @Override
    public WavSampleSource create(Configuration configuration) {
        return new WavSampleSource(configuration);
    }
}
