package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.DiagnosticSampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSource;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.api.source.SourceReadDiagnostics;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;

/** Resolves Inspector's current FMComms2 source through the source service. */
final class InspectorSources {
    private InspectorSources() { }

    private static SampleSourceFactory factory() {
        return FactoryRegistry.discover(SampleSourceFactory.class, f -> f.descriptor().id()).require("fmcomms2");
    }

    static ManagedSampleSource receiver(Configuration configuration) {
        SampleSource source = factory().create(configuration);
        if (source instanceof ManagedSampleSource managed) return managed;
        source.close();
        throw new IllegalStateException("FMComms2 source does not support managed acquisition");
    }

    static DiagnosticSampleSourceFactory.Capture diagnostic(Configuration configuration, int capacity,
                                                             SourceReadDiagnostics.ReadListener listener) {
        SampleSourceFactory sourceFactory = factory();
        if (sourceFactory instanceof DiagnosticSampleSourceFactory diagnostic) {
            return diagnostic.createDiagnostic(configuration, capacity, listener);
        }
        throw new IllegalStateException("FMComms2 source does not support read diagnostics");
    }
}
