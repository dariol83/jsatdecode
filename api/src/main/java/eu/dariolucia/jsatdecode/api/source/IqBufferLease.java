package eu.dariolucia.jsatdecode.api.source;

import java.util.Objects;

/**
 * A single closeable ownership claim on pooled interleaved I/Q storage.
 * Acquired leases start exclusively writable. {@link #publish(int, int)}
 * irreversibly changes the same lease to read-only and fixes its valid window.
 * Indexed reads are relative to that window; its offset is measured in complex
 * samples from the beginning of the underlying capacity.
 *
 * <p>A synchronous caller may borrow an open read-only lease for the duration
 * of a call. Before enqueueing or otherwise extending its lifetime, an
 * asynchronous consumer must call {@link #retain()} and eventually close that
 * separate lease on success, rejection, cancellation and failure. Each open
 * lease must be closed exactly once. Closing a lease while another thread is
 * using that same lease makes subsequent operations fail; use a retained lease
 * for independently managed lifetimes. No public method exposes the backing
 * array.</p>
 *
 * <p>All operations are linearized with pool shutdown and other lease
 * operations. Access, retention and publication after close fail. A duplicate
 * close also fails and is counted by the pool. Published data cannot be
 * written through any lease, and storage is not recycled until the last lease
 * closes.</p>
 */
public final class IqBufferLease implements AutoCloseable {
    private final IqBufferPool pool;
    private IqBufferPool.Buffer buffer;
    private boolean published;
    private int offset, length;

    IqBufferLease(IqBufferPool pool, IqBufferPool.Buffer buffer) {
        this.pool = pool;
        this.buffer = buffer;
    }

    private IqBufferLease(IqBufferPool pool, IqBufferPool.Buffer buffer, int offset, int length) {
        this(pool, buffer);
        this.published = true;
        this.offset = offset;
        this.length = length;
    }

    /**
     * Returns the size class capacity, which can exceed the requested or valid
     * sample count.
     *
     * @return complex-sample capacity
     * @throws IllegalStateException if this lease is closed
     */
    public int capacity() {
        synchronized (pool.lock) {
            return requireOpen().capacity();
        }
    }

    /**
     * Returns whether this open lease has been published read-only.
     *
     * @return true after publication
     * @throws IllegalStateException if this lease is closed
     */
    public boolean isReadOnly() {
        synchronized (pool.lock) {
            requireOpen();
            return published;
        }
    }

    /**
     * Writes one complete I/Q pair before publication.
     *
     * @param index complex-sample index within the capacity
     * @param i in-phase value
     * @param q quadrature value
     * @throws IndexOutOfBoundsException if the index is outside the capacity
     * @throws IllegalStateException if published or closed
     */
    public void put(int index, float i, float q) {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requireWritable();
            Objects.checkIndex(index, current.capacity());
            current.put(index, i, q);
        }
    }

    /**
     * Copies interleaved pairs into writable storage. The caller must not
     * concurrently modify the source array during this call.
     *
     * @param source source interleaved I/Q values
     * @param sourceOffsetFloats offset in floats, not complex samples
     * @param targetOffsetSamples destination offset in complex samples
     * @param complexSamples number of complete pairs to copy
     * @throws NullPointerException if source is null
     * @throws IndexOutOfBoundsException if either range is invalid
     * @throws IllegalStateException if published or closed
     */
    public void putInterleaved(float[] source, int sourceOffsetFloats, int targetOffsetSamples, int complexSamples) {
        Objects.requireNonNull(source, "source");
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requireWritable();
            Objects.checkFromIndexSize(targetOffsetSamples, complexSamples, current.capacity());
            Objects.checkFromIndexSize(sourceOffsetFloats, complexSamples * 2, source.length);
            current.putAll(source, sourceOffsetFloats, targetOffsetSamples, complexSamples);
        }
    }

    /**
     * Publishes a valid window and relinquishes write access on this same
     * lease. The caller must have initialized every sample in the window. A
     * failed validation leaves the lease writable. Publication does not create
     * another ownership claim; close this lease after its final use.
     *
     * @param offsetSamples first valid complex sample within capacity
     * @param complexSamples number of valid complex samples, zero allowed
     * @throws IndexOutOfBoundsException if the window is outside capacity
     * @throws IllegalStateException if already published or closed
     */
    public void publish(int offsetSamples, int complexSamples) {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requireWritable();
            Objects.checkFromIndexSize(offsetSamples, complexSamples, current.capacity());
            offset = offsetSamples;
            length = complexSamples;
            published = true;
        }
    }

    /**
     * Creates an independently closeable claim on published storage. This is
     * required before handing a lease to an asynchronous consumer, even if the
     * pool has already been closed.
     *
     * @return a new read-only lease with the same valid window
     * @throws IllegalStateException if this lease is writable or closed, or the
     *         number of concurrent claims cannot be represented
     */
    public IqBufferLease retain() {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requirePublished();
            IqBufferLease retained = new IqBufferLease(pool, current, offset, length);
            pool.retained(current);
            return retained;
        }
    }

    /**
     * Returns the published window's offset within capacity.
     *
     * @return offset in complex samples
     * @throws IllegalStateException if writable or closed
     */
    public int offsetSamples() {
        synchronized (pool.lock) {
            requirePublished();
            return offset;
        }
    }

    /**
     * Returns the number of valid published complex samples.
     *
     * @return valid sample count
     * @throws IllegalStateException if writable or closed
     */
    public int complexSamples() {
        synchronized (pool.lock) {
            requirePublished();
            return length;
        }
    }

    /**
     * Reads an in-phase component without allocating.
     *
     * @param index complex-sample index relative to the published window
     * @return in-phase value
     * @throws IndexOutOfBoundsException if outside the valid window
     * @throws IllegalStateException if writable or closed
     */
    public float i(int index) {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requirePublished();
            return current.i(offset + Objects.checkIndex(index, length));
        }
    }

    /**
     * Reads a quadrature component without allocating.
     *
     * @param index complex-sample index relative to the published window
     * @return quadrature value
     * @throws IndexOutOfBoundsException if outside the valid window
     * @throws IllegalStateException if writable or closed
     */
    public float q(int index) {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requirePublished();
            return current.q(offset + Objects.checkIndex(index, length));
        }
    }

    /**
     * Copies the valid window into independently owned legacy storage. This
     * boundary deliberately retains the defensive-copy contract of
     * {@link ComplexSampleBlock}; closing this lease does not affect the result.
     *
     * @param sampleIndex absolute index of the first valid sample, nonnegative
     * @param timestampNanos UTC epoch-nanosecond timestamp
     * @param sampleRate positive finite complex samples per second
     * @return an independent immutable legacy block
     * @throws IllegalArgumentException if sample index or rate is invalid
     * @throws IllegalStateException if writable or closed
     */
    public ComplexSampleBlock toComplexSampleBlock(long sampleIndex, long timestampNanos, double sampleRate) {
        synchronized (pool.lock) {
            IqBufferPool.Buffer current = requirePublished();
            float[] copy = new float[length * 2];
            current.copyTo(copy, offset, length);
            return new ComplexSampleBlock(copy, length, sampleIndex, timestampNanos, sampleRate);
        }
    }

    /**
     * Releases exactly this ownership claim. The last close recycles or discards
     * the backing storage; closing one lease never closes any retained lease.
     *
     * @throws IllegalStateException if this lease was already closed
     */
    @Override
    public void close() {
        synchronized (pool.lock) {
            if (buffer == null) {
                pool.invalidRelease();
                throw new IllegalStateException("Lease is already closed");
            }
            IqBufferPool.Buffer current = buffer;
            buffer = null;
            pool.release(current);
        }
    }

    private IqBufferPool.Buffer requireOpen() {
        if (buffer == null) throw new IllegalStateException("Lease is closed");
        return buffer;
    }

    private IqBufferPool.Buffer requireWritable() {
        IqBufferPool.Buffer current = requireOpen();
        if (published) throw new IllegalStateException("Lease is read-only");
        return current;
    }

    private IqBufferPool.Buffer requirePublished() {
        IqBufferPool.Buffer current = requireOpen();
        if (!published) throw new IllegalStateException("Lease is not published");
        return current;
    }
}
