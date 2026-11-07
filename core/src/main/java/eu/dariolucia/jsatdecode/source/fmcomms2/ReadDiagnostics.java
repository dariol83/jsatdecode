package eu.dariolucia.jsatdecode.source.fmcomms2;

import java.util.Objects;

/**
 * Bounded, preallocated log of successful whole IIOD READBUF completions for one
 * count-only {@link Fmcomms2SampleSource} run. Entries describe the actual returned
 * complex-sample count, including short transfers, before conversion or delivery.
 * UTC completion instants are host receipt times, not radio sample timestamps.
 *
 * <p>Construct on the management thread before starting the source. One acquisition
 * worker exclusively writes the primitive arrays and invokes the listener. The
 * owner must stop/join the source before inspecting the entries or counts; these
 * methods do not supply a concurrent snapshot. Do not share an instance between
 * sources or reuse it for another run. Storage never grows, wraps or silently
 * overwrites: excess reads increase {@link #overflowReadCount()} while the listener
 * still receives every successful completion. No logging or Swing work occurs here.
 */
public final class ReadDiagnostics {
    private final long[] completionTimes;
    private final long[] elapsedTimes;
    private final int[] sampleCounts;
    private final ReadListener listener;
    private int capturedReads;
    private long overflowReads;

    /**
     * Receives every successful read on the source acquisition thread after its
     * diagnostic entry is recorded, including when the fixed log is full.
     * Implementations must return promptly, perform only bounded counting/state
     * work and avoid I/O, logging, FFT, Swing or lifecycle joins. A listener may
     * call {@link Fmcomms2SampleSource#requestStop()} to end its run. An exception
     * fails acquisition just like a sample-sink exception.
     */
    @FunctionalInterface
    public interface ReadListener {
        /**
         * Observes the completion of one whole READBUF transaction.
         *
         * @param completionTimeNanos host completion time in UTC epoch nanoseconds
         * @param elapsedNanos monotonic elapsed nanoseconds since the first read
         *                     completion, zero for that first completion
         * @param samples positive number of returned complex samples
         */
        void onRead(long completionTimeNanos, long elapsedNanos, int samples);
    }

    /**
     * Allocates all log storage before acquisition; construction performs no I/O.
     * A capacity of N uses N long UTC instants, N long elapsed durations and N int
     * sample counts. The listener is retained for the lifetime of this log.
     *
     * @param capacity positive maximum number of retained read entries
     * @param listener non-null, acquisition-thread counting callback
     * @throws IllegalArgumentException if capacity is not positive
     * @throws NullPointerException if listener is null
     */
    public ReadDiagnostics(int capacity, ReadListener listener) {
        if (capacity <= 0) throw new IllegalArgumentException("Diagnostic read capacity must be positive");
        this.listener = Objects.requireNonNull(listener, "listener");
        completionTimes = new long[capacity];
        elapsedTimes = new long[capacity];
        sampleCounts = new int[capacity];
    }

    // Sole acquisition-worker writer. The management owner reads only after join.
    void record(long completionTimeNanos, long elapsedNanos, int samples) {
        if (capturedReads < sampleCounts.length) {
            int index = capturedReads;
            completionTimes[index] = completionTimeNanos;
            elapsedTimes[index] = elapsedNanos;
            sampleCounts[index] = samples;
            capturedReads = index + 1;
        } else {
            overflowReads++;
        }
        listener.onRead(completionTimeNanos, elapsedNanos, samples);
    }

    /**
     * Returns the immutable storage bound, safe to query from any thread anytime.
     * @return maximum number of retained read entries
     */
    public int capacity() { return sampleCounts.length; }

    /**
     * Returns the retained entry count after the source has been stopped/joined.
     * @return count in the range zero through {@link #capacity()}
     */
    public int capturedReadCount() { return capturedReads; }

    /**
     * Returns successful reads omitted because the log was full, after source join.
     * Counting callbacks continue even for these omitted entries.
     * @return nonnegative number of unretained read entries
     */
    public long overflowReadCount() { return overflowReads; }

    /**
     * Returns one retained read's host completion instant after source join.
     * @param index zero-based index below {@link #capturedReadCount()}
     * @return UTC epoch nanoseconds, not a raw monotonic clock reading
     * @throws IndexOutOfBoundsException if the entry was not captured
     */
    public long completionTimeNanos(int index) {
        return completionTimes[Objects.checkIndex(index, capturedReads)];
    }

    /**
     * Returns one retained read's monotonic completion offset after source join.
     * @param index zero-based index below {@link #capturedReadCount()}
     * @return nanoseconds since the first read completion; zero for entry zero
     * @throws IndexOutOfBoundsException if the entry was not captured
     */
    public long elapsedNanos(int index) {
        return elapsedTimes[Objects.checkIndex(index, capturedReads)];
    }

    /**
     * Returns one retained read's actual returned complex-sample count after join.
     * @param index zero-based index below {@link #capturedReadCount()}
     * @return positive number of complete complex scans in the READBUF response
     * @throws IndexOutOfBoundsException if the entry was not captured
     */
    public int sampleCount(int index) {
        return sampleCounts[Objects.checkIndex(index, capturedReads)];
    }
}
