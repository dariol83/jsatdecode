package eu.dariolucia.jsatdecode.core.buffers;

import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Bounded latest-data tap for RF, baseband or symbols. Exactly one producer owns
 * writes; any number of observers may request snapshots concurrently. Producer
 * ownership may move between threads only with external synchronization.
 * Each I/Q pair occupies one atomic primitive long, preserving raw float bits.
 * Writes never allocate, wait, retry, invoke observers or acquire a lock.
 * Scalar writes have constant work; block writes copy at most capacity samples.
 * Readers try at most three times and return an empty snapshot under contention.
 * Snapshot allocation and defensive copying happen only on the observer thread.
 */
public final class ComplexFloatRingBuffer implements ComplexTap {
    private static final int SNAPSHOT_ATTEMPTS = 3;
    private final AtomicLongArray pairs;
    private final double sampleRate;
    private final AtomicLong missedSnapshots = new AtomicLong();
    private volatile long revision;
    private volatile long samplesWritten;
    private volatile long timestampNanos;

    /**
     * Allocates the fixed sample storage during setup.
     *
     * @param capacitySamples positive complex sample capacity
     * @param sampleRate      positive finite complex samples per second
     * @throws IllegalArgumentException for invalid capacity or rate
     */
    public ComplexFloatRingBuffer(int capacitySamples, double sampleRate) {
        if (capacitySamples <= 0 || capacitySamples > Integer.MAX_VALUE / 2) {
            throw new IllegalArgumentException("Invalid complex sample capacity");
        }
        if (!Double.isFinite(sampleRate) || sampleRate <= 0) {
            throw new IllegalArgumentException("Invalid sample rate");
        }
        pairs = new AtomicLongArray(capacitySamples);
        this.sampleRate = sampleRate;
    }

    /**
     * Publishes one complex sample, overwriting the oldest retained sample.
     *
     * @param i in-phase value, including NaN/infinity if present in the stream
     * @param q quadrature value
     * @throws ArithmeticException if the lifetime sample count would overflow
     */
    public void write(float i, float q) {
        long end = Math.addExact(samplesWritten, 1);
        revision++;
        pairs.set((int) ((end - 1) % pairs.length()), pack(i, q));
        timestampNanos = eu.dariolucia.jsatdecode.util.UtcTime.nowNanos();
        samplesWritten = end;
        revision++;
    }

    /**
     * Publishes the valid prefix of an interleaved array.
     *
     * @param interleavedIq caller-owned I0,Q0,I1,Q1 array, stable until return
     * @param samples       nonnegative complex sample count
     * @throws IndexOutOfBoundsException for an invalid range
     */
    public void write(float[] interleavedIq, int samples) {
        write(interleavedIq, 0, samples);
    }

    /**
     * Publishes a range, counting all input but retaining only its newest capacity
     * samples. Oversized writes skip the obsolete prefix. Zero samples is a no-op.
     *
     * @param interleavedIq caller-owned array, never retained or modified
     * @param offsetSamples nonnegative offset measured in complex samples
     * @param samples       nonnegative complex sample count
     * @throws NullPointerException      if the array is null
     * @throws IndexOutOfBoundsException for an invalid range
     * @throws ArithmeticException       if the lifetime sample count would overflow
     */
    public void write(float[] interleavedIq, int offsetSamples, int samples) {
        Objects.requireNonNull(interleavedIq, "interleavedIq");
        Objects.checkFromIndexSize(offsetSamples, samples, interleavedIq.length / 2);
        if (samples == 0) return;
        long end = Math.addExact(samplesWritten, samples);
        int kept = Math.min(samples, pairs.length());
        int source = (offsetSamples + samples - kept) * 2;
        int slot = (int) ((end - kept) % pairs.length());

        // An odd revision invalidates concurrent reads. Publish the complete batch
        // with an even revision; producer work never depends on reader progress.
        revision++;
        for (int n = 0; n < kept; n++) {
            pairs.set(slot, pack(interleavedIq[source], interleavedIq[source + 1]));
            source += 2;
            if (++slot == pairs.length()) slot = 0;
        }
        timestampNanos = eu.dariolucia.jsatdecode.util.UtcTime.nowNanos();
        samplesWritten = end;
        revision++;
    }

    /**
     * Returns a bounded immutable snapshot, equivalent to snapshotLatest.
     *
     * @param maximumSamples nonnegative limit; zero returns no samples
     * @return chronological latest samples, or empty during sustained contention
     * @throws IllegalArgumentException if the limit is negative
     */
    @Override
    public ComplexSnapshot snapshot(int maximumSamples) {
        return snapshotLatest(maximumSamples);
    }

    /**
     * Tries to copy a coherent chronological suffix without coordinating with the
     * producer. At most three capacity-bounded passes are attempted. A failed read
     * returns empty and increments missedSnapshots; callers must tolerate this.
     * The returned storage is owned by the snapshot and is never reused.
     *
     * @param maximumSamples nonnegative maximum number of complex samples
     * @return immutable snapshot, possibly shorter than requested
     * @throws IllegalArgumentException if the limit is negative
     */
    public ComplexSnapshot snapshotLatest(int maximumSamples) {
        if (maximumSamples < 0) throw new IllegalArgumentException("Negative snapshot size");
        int limit = Math.min(maximumSamples, pairs.length());
        if (limit == 0) return new ComplexSnapshot(new float[0], 0, sampleRate, timestampNanos);
        float[] copy = null;
        for (int attempt = 0; attempt < SNAPSHOT_ATTEMPTS; attempt++) {
            long before = revision;
            if ((before & 1) != 0) continue;
            long end = samplesWritten;
            long time = timestampNanos;
            int count = (int) Math.min(end, limit);
            if (copy == null || copy.length < count * 2) copy = new float[count * 2];
            int slot = (int) ((end - count) % pairs.length());
            for (int n = 0; n < count; n++) {
                long packed = pairs.get(slot);
                copy[n * 2] = Float.intBitsToFloat((int) (packed >>> 32));
                copy[n * 2 + 1] = Float.intBitsToFloat((int) packed);
                if (++slot == pairs.length()) slot = 0;
            }
            // All cell accesses and revision reads are volatile. A changed revision
            // rejects overwritten or partially published batches, without retries on DSP.
            if (revision == before) return new ComplexSnapshot(copy, count, sampleRate, time);
        }
        missedSnapshots.incrementAndGet();
        return new ComplexSnapshot(new float[0], 0, sampleRate, timestampNanos);
    }

    /**
     * Returns the fixed retention limit.
     *
     * @return complex sample capacity
     */
    public int capacitySamples() {
        return pairs.length();
    }

    /**
     * Returns the configured complex sample rate.
     *
     * @return samples per second
     */
    public double sampleRate() {
        return sampleRate;
    }

    /**
     * Returns the published lifetime count, including skipped oversized prefixes.
     *
     * @return number of samples written
     */
    public long samplesWritten() {
        return samplesWritten;
    }

    /**
     * Counts samples that have aged out of retention, independent of reader activity.
     * This is not a per-consumer unread/drop count.
     *
     * @return cumulative overwritten/obsolete sample count
     */
    public long overwrittenSamples() {
        return Math.max(0, samplesWritten - pairs.length());
    }

    /**
     * Counts snapshot requests abandoned after bounded contention attempts.
     *
     * @return missed snapshot count
     */
    public long missedSnapshots() {
        return missedSnapshots.get();
    }

    private static long pack(float i, float q) {
        return ((long) Float.floatToRawIntBits(i) << 32) | (Float.floatToRawIntBits(q) & 0xffffffffL);
    }
}
