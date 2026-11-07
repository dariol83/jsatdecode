package eu.dariolucia.jsatdecode.decoder.dummy;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;

/**
 * ServiceLoader factory for deterministic integration tests; performs no mission decoding.
 */
public final class DummyDecoderFactory implements DecoderFactory {
    /**
     * Creates a stateless factory.
     */
    public DummyDecoderFactory() {
    }

    @Override
    public DecoderDescriptor descriptor() {
        return new DecoderDescriptor("dummy", "Dummy decoder", "1", "Integration only, no DSP");
    }

    @Override
    public Decoder create(Configuration configuration) {
        return new DummyDecoder();
    }
}
