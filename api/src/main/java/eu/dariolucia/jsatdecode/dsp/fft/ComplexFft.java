package eu.dariolucia.jsatdecode.dsp.fft;

import java.util.Objects;

/**
 * Immutable radix-2 Cooley-Tukey FFT plan with precomputed bit reversal and
 * twiddles. Construct once per size and reuse; there is no global plan cache.
 * Transforms modify caller-owned, separate real/imaginary arrays in place and
 * allocate nothing. A plan is safe to share when calls use disjoint buffers.
 * Runtime is O(N log N), plan storage O(N). No UI, worker or observer is created.
 */
public final class ComplexFft {
    private final int size;
    private final int[] reversed;
    private final double[] cosine;
    private final double[] sine;

    /**
     * Precomputes a fixed-size plan during setup.
     *
     * @param size positive power of two; one is supported
     * @throws IllegalArgumentException if size is not a positive power of two
     */
    public ComplexFft(int size) {
        if (size <= 0 || (size & (size - 1)) != 0) {
            throw new IllegalArgumentException("FFT size must be a positive power of two");
        }
        this.size = size;
        reversed = new int[size];
        int levels = Integer.numberOfTrailingZeros(size);
        for (int n = 0; n < size; n++) reversed[n] = Integer.reverse(n) >>> (32 - levels);
        cosine = new double[size / 2];
        sine = new double[size / 2];
        for (int n = 0; n < cosine.length; n++) {
            double angle = -2 * Math.PI * n / size;
            cosine[n] = Math.cos(angle);
            sine[n] = Math.sin(angle);
        }
    }

    /**
     * Returns the fixed transform size.
     *
     * @return complex sample count
     */
    public int size() {
        return size;
    }

    /**
     * Computes X[k] = sum(x[n] exp(-j 2 pi k n / N)), without normalization.
     *
     * @param real      non-null length-N in-phase input, replaced by real bins
     * @param imaginary non-null distinct length-N quadrature input, replaced by imaginary bins
     * @throws IllegalArgumentException for wrong lengths or aliased arrays
     */
    public void forward(double[] real, double[] imaginary) {
        transform(real, imaginary, false);
    }

    /**
     * Computes the positive-exponent inverse, normalized by 1/N.
     *
     * @param real      non-null length-N real bins, replaced by in-phase samples
     * @param imaginary non-null distinct length-N imaginary bins, replaced by quadrature samples
     * @throws IllegalArgumentException for wrong lengths or aliased arrays
     */
    public void inverse(double[] real, double[] imaginary) {
        transform(real, imaginary, true);
    }

    private void transform(double[] real, double[] imaginary, boolean inverse) {
        Objects.requireNonNull(real, "real");
        Objects.requireNonNull(imaginary, "imaginary");
        if (real.length != size || imaginary.length != size || real == imaginary) {
            throw new IllegalArgumentException("FFT requires separate length-N arrays");
        }
        // Permute once, then perform iterative butterflies using the cached twiddles.
        for (int n = 0; n < size; n++) {
            int other = reversed[n];
            if (other > n) {
                double value = real[n];
                real[n] = real[other];
                real[other] = value;
                value = imaginary[n];
                imaginary[n] = imaginary[other];
                imaginary[other] = value;
            }
        }
        for (int half = 1; half < size; half <<= 1) {
            int width = half << 1;
            int stride = size / width;
            for (int start = 0; start < size; start += width) {
                for (int offset = 0; offset < half; offset++) {
                    int even = start + offset;
                    int odd = even + half;
                    int twiddle = offset * stride;
                    double c = cosine[twiddle];
                    double s = inverse ? -sine[twiddle] : sine[twiddle];
                    double r = c * real[odd] - s * imaginary[odd];
                    double i = s * real[odd] + c * imaginary[odd];
                    real[odd] = real[even] - r;
                    imaginary[odd] = imaginary[even] - i;
                    real[even] += r;
                    imaginary[even] += i;
                }
            }
        }
        if (inverse) {
            for (int n = 0; n < size; n++) {
                real[n] /= size;
                imaginary[n] /= size;
            }
        }
    }
}
