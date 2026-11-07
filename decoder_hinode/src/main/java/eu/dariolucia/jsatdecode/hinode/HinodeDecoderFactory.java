package eu.dariolucia.jsatdecode.hinode;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;

/** Stateless ServiceLoader entry point. Mission configuration stays in this module. */
public final class HinodeDecoderFactory implements DecoderFactory {
    /** Creates a factory without allocating DSP state or starting threads. */
    public HinodeDecoderFactory() {}

    @Override
    public DecoderDescriptor descriptor() {
        return HinodeDecoder.DESCRIPTOR;
    }

    @Override
    public Decoder create(Configuration configuration) {
        return new HinodeDecoder(configuration);
    }
}
