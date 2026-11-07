package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;

/**
 * ServiceLoader factory for the Java socket FMComms2/PlutoPlus IIOD source.
 */
public final class Fmcomms2SourceFactory implements SampleSourceFactory {
    /**
     * Creates an inert factory with no network/native initialization.
     */
    public Fmcomms2SourceFactory() {
    }

    @Override
    public SourceDescriptor descriptor() {
        return Fmcomms2SampleSource.DESCRIPTOR;
    }

    @Override
    public Fmcomms2SampleSource create(Configuration configuration) {
        return new Fmcomms2SampleSource(configuration);
    }
}
