package eu.dariolucia.jsatdecode.api.source;

import java.util.Arrays;
import java.util.Objects;

/**
 * Immutable input block in I0,Q0,I1,Q1 order. Construction copies the valid prefix;
 * array access returns a copy. Receivers must not mutate producer buffers.
 * Timestamp is UTC epoch nanoseconds; sampleIndex is the recording/stream
 * position. Construct once per block, never once per sample. Safe to share.
 */
public final class ComplexSampleBlock {
    private final float[] iq;
    private final int complexSamples;
    private final long sampleIndex, timestampNanos;
    private final double sampleRate;

    /**
     * Snapshots the valid prefix.
     *
     * @param iq             interleaved input array
     * @param complexSamples number of complex samples, zero allowed
     * @param sampleIndex    nonnegative absolute first sample index
     * @param timestampNanos UTC epoch-nanosecond timestamp, may be negative
     * @param sampleRate     positive finite complex samples per second
     */
    public ComplexSampleBlock(float[] iq, int complexSamples, long sampleIndex, long timestampNanos, double sampleRate) {
        Objects.requireNonNull(iq, "iq");
        if (complexSamples < 0 || complexSamples > iq.length / 2)
            throw new IllegalArgumentException("Invalid complexSamples");
        if (sampleIndex < 0) throw new IllegalArgumentException("Negative sampleIndex");
        if (!Double.isFinite(sampleRate) || sampleRate <= 0) throw new IllegalArgumentException("Invalid sampleRate");
        this.iq = Arrays.copyOf(iq, complexSamples * 2);
        this.complexSamples = complexSamples;
        this.sampleIndex = sampleIndex;
        this.timestampNanos = timestampNanos;
        this.sampleRate = sampleRate;
    }

    /**
     * Copies valid samples; prefer indexed access in DSP loops.
     *
     * @return detached interleaved array
     */
    public float[] iq() {
        return iq.clone();
    }

    /**
     * Reads a primitive without allocating.
     *
     * @param index complex sample offset
     * @return in-phase component
     */
    public float i(int index) {
        Objects.checkIndex(index, complexSamples);
        return iq[index * 2];
    }

    /**
     * Reads a primitive without allocating.
     *
     * @param index complex sample offset
     * @return quadrature component
     */
    public float q(int index) {
        Objects.checkIndex(index, complexSamples);
        return iq[index * 2 + 1];
    }

    /**
     * Returns valid sample count.
     *
     * @return complex sample count
     */
    public int complexSamples() {
        return complexSamples;
    }

    /**
     * Returns absolute position.
     *
     * @return first sample index
     */
    public long sampleIndex() {
        return sampleIndex;
    }

    /**
     * Returns UTC epoch-nanosecond capture timestamp.
     *
     * @return nanoseconds
     */
    public long timestampNanos() {
        return timestampNanos;
    }

    /**
     * Returns complex sample rate.
     *
     * @return samples per second
     */
    public double sampleRate() {
        return sampleRate;
    }
}