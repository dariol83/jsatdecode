package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;
import eu.dariolucia.jsatdecode.dsp.fft.ExponentialAverager;
import eu.dariolucia.jsatdecode.dsp.fft.Spectrum;
import eu.dariolucia.jsatdecode.dsp.fft.Window;
import java.util.Locale;

/** Fixed-workload FFT/analysis smoke benchmark; timings are diagnostic, never assertions. */
public final class SpectrumBenchmark {
    private static volatile double consumed;
    private SpectrumBenchmark() {}

    /** Measures reusable plans and buffers after warmup.
     * @param args unused
     */
    public static void main(String[] args) {
        for (int size : new int[]{512, 4096, 16384}) {
            for (int warmup = 0; warmup < 3; warmup++) run(size, false);
            run(size, true);
        }
        System.out.println("Spectrum benchmark checksum: " + consumed);
    }

    private static void run(int size, boolean report) {
        // Allocate plans, coefficients, state and all scratch buffers outside timing.
        int iterations = 200;
        ComplexFft fft = new ComplexFft(size);
        Window window = new Window(size);
        ExponentialAverager average = new ExponentialAverager(size, 0.2);
        double[] originalR = new double[size], originalI = new double[size];
        double[] real = new double[size], imaginary = new double[size];
        double[] power = new double[size], shifted = new double[size];
        double[] averaged = new double[size], db = new double[size];
        double[] pixels = new double[Math.min(1000, size)];
        for (int n = 0; n < size; n++) {
            originalR[n] = Math.cos(2 * Math.PI * 17 * n / size);
            originalI[n] = Math.sin(2 * Math.PI * 17 * n / size);
        }
        System.arraycopy(originalR, 0, real, 0, size);
        System.arraycopy(originalI, 0, imaginary, 0, size);

        // Round trips avoid unbounded numerical growth while reusing in-place arrays.
        long start = System.nanoTime();
        for (int pass = 0; pass < iterations; pass++) {
            fft.forward(real, imaginary);
            fft.inverse(real, imaginary);
        }
        long fftNanos = System.nanoTime() - start;
        consumed = real[0] + imaginary[0];

        // Include analysis copies, Hann, power, shift, linear averaging, dB and reduction.
        start = System.nanoTime();
        for (int pass = 0; pass < iterations; pass++) {
            System.arraycopy(originalR, 0, real, 0, size);
            System.arraycopy(originalI, 0, imaginary, 0, size);
            window.apply(real, imaginary);
            fft.forward(real, imaginary);
            Spectrum.power(real, imaginary, power);
            Spectrum.fftShift(power, shifted);
            average.update(shifted, averaged);
            Spectrum.powerToDb(averaged, db, -160);
            Spectrum.reduceMaximum(db, pixels);
        }
        long analysisNanos = System.nanoTime() - start;
        consumed += pixels[pixels.length / 2];
        if (report) {
            System.out.printf(Locale.ROOT,
                    "Spectrum N=%d (3 warmups, %d passes): FFT+inverse %.2f us; analysis %.2f us%n",
                    size, iterations, fftNanos / (iterations * 1000.0), analysisNanos / (iterations * 1000.0));
        }
    }
}