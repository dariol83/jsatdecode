package eu.dariolucia.jsatdecode.api.source;

import java.util.Optional;

/**
 * Optional capability for sources that support nonblocking cancellation and
 * expose the original acquisition failure to their owner.
 */
public interface ManagedSampleSource extends SampleSource {
    /** Starts the source or throws an unchecked startup failure. */
    @Override
    void start();

    /** Requests cancellation without joining; safe to call from a sample callback. */
    void requestStop();

    /** Returns the first acquisition failure, if one has occurred. */
    Optional<Throwable> failure();
}
