package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;
import eu.dariolucia.jsatdecode.dsp.fft.ExponentialAverager;
import eu.dariolucia.jsatdecode.dsp.fft.Spectrum;
import eu.dariolucia.jsatdecode.dsp.fft.Window;
import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained Phase 3 numerical oracle, signal, boundary and plan-sharing tests. */
public final class SpectrumTest {
    private static int checks;
    private SpectrumTest() {}

    /** Executes numerical acceptance checks independently of -ea.
     * @param args unused
     * @throws Exception if concurrent plan reuse fails
     */
    public static void main(String[] args) throws Exception {
        dftOracle();
        knownSignals();
        largeRoundTrips();
        windows();
        windowLeakage();
        conversions();
        shiftsAndAxes();
        reduction();
        averaging();
        validation();
        sharedPlan();
        System.out.println("Phase 3: " + checks + " checks passed");
    }

    private static void dftOracle() {
        Random random = new Random(0x5a7dec0deL);
        for (int size = 1; size <= 64; size *= 2) {
            ComplexFft fft = new ComplexFft(size);
            check(fft.size() == size);
            for (int trial = 0; trial < 6; trial++) {
                double[] real = randomVector(random, size);
                double[] imaginary = randomVector(random, size);
                double[] originalR = real.clone(), originalI = imaginary.clone();
                double[][] expected = directDft(real, imaginary, false);
                fft.forward(real, imaginary);
                nearArrays(real, expected[0], 2e-11);
                nearArrays(imaginary, expected[1], 2e-11);

                // An independent inverse oracle verifies sign and 1/N, not just round trips.
                double[][] inverse = directDft(originalR, originalI, true);
                double[] inverseR = originalR.clone(), inverseI = originalI.clone();
                fft.inverse(inverseR, inverseI);
                nearArrays(inverseR, inverse[0], 2e-11);
                nearArrays(inverseI, inverse[1], 2e-11);
                fft.inverse(real, imaginary);
                nearArrays(real, originalR, 2e-12);
                nearArrays(imaginary, originalI, 2e-12);
            }
        }
    }

    private static double[][] directDft(double[] real, double[] imaginary, boolean inverse) {
        int size = real.length;
        double[][] result = new double[2][size];
        for (int bin = 0; bin < size; bin++) {
            for (int n = 0; n < size; n++) {
                double angle = (inverse ? 1 : -1) * 2 * StrictMath.PI * bin * n / size;
                double c = StrictMath.cos(angle), s = StrictMath.sin(angle);
                result[0][bin] += real[n] * c - imaginary[n] * s;
                result[1][bin] += real[n] * s + imaginary[n] * c;
            }
            if (inverse) {
                result[0][bin] /= size;
                result[1][bin] /= size;
            }
        }
        return result;
    }

    private static void knownSignals() {
        // Impulse and complex DC establish the unnormalized forward convention.
        int size = 64;
        ComplexFft fft = new ComplexFft(size);
        double[] real = new double[size], imaginary = new double[size];
        real[0] = 1;
        fft.forward(real, imaginary);
        for (int bin = 0; bin < size; bin++) {
            near(real[bin], 1, 1e-14);
            near(imaginary[bin], 0, 1e-14);
        }
        Arrays.fill(real, 2);
        Arrays.fill(imaginary, -3);
        fft.forward(real, imaginary);
        near(real[0], 128, 1e-12);
        near(imaginary[0], -192, 1e-12);
        for (int bin = 1; bin < size; bin++) near(Math.hypot(real[bin], imaginary[bin]), 0, 1e-12);
        // Positive, negative and Nyquist tones must land on the correct shifted Hz axis.
        for (int bin : new int[]{0, 1, 7, 31, 32, 33, 63}) {
            tone(real, imaginary, bin, 1);
            fft.forward(real, imaginary);
            for (int k = 0; k < size; k++) {
                near(real[k], k == bin ? size : 0, 1e-10);
                near(imaginary[k], 0, 1e-10);
            }
            double[] power = new double[size], shifted = new double[size];
            Spectrum.power(real, imaginary, power);
            Spectrum.fftShift(power, shifted);
            int peak = maximumIndex(shifted);
            check(peak == (bin + size / 2) % size);
            near(Spectrum.frequencyAtBin(peak, size, 64000, 0),
                    (bin < size / 2 ? bin : bin - size) * 1000, 1e-12);
        }
        // Superpose two different amplitudes and opposite frequency signs.
        tone(real, imaginary, 5, 1);
        for (int n = 0; n < size; n++) {
            real[n] += 0.5 * Math.cos(-2 * Math.PI * 9 * n / size);
            imaginary[n] += 0.5 * Math.sin(-2 * Math.PI * 9 * n / size);
        }
        fft.forward(real, imaginary);
        for (int bin = 0; bin < size; bin++) {
            near(real[bin], bin == 5 ? 64 : bin == 55 ? 32 : 0, 1e-10);
            near(imaginary[bin], 0, 1e-10);
        }
    }

    private static void largeRoundTrips() {
        Random random = new Random(123456);
        for (int size : new int[]{512, 1024, 2048, 4096, 8192, 16384}) {
            ComplexFft fft = new ComplexFft(size);
            double[] real = randomVector(random, size), imaginary = randomVector(random, size);
            double[] originalR = real.clone(), originalI = imaginary.clone();
            double inputEnergy = energy(real, imaginary);
            fft.forward(real, imaginary);
            near(energy(real, imaginary) / size, inputEnergy, 2e-12);
            fft.inverse(real, imaginary);
            nearArrays(real, originalR, 2e-12);
            nearArrays(imaginary, originalI, 2e-12);
        }
    }

    private static void windows() {
        double[][] expected = {
            {1, 1, 1, 1, 1}, {0, 0.5, 1, 0.5, 0},
            {0.08, 0.54, 1, 0.54, 0.08}, {0, 0.34, 1, 0.34, 0}
        };
        for (WindowFunction function : WindowFunction.values()) {
            Window window = new Window(function, 5);
            double[] real = {2, 2, 2, 2, 2}, imaginary = {-3, -3, -3, -3, -3};
            double[] coefficients = expected[function.ordinal()];
            check(window.function() == function && window.size() == 5);
            window.apply(real, imaginary);
            double sum = 0;
            for (int n = 0; n < 5; n++) {
                near(window.coefficient(n), coefficients[n], 1e-14);
                near(real[n], 2 * coefficients[n], 1e-14);
                near(imaginary[n], -3 * coefficients[n], 1e-14);
                sum += coefficients[n];
            }
            near(window.coherentGain(), sum / 5, 1e-14);
            Window symmetric = new Window(function, 64);
            for (int n = 0; n < 64; n++) near(symmetric.coefficient(n), symmetric.coefficient(63 - n), 1e-14);
            near(new Window(function, 1).coefficient(0), 1, 0);
        }
        check(new Window(16).function() == WindowFunction.HANN);
        near(new Window(2).coherentGain(), 0, 0);
    }

    private static void windowLeakage() {
        int size = 256;
        double[] rectangularR = new double[size], rectangularI = new double[size];
        tone(rectangularR, rectangularI, 17.35, 1);
        double[] hannR = rectangularR.clone(), hannI = rectangularI.clone();
        new Window(size).apply(hannR, hannI);
        ComplexFft fft = new ComplexFft(size);
        fft.forward(rectangularR, rectangularI);
        fft.forward(hannR, hannI);
        double rectangularFar = 0, hannFar = 0;
        for (int k = 0; k < size; k++) {
            if (k < 14 || k > 21) {
                rectangularFar += rectangularR[k] * rectangularR[k] + rectangularI[k] * rectangularI[k];
                hannFar += hannR[k] * hannR[k] + hannI[k] * hannI[k];
            }
        }
        check(hannFar / energy(hannR, hannI) < rectangularFar / energy(rectangularR, rectangularI) * 0.01);
        // Coherent gain compensates a bin-centered tone's peak, not arbitrary PSD.
        tone(hannR, hannI, 17, 1);
        Window window = new Window(size);
        window.apply(hannR, hannI);
        fft.forward(hannR, hannI);
        near(Math.hypot(hannR[17], hannI[17]) / (size * window.coherentGain()), 1, 1e-12);
    }

    private static void conversions() {
        double[] real = {3, 0, 1, 1e-300}, imaginary = {4, 0, 0, 0};
        double[] magnitude = new double[4], power = new double[4];
        Spectrum.magnitude(real, imaginary, magnitude);
        Spectrum.power(real, imaginary, power);
        nearArrays(magnitude, new double[]{5, 0, 1, 1e-300}, 1e-14);
        nearArrays(power, new double[]{25, 0, 1, 0}, 1e-14);
        double[] magnitudeDb = new double[4], powerDb = new double[4];
        Spectrum.magnitudeToDb(magnitude, magnitudeDb, -160);
        Spectrum.powerToDb(power, powerDb, -160);
        nearArrays(magnitudeDb, powerDb, 1e-13);
        near(powerDb[0], 10 * Math.log10(25), 1e-14);
        near(powerDb[1], -160, 0);
        near(powerDb[2], 0, 0);
        Spectrum.powerToDb(new double[]{Double.MIN_VALUE}, powerDb = new double[1], -4000);
        check(Double.isFinite(powerDb[0]) && powerDb[0] < -3000);
        Spectrum.magnitude(new double[]{Double.MAX_VALUE / 2}, new double[]{Double.MAX_VALUE / 2},
                magnitude = new double[1]);
        check(Double.isFinite(magnitude[0]));
        Spectrum.magnitude(new double[]{Double.NaN}, new double[]{Double.POSITIVE_INFINITY}, magnitude);
        check(Double.isNaN(magnitude[0]));
        double[] aliasR = {3, 4}, aliasI = {4, 3};
        Spectrum.magnitude(aliasR, aliasI, aliasR);
        nearArrays(aliasR, new double[]{5, 5}, 0);
        Spectrum.power(new double[]{3}, aliasI = new double[]{4}, aliasI);
        near(aliasI[0], 25, 0);
        double[] special = {Double.NaN, Double.POSITIVE_INFINITY, -0.0};
        Spectrum.powerToDb(special, special, -100);
        check(Double.isNaN(special[0]) && special[1] == Double.POSITIVE_INFINITY && special[2] == -100);
        Spectrum.magnitudeToDb(new double[0], new double[0], -160);
    }

    private static void shiftsAndAxes() {
        for (int size = 0; size <= 9; size++) {
            double[] values = new double[size], output = new double[size];
            for (int n = 0; n < size; n++) values[n] = n;
            Spectrum.fftShift(values, output);
            double[] inPlace = values.clone();
            Spectrum.fftShift(inPlace, inPlace);
            for (int n = 0; n < size; n++) {
                near(output[n], (n + (size + 1) / 2) % size, 0);
                near(inPlace[n], output[n], 0);
                near(values[n], n, 0);
            }
        }
        for (int n = 0; n < 5; n++) near(Spectrum.frequencyAtBin(n, 5, 5, 100), 98 + n, 0);
        near(Spectrum.frequencyAtBin(0, 1, 48000, 100), 100, 0);
    }

    private static void reduction() {
        double[] source = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9}, output = new double[3];
        Spectrum.reduceMaximum(source, output);
        nearArrays(output, new double[]{2, 5, 9}, 0);
        source = new double[4096];
        Arrays.fill(source, -100);
        source[2047] = 7;
        Spectrum.reduceMaximum(source, output = new double[1000]);
        near(Arrays.stream(output).max().orElseThrow(), 7, 0);
        check(Arrays.stream(output).filter(value -> value == 7).count() == 1);
        source = new double[]{Double.NaN, -4, Double.NaN, Double.NaN, Double.NEGATIVE_INFINITY, 6};
        Spectrum.reduceMaximum(source, output = new double[3]);
        check(output[0] == -4 && Double.isNaN(output[1]) && output[2] == 6);
        Spectrum.reduceMaximum(new double[]{-8, -2}, output = new double[1]);
        near(output[0], -2, 0);
        Spectrum.reduceMaximum(output, output);
        near(output[0], -2, 0);
        Spectrum.reduceMaximum(new double[0], new double[0]);
    }

    private static void averaging() {
        ExponentialAverager average = new ExponentialAverager(3, 0.25);
        double[] output = new double[3], input = {10, -10, Double.NaN};
        check(average.size() == 3 && average.alpha() == 0.25);
        average.copyTo(output);
        check(Arrays.stream(output).allMatch(Double::isNaN));
        average.update(input, output);
        check(output[0] == 10 && output[1] == -10 && Double.isNaN(output[2]));
        input[0] = 999;
        average.update(new double[]{14, -6, 4}, output);
        nearArrays(output, new double[]{11, -9, 4}, 0);
        average.update(new double[]{Double.NaN, -1, 8}, output);
        nearArrays(output, new double[]{11, -7, 5}, 0);
        output[0] = 999;
        average.copyTo(output);
        near(output[0], 11, 0);
        average.reset();
        average.update(new double[]{2, 3, 4}, input);
        nearArrays(input, new double[]{2, 3, 4}, 0);
        ExponentialAverager bypass = new ExponentialAverager(1, 1);
        double[] alias = {3};
        bypass.update(alias, alias);
        alias[0] = 7;
        bypass.update(alias, alias);
        near(alias[0], 7, 0);
        average.update(new double[]{-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE}, output);
        check(Double.isFinite(output[0]));
    }

    private static void validation() {
        // Invalid array parameters must fail before mutating buffers or average history.
        for (int size : new int[]{0, -1, 3, 6, Integer.MIN_VALUE}) {
            expect(IllegalArgumentException.class, () -> new ComplexFft(size));
        }
        ComplexFft fft = new ComplexFft(4);
        double[] preserved = {1, 2, 3, 4};
        expect(NullPointerException.class, () -> fft.forward(null, new double[4]));
        expect(IllegalArgumentException.class, () -> fft.forward(preserved, new double[2]));
        expect(IllegalArgumentException.class, () -> fft.inverse(preserved, preserved));
        nearArrays(preserved, new double[]{1, 2, 3, 4}, 0);
        expect(IllegalArgumentException.class, () -> new Window(0));
        expect(NullPointerException.class, () -> new Window(null, 4));
        Window window = new Window(4);
        expect(NullPointerException.class, () -> window.apply(null, new double[4]));
        expect(IllegalArgumentException.class, () -> window.apply(preserved, preserved));
        expect(IllegalArgumentException.class, () -> window.apply(preserved, new double[2]));
        expect(IndexOutOfBoundsException.class, () -> window.coefficient(-1));
        expect(IndexOutOfBoundsException.class, () -> window.coefficient(4));
        expect(IllegalArgumentException.class, () -> Spectrum.magnitude(new double[1], new double[2], new double[1]));
        expect(NullPointerException.class, () -> Spectrum.power(null, new double[1], new double[1]));
        double[] invalidPower = {1, -1};
        expect(IllegalArgumentException.class, () -> Spectrum.powerToDb(invalidPower, invalidPower, -160));
        nearArrays(invalidPower, new double[]{1, -1}, 0);
        expect(IllegalArgumentException.class, () -> Spectrum.magnitudeToDb(new double[]{-1}, new double[1], -100));
        expect(IllegalArgumentException.class, () -> Spectrum.powerToDb(new double[1], new double[1], Double.NaN));
        expect(IllegalArgumentException.class, () -> Spectrum.fftShift(new double[2], new double[3]));
        expect(IllegalArgumentException.class, () -> Spectrum.reduceMaximum(new double[2], new double[3]));
        expect(IllegalArgumentException.class, () -> Spectrum.reduceMaximum(new double[2], new double[0]));
        expect(NullPointerException.class, () -> Spectrum.reduceMaximum(null, new double[0]));
        expect(IndexOutOfBoundsException.class, () -> Spectrum.frequencyAtBin(4, 4, 1, 0));
        expect(IllegalArgumentException.class, () -> Spectrum.frequencyAtBin(0, 0, 1, 0));
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> Spectrum.frequencyAtBin(0, 4, rate, 0));
        }
        expect(IllegalArgumentException.class, () -> Spectrum.frequencyAtBin(0, 4, 1, Double.NaN));
        expect(IllegalArgumentException.class, () -> new ExponentialAverager(0, 0.5));
        for (double alpha : new double[]{0, -1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> new ExponentialAverager(1, alpha));
        }
        // Rejected averaging input must leave previously accumulated history intact.
        ExponentialAverager average = new ExponentialAverager(2, 0.5);
        average.update(new double[]{2, 4}, new double[2]);
        expect(IllegalArgumentException.class, () -> average.update(new double[]{9, Double.POSITIVE_INFINITY}, new double[2]));
        expect(IllegalArgumentException.class, () -> average.update(new double[2], new double[1]));
        expect(NullPointerException.class, () -> average.copyTo(null));
        double[] output = new double[2];
        average.copyTo(output);
        nearArrays(output, new double[]{2, 4}, 0);
    }

    private static void sharedPlan() throws Exception {
        ComplexFft fft = new ComplexFft(1024);
        Window window = new Window(1024);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[3];
        for (int index = 0; index < threads.length; index++) {
            final int bin = index * 17 + 1;
            threads[index] = new Thread(() -> {
                try {
                    double[] real = new double[1024], imaginary = new double[1024];
                    for (int run = 0; run < 30; run++) {
                        tone(real, imaginary, bin, 1);
                        window.apply(real, imaginary);
                        fft.forward(real, imaginary);
                        double amplitude = Math.hypot(real[bin], imaginary[bin]);
                        if (Math.abs(amplitude - 1024 * window.coherentGain()) > 1e-9) {
                            throw new AssertionError("Shared plan state changed");
                        }
                    }
                } catch (Throwable e) { failure.compareAndSet(null, e); }
            }, "fft-plan-reader-" + index);
            threads[index].setDaemon(true);
            threads[index].start();
        }
        for (Thread thread : threads) {
            thread.join(5000);
            check(!thread.isAlive());
        }
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static void tone(double[] real, double[] imaginary, double bin, double amplitude) {
        for (int n = 0; n < real.length; n++) {
            double angle = 2 * Math.PI * bin * n / real.length;
            real[n] = amplitude * Math.cos(angle);
            imaginary[n] = amplitude * Math.sin(angle);
        }
    }
    private static double[] randomVector(Random random, int size) {
        double[] values = new double[size];
        for (int n = 0; n < size; n++) values[n] = random.nextDouble() * 2 - 1;
        return values;
    }
    private static double energy(double[] real, double[] imaginary) {
        double sum = 0;
        for (int n = 0; n < real.length; n++) sum += real[n] * real[n] + imaginary[n] * imaginary[n];
        return sum;
    }
    private static int maximumIndex(double[] values) {
        int best = 0;
        for (int n = 1; n < values.length; n++) if (values[n] > values[best]) best = n;
        return best;
    }
    private static void nearArrays(double[] actual, double[] expected, double tolerance) {
        check(actual.length == expected.length);
        for (int n = 0; n < actual.length; n++) near(actual[n], expected[n], tolerance);
    }
    private static void near(double actual, double expected, double tolerance) {
        checks++;
        if (!Double.isFinite(actual) || Math.abs(actual - expected) > tolerance * Math.max(1, Math.abs(expected))) {
            throw new AssertionError("Expected " + expected + ", got " + actual + ", tolerance " + tolerance);
        }
    }
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("Phase 3 check " + checks + " failed");
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw new AssertionError("Expected " + type + ", got " + failure, failure);
        }
        throw new AssertionError("Expected " + type);
    }
}
