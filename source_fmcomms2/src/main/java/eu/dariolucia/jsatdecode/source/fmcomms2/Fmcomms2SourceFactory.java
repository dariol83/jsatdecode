package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.DiagnosticSampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceReadDiagnostics;
import eu.dariolucia.jsatdecode.api.source.SourceDescriptor;
import eu.dariolucia.jsatdecode.api.source.SourcePropertyDescriptor;

import java.util.List;

/**
 * ServiceLoader factory for the Java socket FMComms2/PlutoPlus IIOD source.
 */
public final class Fmcomms2SourceFactory implements DiagnosticSampleSourceFactory {
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
    public List<SourcePropertyDescriptor> configurationProperties() {
        return Fmcomms2Properties.ALL;
    }

    @Override
    public Fmcomms2SampleSource create(Configuration configuration) {
        return new Fmcomms2SampleSource(configuration);
    }

    @Override
    public Capture createDiagnostic(Configuration configuration, int capacity, SourceReadDiagnostics.ReadListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        ReadDiagnostics diagnostics = new ReadDiagnostics(capacity, listener::onRead);
        return new Capture(new Fmcomms2SampleSource(configuration, diagnostics), diagnostics);
    }
}
