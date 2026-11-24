package eu.dariolucia.jsatdecode.dsp.fft;

import java.util.Objects;

/**
 * Allocation-free spectrum conversions on caller-owned arrays. Stateless and
 * thread-safe on disjoint buffers. Magnitude and power are unnormalized FFT-bin
 * values: no N, window, bandwidth or hardware calibration is implied. Display
 * decibels are relative dB referenced to one, never dBm or calibrated PSD.
 * NaN denotes unavailable data and is preserved unless a reduction has other
 * valid bins. Methods create no workers and do no UI or transport work.
 */
public final class Spectrum {
    private Spectrum() {
    }

    /**
     * Writes hypot(real, imaginary) per bin, avoiding intermediate square overflow.
     *
     * @param real        non-null real bins
     * @param imaginary   non-null equally sized imaginary bins
     * @param destination non-null equally sized output, may alias either input
     * @throws IllegalArgumentException for mismatched lengths
     */
    public static void magnitude(double[] real, double[] imaginary, double[] destination) {
        sameLength(real, imaginary);
        sameLength(real, destination);
        for (int n = 0; n < real.length; n++) {
            double r = real[n], i = imaginary[n];
            destination[n] = Double.isNaN(r) || Double.isNaN(i) ? Double.NaN : Math.hypot(r, i);
        }
    }

    /**
     * Writes real squared plus imaginary squared per bin; IEEE overflow becomes infinity.
     *
     * @param real        non-null real bins
     * @param imaginary   non-null equally sized imaginary bins
     * @param destination non-null equally sized output, may alias either input
     * @throws IllegalArgumentException for mismatched lengths
     */
    public static void power(double[] real, double[] imaginary, double[] destination) {
        sameLength(real, imaginary);
        sameLength(real, destination);
        for (int n = 0; n < real.length; n++) {
            double r = real[n], i = imaginary[n];
            destination[n] = r * r + i * i;
        }
    }

    /**
     * Writes max(floorDb, 10 log10(power)); zero maps to the floor and NaN stays NaN.
     *
     * @param power       non-null nonnegative powers, NaN and positive infinity allowed
     * @param destination non-null matching output, may alias input
     * @param floorDb     finite relative-dB floor, for example -160
     * @throws IllegalArgumentException for mismatched lengths, negative power or nonfinite floor
     */
    public static void powerToDb(double[] power, double[] destination, double floorDb) {
        toDb(power, destination, floorDb, 10);
    }

    /**
     * Writes max(floorDb, 20 log10(magnitude)); zero maps to the floor and NaN stays NaN.
     *
     * @param magnitude   non-null nonnegative amplitudes, NaN and positive infinity allowed
     * @param destination non-null matching output, may alias input
     * @param floorDb     finite relative-dB floor
     * @throws IllegalArgumentException for mismatched lengths, negative magnitude or nonfinite floor
     */
    public static void magnitudeToDb(double[] magnitude, double[] destination, double floorDb) {
        toDb(magnitude, destination, floorDb, 20);
    }

    /**
     * Moves zero frequency to the center by rotating left by ceil(N/2). Even
     * transforms produce bins [-Fs/2,Fs/2); odd arrays follow the same fftshift
     * ordering with DC at floor(N/2). Empty and singleton arrays are supported.
     *
     * @param source      non-null bins
     * @param destination non-null equally sized output; in-place operation is supported
     * @throws IllegalArgumentException for mismatched lengths
     */
    public static void fftShift(double[] source, double[] destination) {
        sameLength(source, destination);
        int middle = source.length / 2 + source.length % 2;
        if (source == destination) {
            // Three reversals rotate arbitrary odd/even lengths without scratch arrays.
            reverse(destination, 0, middle);
            reverse(destination, middle, destination.length);
            reverse(destination, 0, destination.length);
        } else {
            System.arraycopy(source, middle, destination, 0, source.length - middle);
            System.arraycopy(source, 0, destination, source.length - middle, middle);
        }
    }

    /**
     * Returns a shifted bin's center frequency, with no axis-array allocation.
     *
     * @param index           shifted index in [0,size)
     * @param size            positive bin count
     * @param sampleRate      positive finite complex samples per second
     * @param centerFrequency finite RF center in Hz, or zero for relative baseband
     * @return bin center in Hz; extreme finite inputs may overflow the sum
     * @throws IllegalArgumentException  for invalid size, rate or center frequency
     * @throws IndexOutOfBoundsException for an invalid index
     */
    public static double frequencyAtBin(int index, int size, double sampleRate, double centerFrequency) {
        if (size <= 0 || !Double.isFinite(sampleRate) || sampleRate <= 0 || !Double.isFinite(centerFrequency)) {
            throw new IllegalArgumentException("Invalid frequency axis parameters");
        }
        Objects.checkIndex(index, size);
        return centerFrequency + (index - size / 2) * (sampleRate / size);
    }

    /**
     * Reduces contiguous frequency bins to pixel-column maxima. Column c covers
     * [floor(c*N/W),floor((c+1)*N/W)), so every input bin belongs to exactly one
     * nonempty column and narrow peaks survive. NaNs are ignored unless a column
     * has no available values, in which case its output is NaN.
     *
     * @param source      non-null bins in display order
     * @param destination non-null output of length W in [1,N], or both arrays empty
     * @throws IllegalArgumentException for an empty width with nonempty input or upsampling
     */
    public static void reduceMaximum(double[] source, double[] destination) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        int width = destination.length;
        if (width > source.length || (width == 0 && source.length != 0)) {
            throw new IllegalArgumentException("Reduction width must be between one and input length");
        }
        for (int column = 0; column < width; column++) {
            int start = (int) ((long) column * source.length / width);
            int end = (int) ((long) (column + 1) * source.length / width);
            double maximum = Double.NaN;
            for (int n = start; n < end; n++) {
                if (!Double.isNaN(source[n])) {
                    maximum = Double.isNaN(maximum) ? source[n] : Math.max(maximum, source[n]);
                }
            }
            destination[column] = maximum;
        }
    }

    private static void toDb(double[] source, double[] destination, double floorDb, double multiplier) {
        sameLength(source, destination);
        if (!Double.isFinite(floorDb)) throw new IllegalArgumentException("dB floor must be finite");
        // Validate the whole input before changing an aliased or separate output.
        for (double value : source) {
            if (value < 0) throw new IllegalArgumentException("Magnitude/power must be nonnegative");
        }
        for (int n = 0; n < source.length; n++) {
            destination[n] = source[n] == 0 ? floorDb : Math.max(floorDb, multiplier * Math.log10(source[n]));
        }
    }

    private static void sameLength(double[] first, double[] second) {
        Objects.requireNonNull(first, "input");
        Objects.requireNonNull(second, "output");
        if (first.length != second.length) throw new IllegalArgumentException("Array lengths must match");
    }

    private static void reverse(double[] values, int start, int end) {
        for (int left = start, right = end - 1; left < right; left++, right--) {
            double value = values[left];
            values[left] = values[right];
            values[right] = value;
        }
    }
}
