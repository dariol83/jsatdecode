package eu.dariolucia.jsatdecode.api.source;

/**
 * A bounded log of completed source reads. Entries and counts are inspected
 * only after the source owner has stopped and joined acquisition.
 */
public interface SourceReadDiagnostics {
    /** Called on the acquisition thread; implementations must return promptly. */
    @FunctionalInterface
    interface ReadListener {
        /** Observes a completed read's UTC instant, elapsed time and sample count. */
        void onRead(long completionTimeNanos, long elapsedNanos, int samples);
    }

    /** Returns the maximum number of retained entries. */
    int capacity();

    /** Returns the number of retained entries after acquisition has joined. */
    int capturedReadCount();

    /** Returns the number of completed reads omitted from the bounded log. */
    long overflowReadCount();

    /** Returns a retained read's host completion time as UTC epoch nanoseconds. */
    long completionTimeNanos(int index);

    /** Returns a retained read's monotonic offset from the first completion. */
    long elapsedNanos(int index);

    /** Returns a retained read's number of complex samples. */
    int sampleCount(int index);
}
