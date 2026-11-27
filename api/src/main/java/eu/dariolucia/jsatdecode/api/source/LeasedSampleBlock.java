package eu.dariolucia.jsatdecode.api.source;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.function.Consumer;

/**
 * Independently closeable, read-only ownership of published IQ and its stream
 * metadata. Synchronous consumers may borrow an open block; asynchronous
 * consumers must retain a separate block before enqueueing and close it after
 * the final read, including rejection and failure. Retention uses atomics and
 * does not acquire the pool monitor; the final close releases the pool lease.
 * No method exposes the mutable backing array.
 */
public final class LeasedSampleBlock implements AutoCloseable {
    private static final AtomicIntegerFieldUpdater<LeasedSampleBlock> CLOSED =
            AtomicIntegerFieldUpdater.newUpdater(LeasedSampleBlock.class, "closed");
    private final Shared shared;
    private final long sampleIndex, timestampNanos;
    private final double sampleRate;
    private final int complexSamples;
    private volatile int closed;

    private LeasedSampleBlock(Shared shared, long sampleIndex, long timestampNanos,
                              double sampleRate, int complexSamples) {
        this.shared = shared;
        this.sampleIndex = sampleIndex;
        this.timestampNanos = timestampNanos;
        this.sampleRate = sampleRate;
        this.complexSamples = complexSamples;
    }

    /**
     * Creates an independent claim on an already published lease. The caller
     * keeps its own lease and must still close it separately. A failed call does
     * not transfer ownership.
     *
     * @param lease open published lease
     * @param sampleIndex nonnegative absolute first sample index
     * @param timestampNanos UTC epoch nanoseconds
     * @param sampleRate positive finite complex sample rate
     * @return an independently closeable block
     * @throws IllegalArgumentException if the index or rate is invalid
     * @throws IllegalStateException if the lease is closed or unpublished
     */
    public static LeasedSampleBlock from(IqBufferLease lease, long sampleIndex,
                                         long timestampNanos, double sampleRate) {
        Objects.requireNonNull(lease, "lease");
        if (sampleIndex < 0) throw new IllegalArgumentException("Negative sampleIndex");
        if (!Double.isFinite(sampleRate) || sampleRate <= 0) throw new IllegalArgumentException("Invalid sampleRate");
        IqBufferLease owned = lease.retain();
        try {
            return new LeasedSampleBlock(new Shared(owned), sampleIndex, timestampNanos,
                    sampleRate, owned.complexSamples());
        } catch (RuntimeException | Error e) {
            owned.close();
            throw e;
        }
    }

    /** Returns the valid length.
     * @return complex sample count
     * @throws IllegalStateException if closed */
    public int complexSamples() { requireOpen(); return complexSamples; }
    /** Returns the stream position.
     * @return absolute first sample index
     * @throws IllegalStateException if closed */
    public long sampleIndex() { requireOpen(); return sampleIndex; }
    /** Returns the sample timestamp.
     * @return UTC epoch nanoseconds
     * @throws IllegalStateException if closed */
    public long timestampNanos() { requireOpen(); return timestampNanos; }
    /** Returns the sampling rate.
     * @return complex samples per second
     * @throws IllegalStateException if closed */
    public double sampleRate() { requireOpen(); return sampleRate; }

    /**
     * Retains an independent claim without taking the pool monitor. When a
     * synchronous producer owns the borrowed block for the entire offer call,
     * closing a rejected retained claim also cannot be the final release.
     *
     * @return a new owner of the same published samples
     * @throws IllegalStateException if closed or the claim count overflows
     */
    public LeasedSampleBlock retain() {
        requireOpen();
        // Allocate before increasing the count, so an allocation error cannot leak.
        LeasedSampleBlock child = new LeasedSampleBlock(shared, sampleIndex, timestampNanos,
                sampleRate, complexSamples);
        for (;;) {
            int count = Shared.REFERENCES.get(shared);
            if (count == 0 || closed != 0) throw new IllegalStateException("Block is closed");
            if (count == Integer.MAX_VALUE) throw new IllegalStateException("Too many block owners");
            if (Shared.REFERENCES.compareAndSet(shared, count, count + 1)) break;
        }
        // A concurrent close may have begun after the open check. A
        // successful increment linearizes this retain before that close; the
        // shared count cannot reach zero or recycle while the claim exists.
        return child;
    }

    /**
     * Calls a reader with a thread-confined view; neither the view nor its
     * accessors may be used after the callback returns or on another thread.
     * A temporary pool claim prevents concurrent close from recycling storage.
     *
     * @param reader synchronous reader
     * @throws IllegalStateException if closed
     */
    public void withView(Consumer<? super IqSampleView> reader) {
        Objects.requireNonNull(reader, "reader");
        requireOpen();
        shared.lease.withReadAccess(access -> reader.accept(new View(access, sampleIndex, timestampNanos, sampleRate)));
    }

    /**
     * Copies into the legacy retainable immutable representation.
     *
     * @return independently owned legacy block
     * @throws IllegalStateException if closed
     */
    public ComplexSampleBlock toComplexSampleBlock() {
        requireOpen();
        return shared.lease.toComplexSampleBlock(sampleIndex, timestampNanos, sampleRate);
    }

    /**
     * Releases this claim. Only the final block owner releases its pool lease.
     *
     * @throws IllegalStateException on a duplicate close
     */
    @Override
    public void close() {
        if (!CLOSED.compareAndSet(this, 0, 1)) throw new IllegalStateException("Block is already closed");
        if (Shared.REFERENCES.decrementAndGet(shared) == 0) shared.lease.close();
    }

    private void requireOpen() {
        if (closed != 0) throw new IllegalStateException("Block is closed");
    }

    private static final class Shared {
        private static final AtomicIntegerFieldUpdater<Shared> REFERENCES =
                AtomicIntegerFieldUpdater.newUpdater(Shared.class, "references");
        final IqBufferLease lease;
        private volatile int references = 1;
        Shared(IqBufferLease lease) { this.lease = lease; }
    }

    private record View(IqReadAccess access, long sampleIndex, long timestampNanos,
                        double sampleRate) implements IqSampleView {
        @Override public int complexSamples() { return access.complexSamples(); }
        @Override public float i(int index) { return access.i(index); }
        @Override public float q(int index) { return access.q(index); }
        @Override public long sampleIndex() { access.complexSamples(); return sampleIndex; }
        @Override public long timestampNanos() { access.complexSamples(); return timestampNanos; }
        @Override public double sampleRate() { access.complexSamples(); return sampleRate; }
    }
}
