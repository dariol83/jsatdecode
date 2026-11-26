package eu.dariolucia.jsatdecode.api.source;

import java.util.Optional;

/**
 * An opt-in, bounded pool of interleaved float32 I/Q storage. A successful acquisition
 * owns one exclusive writable lease; publication makes it read-only, and each
 * asynchronous owner must retain and later close its own lease.
 *
 * <p>Capacity classes are powers of two complex samples, from one through
 * 2^29. The budget counts the full float-array payload of both checked-out and
 * idle buffers, not object headers, lease objects or arrays already evicted for
 * garbage collection. A zero-sample request uses the smallest class. When a
 * new class needs space, idle buffers are evicted largest-class first. If the
 * requested class still cannot fit, acquisition returns empty immediately; it
 * never waits for an owner to release storage. Allocation errors propagate.
 * Contents of acquired storage are unspecified and must be initialized before
 * publication. No background thread, finalizer or garbage collector releases
 * a lease.</p>
 *
 * <p>Operations are thread-safe and linearized with respect to closing and
 * recycling, including indexed reads and writes. They can briefly contend on a
 * pool lock; acquisition does not wait for capacity. Closing the pool discards
 * idle storage and prevents further acquisition, but does not invalidate
 * checked-out leases. Those owners may still read, publish, retain and close;
 * their storage is discarded when the last owner closes.</p>
 */
public final class IqBufferPool implements AutoCloseable {
    private static final int MAX_CLASS = 29;
    private static final int MAX_SAMPLES = 1 << MAX_CLASS;
    private static final int BYTES_PER_SAMPLE = 2 * Float.BYTES;

    final Object lock = new Object();
    private final Buffer[] idle = new Buffer[MAX_CLASS + 1];
    private final long budgetBytes;
    private long activeBytes, retainedBytes, outstandingLeases;
    private long peakActiveBytes, peakRetainedBytes, peakReservedBytes;
    private long allocations, reuses, exhaustions, invalidReleases;
    private boolean closed;

    /**
     * Creates a pool with a fixed payload budget. Less than eight bytes is
     * permitted but cannot accommodate even the smallest size class.
     *
     * @param budgetBytes maximum active plus retained float-array payload bytes
     * @throws IllegalArgumentException if the budget is negative
     */
    public IqBufferPool(long budgetBytes) {
        if (budgetBytes < 0) throw new IllegalArgumentException("Negative pool budget");
        this.budgetBytes = budgetBytes;
    }

    /**
     * Attempts to acquire an exclusively writable buffer. The result has a
     * capacity at least as large as requested and exactly one outstanding
     * lease. It contains unspecified data, including on reuse.
     *
     * @param minimumComplexSamples required complex-sample capacity, including zero
     * @return a writable lease, or empty if the class cannot fit in the budget
     * @throws IllegalArgumentException if the requested capacity is negative or
     *         greater than 2^29 complex samples
     * @throws IllegalStateException if the pool has been closed
     */
    public Optional<IqBufferLease> tryAcquire(int minimumComplexSamples) {
        if (minimumComplexSamples < 0 || minimumComplexSamples > MAX_SAMPLES) {
            throw new IllegalArgumentException("Invalid complex-sample capacity");
        }
        int sizeClass = minimumComplexSamples <= 1 ? 0
                : Integer.SIZE - Integer.numberOfLeadingZeros(minimumComplexSamples - 1);
        int capacity = 1 << sizeClass;
        long bytes = (long) capacity * BYTES_PER_SAMPLE;
        synchronized (lock) {
            if (closed) throw new IllegalStateException("Pool is closed");
            Buffer buffer = idle[sizeClass];
            if (buffer != null) {
                // Construct the result before removing the idle buffer so an
                // allocation failure cannot strand it outside pool accounting.
                IqBufferLease lease = new IqBufferLease(this, buffer);
                Optional<IqBufferLease> result = Optional.of(lease);
                idle[sizeClass] = buffer.next;
                buffer.next = null;
                retainedBytes -= bytes;
                activeBytes += bytes;
                outstandingLeases++;
                buffer.references = 1;
                reuses++;
                updatePeaks();
                return result;
            }
            if (bytes > budgetBytes - activeBytes) {
                exhaustions++;
                return Optional.empty();
            }
            for (int index = MAX_CLASS; index >= 0 && bytes > budgetBytes - activeBytes - retainedBytes; index--) {
                while (idle[index] != null && bytes > budgetBytes - activeBytes - retainedBytes) {
                    Buffer evicted = idle[index];
                    idle[index] = evicted.next;
                    evicted.next = null;
                    retainedBytes -= evicted.bytes();
                }
            }
            // Only publish accounting after all allocations succeed. Evicted
            // idle arrays are no longer pool-owned even if allocation fails.
            buffer = new Buffer(capacity, sizeClass);
            IqBufferLease lease = new IqBufferLease(this, buffer);
            Optional<IqBufferLease> result = Optional.of(lease);
            buffer.references = 1;
            activeBytes += bytes;
            outstandingLeases++;
            allocations++;
            updatePeaks();
            return result;
        }
    }

    /**
     * Returns a consistent snapshot of pool accounting and lifetime counters.
     * Active bytes count each checked-out array once, regardless of the number
     * of leases sharing it. Retained bytes are idle arrays held for reuse.
     *
     * @return immutable metrics snapshot
     */
    public Statistics statistics() {
        synchronized (lock) {
            return new Statistics(budgetBytes, allocations, reuses, outstandingLeases,
                    activeBytes, retainedBytes, peakActiveBytes, peakRetainedBytes,
                    peakReservedBytes, exhaustions, invalidReleases, closed);
        }
    }

    /**
     * Stops acquisition and releases idle arrays from the pool. This operation
     * is idempotent and does not close outstanding leases.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (int i = 0; i < idle.length; i++) idle[i] = null;
            retainedBytes = 0;
        }
    }

    void retained(Buffer buffer) {
        if (buffer.references == Integer.MAX_VALUE) throw new IllegalStateException("Too many leases for buffer");
        buffer.references++;
        outstandingLeases++;
    }

    void release(Buffer buffer) {
        outstandingLeases--;
        if (--buffer.references == 0) {
            activeBytes -= buffer.bytes();
            if (!closed) {
                buffer.next = idle[buffer.sizeClass];
                idle[buffer.sizeClass] = buffer;
                retainedBytes += buffer.bytes();
                updatePeaks();
            }
        }
    }

    void invalidRelease() {
        invalidReleases++;
    }

    private void updatePeaks() {
        peakActiveBytes = Math.max(peakActiveBytes, activeBytes);
        peakRetainedBytes = Math.max(peakRetainedBytes, retainedBytes);
        peakReservedBytes = Math.max(peakReservedBytes, activeBytes + retainedBytes);
    }

    // Intrusive idle lists make the last release allocation-free, even under
    // memory pressure. The backing array is never returned to a caller.
    static final class Buffer {
        private final float[] data;
        private final int sizeClass;
        private int references;
        private Buffer next;

        private Buffer(int capacity, int sizeClass) {
            data = new float[capacity * 2];
            this.sizeClass = sizeClass;
        }

        int capacity() { return data.length / 2; }
        long bytes() { return (long) data.length * Float.BYTES; }
        float i(int index) { return data[index * 2]; }
        float q(int index) { return data[index * 2 + 1]; }
        void put(int index, float i, float q) { data[index * 2] = i; data[index * 2 + 1] = q; }
        void putAll(float[] source, int sourceOffset, int targetOffset, int count) {
            System.arraycopy(source, sourceOffset, data, targetOffset * 2, count * 2);
        }
        void copyTo(float[] destination, int offset, int count) {
            System.arraycopy(data, offset * 2, destination, 0, count * 2);
        }
    }

    /**
     * Consistent pool accounting. Byte values cover float payload only.
     *
     * @param budgetBytes configured maximum active plus retained bytes
     * @param allocations successfully allocated arrays
     * @param reuses successful acquisitions from an idle size class
     * @param outstandingLeases currently open writable and read-only leases
     * @param activeBytes bytes in distinct checked-out arrays
     * @param retainedBytes bytes in idle arrays held for reuse
     * @param peakActiveBytes maximum observed active bytes
     * @param peakRetainedBytes maximum observed retained bytes
     * @param peakReservedBytes maximum observed active plus retained bytes
     * @param exhaustions acquisitions refused because of the budget
     * @param invalidReleases attempted duplicate lease closes
     * @param closed whether the pool has been closed
     */
    public record Statistics(long budgetBytes, long allocations, long reuses,
                             long outstandingLeases, long activeBytes, long retainedBytes,
                             long peakActiveBytes, long peakRetainedBytes, long peakReservedBytes,
                             long exhaustions, long invalidReleases, boolean closed) { }
}
