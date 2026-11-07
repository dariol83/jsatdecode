package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;

/** Stateless ServiceLoader factory; construction creates no worker or external resource. */
public final class ElektroLGgakDecoderFactory implements DecoderFactory {
    /** Creates an inert decoder factory. */
    public ElektroLGgakDecoderFactory() {}

    @Override
    public DecoderDescriptor descriptor() {
        return ElektroLGgakDecoder.DESCRIPTOR;
    }

    @Override
    public Decoder create(Configuration configuration) {
        return new ElektroLGgakDecoder(configuration);
    }
}
