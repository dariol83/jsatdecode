package eu.dariolucia.jsatdecode.dsp.fft;

import java.util.Arrays;
import java.util.Objects;

/**
 * Fixed-size, allocation-free exponential smoothing on the analysis worker.
 * Thread-confined mutable state; keep a separate instance per independent stream.
 * Values are averaged in the domain supplied (linear power or dB), without hidden
 * conversion. The first available value seeds each bin. NaNs skip that bin's
 * update, retaining its last estimate or NaN if no estimate exists. Infinities
 * are rejected before state changes. This object never retains input/output arrays.
 */
public final class ExponentialAverager {
    private final double[] values;
    private final double alpha;

    /**
     * Allocates fixed state during setup.
     *
     * @param size  positive bin count
     * @param alpha finite current-value weight in (0,1]; one disables smoothing
     * @throws IllegalArgumentException for invalid size or weight
     */
    public ExponentialAverager(int size, double alpha) {
        if (size <= 0 || !Double.isFinite(alpha) || alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException("Invalid averaging size or alpha");
        }
        values = new double[size];
        this.alpha = alpha;
        reset();
    }

    /**
     * Applies alpha*current + (1-alpha)*previous to available bins and copies state out.
     *
     * @param current     non-null length-size measurements; NaN denotes unavailable
     * @param destination non-null length-size output, may alias current
     * @throws IllegalArgumentException for wrong lengths or infinite measurements
     */
    public void update(double[] current, double[] destination) {
        validate(current);
        validate(destination);
        for (double value : current) {
            if (Double.isInfinite(value)) throw new IllegalArgumentException("Infinite measurement");
        }
        for (int n = 0; n < values.length; n++) {
            double next = current[n];
            if (!Double.isNaN(next)) {
                values[n] = Double.isNaN(values[n]) || alpha == 1
                        ? next : alpha * next + (1 - alpha) * values[n];
            }
            destination[n] = values[n];
        }
    }

    /**
     * Copies current state into caller-owned storage without modifying it.
     *
     * @param destination non-null length-size output; unavailable bins are NaN
     * @throws IllegalArgumentException for wrong length
     */
    public void copyTo(double[] destination) {
        validate(destination);
        System.arraycopy(values, 0, destination, 0, values.length);
    }

    /**
     * Discards history; all bins become unavailable until seeded again.
     */
    public void reset() {
        Arrays.fill(values, Double.NaN);
    }

    /**
     * Returns the fixed bin count.
     *
     * @return number of averaged bins
     */
    public int size() {
        return values.length;
    }

    /**
     * Returns the current-value weight.
     *
     * @return alpha in (0,1]
     */
    public double alpha() {
        return alpha;
    }

    private void validate(double[] array) {
        Objects.requireNonNull(array, "array");
        if (array.length != values.length) throw new IllegalArgumentException("Wrong averaging length");
    }
}
