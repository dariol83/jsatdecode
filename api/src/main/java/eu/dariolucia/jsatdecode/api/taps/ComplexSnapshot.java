package eu.dariolucia.jsatdecode.api.taps;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

/**
 * Immutable best-effort tap samples owned by this snapshot and never overwritten.
 * Readers may run concurrently with DSP; snapshot acquisition must not stall producers.
 */
public final class ComplexSnapshot {
    private final ComplexSampleBlock block;

    /**
     * Copies valid samples into snapshot-owned storage.
     *
     * @param iq             interleaved I/Q
     * @param complexSamples valid sample count
     * @param sampleRate     positive finite samples/second
     * @param timestampNanos UTC epoch-nanosecond timestamp
     */
    public ComplexSnapshot(float[] iq, int complexSamples, double sampleRate, long timestampNanos) {
        block = new ComplexSampleBlock(iq, complexSamples, 0, timestampNanos, sampleRate);
    }

    /**
     * Returns a detached copy.
     *
     * @return interleaved samples
     */
    public float[] iq() {
        return block.iq();
    }

    /**
     * Reads one immutable in-phase value without allocating or copying an array.
     *
     * @param index complex sample offset
     * @return in-phase component
     * @throws IndexOutOfBoundsException for an invalid offset
     */
    public float i(int index) {
        return block.i(index);
    }

    /**
     * Reads one immutable quadrature value without allocating or copying an array.
     *
     * @param index complex sample offset
     * @return quadrature component
     * @throws IndexOutOfBoundsException for an invalid offset
     */
    public float q(int index) {
        return block.q(index);
    }

    /**
     * Returns sample count.
     *
     * @return complex samples
     */
    public int complexSamples() {
        return block.complexSamples();
    }

    /**
     * Returns sample rate.
     *
     * @return samples per second
     */
    public double sampleRate() {
        return block.sampleRate();
    }

    /**
     * Returns observation timestamp.
     *
     * @return UTC epoch nanoseconds
     */
    public long timestampNanos() {
        return block.timestampNanos();
    }
}
