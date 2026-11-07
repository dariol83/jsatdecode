package eu.dariolucia.jsatdecode.dsp.fft;

import java.util.Objects;

/**
 * Immutable cached window coefficients, shareable when callers use disjoint buffers.
 */
public final class Window {
    private final WindowFunction function;
    private final double[] coefficients;
    private final double coherentGain;

    /**
     * Creates a Hann window, the default for spectrum observations.
     *
     * @param size positive sample count
     */
    public Window(int size) {
        this(WindowFunction.HANN, size);
    }

    /**
     * Precomputes coefficients during setup. No normalization is applied.
     *
     * @param function non-null symmetric window
     * @param size     positive sample count; need not be a power of two
     * @throws IllegalArgumentException for a nonpositive size
     */
    public Window(WindowFunction function, int size) {
        this.function = Objects.requireNonNull(function, "function");
        if (size <= 0) throw new IllegalArgumentException("Window size must be positive");
        coefficients = new double[size];
        double sum = 0;
        for (int n = 0; n < size; n++) {
            coefficients[n] = function.coefficient(n, size);
            sum += coefficients[n];
        }
        coherentGain = sum / size;
    }

    /**
     * Multiplies caller-owned real/imaginary arrays in place, without allocation.
     *
     * @param real      non-null length-size real samples
     * @param imaginary non-null separate length-size imaginary samples
     * @throws IllegalArgumentException for wrong lengths or aliased arrays
     */
    public void apply(double[] real, double[] imaginary) {
        Objects.requireNonNull(real, "real");
        Objects.requireNonNull(imaginary, "imaginary");
        if (real.length != coefficients.length || imaginary.length != coefficients.length || real == imaginary) {
            throw new IllegalArgumentException("Window requires separate matching arrays");
        }
        for (int n = 0; n < coefficients.length; n++) {
            real[n] *= coefficients[n];
            imaginary[n] *= coefficients[n];
        }
    }

    /**
     * Reads a precomputed coefficient without copying or allocating.
     *
     * @param index sample index in [0,size)
     * @return window coefficient
     */
    public double coefficient(int index) {
        return coefficients[Objects.checkIndex(index, coefficients.length)];
    }

    /**
     * Returns the coefficient mean for caller-selected amplitude normalization.
     * A symmetric two-point Hann has zero gain; do not divide by it.
     *
     * @return mean coefficient, not a power/PSD calibration
     */
    public double coherentGain() {
        return coherentGain;
    }

    /**
     * Returns the fixed coefficient count.
     *
     * @return sample count
     */
    public int size() {
        return coefficients.length;
    }

    /**
     * Returns the window convention.
     *
     * @return immutable window kind
     */
    public WindowFunction function() {
        return function;
    }
}
