package eu.dariolucia.jsatdecode.api.source;

import eu.dariolucia.jsatdecode.api.config.Configuration;

import java.util.Objects;

/** Optional factory capability for a count-only source read diagnostic run. */
public interface DiagnosticSampleSourceFactory extends SampleSourceFactory {
    /** A diagnostic source and its bounded read log, owned together. */
    record Capture(ManagedSampleSource source, SourceReadDiagnostics diagnostics) {
        public Capture {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(diagnostics, "diagnostics");
        }
    }

    /**
     * Creates an unstarted diagnostic source and preallocated log. The listener
     * runs on the acquisition thread for every successful read, even when the
     * log is full; it must not block or perform I/O.
     */
    Capture createDiagnostic(Configuration configuration, int capacity,
                             SourceReadDiagnostics.ReadListener listener);
}
