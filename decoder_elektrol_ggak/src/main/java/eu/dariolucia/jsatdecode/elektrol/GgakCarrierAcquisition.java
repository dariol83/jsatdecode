package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.dsp.fft.ComplexFft;

import java.util.Arrays;

/** Bounded streaming port of the v1.5 GRC squared-signal carrier acquisition block. */
final class GgakCarrierAcquisition {
    static final int FFT_SIZE = 65536;
    static final int DELAY = 2 * FFT_SIZE;
    private static final int AVERAGES = 8;
    private static final double RATE = ElektroLGgakSettings.CHANNEL_RATE;
    private static final int PARTNER = (int) Math.round(2 * 5000 * FFT_SIZE / RATE);
    private final ComplexFft fft = new ComplexFft(FFT_SIZE);
    private final double[] window = new double[FFT_SIZE];
    private final double[] real = new double[FFT_SIZE], imaginary = new double[FFT_SIZE];
    private final double[][] history = new double[AVERAGES][FFT_SIZE];
    private final double[] mean = new double[FFT_SIZE], sorted = new double[FFT_SIZE];
    private final float[] delayI = new float[DELAY], delayQ = new float[DELAY];
    private int pending, delayCursor, historyCursor, historySize;
    private double frequency = Double.NaN, phase;
    float i, q;

    GgakCarrierAcquisition() {
        for (int n = 0; n < FFT_SIZE; n++)
            window[n] = .5 - .5 * Math.cos(2 * Math.PI * n / (FFT_SIZE - 1));
    }

    void sample(float inI, float inQ) {
        // Square in double precision, as blk.astype(complex128)**2 in the reference.
        double r = inI, j = inQ;
        real[pending] = (r * r - j * j) * window[pending];
        imaginary[pending] = (2 * r * j) * window[pending];
        if (++pending == FFT_SIZE) {
            analyse();
            pending = 0;
        }
        float delayedI = delayI[delayCursor], delayedQ = delayQ[delayCursor];
        delayI[delayCursor] = inI;
        delayQ[delayCursor] = inQ;
        delayCursor = (delayCursor + 1) & (DELAY - 1);
        if (Double.isNaN(frequency)) {
            i = q = 0;
            return;
        }
        double c = Math.cos(phase), s = Math.sin(phase);
        i = (float) (delayedI * c - delayedQ * s);
        q = (float) (delayedI * s + delayedQ * c);
        phase -= 2 * Math.PI * frequency / RATE;
        phase %= 2 * Math.PI;
        if (phase < 0) phase += 2 * Math.PI;
    }

    double frequencyHz() { return frequency; }

    private void analyse() {
        fft.forward(real, imaginary);
        double[] power = history[historyCursor];
        for (int k = 0; k < FFT_SIZE; k++)
            power[k] = real[k] * real[k] + imaginary[k] * imaginary[k];
        historyCursor = (historyCursor + 1) % AVERAGES;
        historySize = Math.min(historySize + 1, AVERAGES);
        if (historySize < 2) return;

        Arrays.fill(mean, 0);
        for (int n = 0; n < historySize; n++) {
            // Accumulate chronologically, also after the rolling history wraps.
            double[] p = history[(historyCursor - historySize + n + AVERAGES) % AVERAGES];
            for (int k = 0; k < FFT_SIZE; k++) mean[k] += p[k];
        }
        int best = 0;
        double maximum = -1;
        for (int k = 0; k < FFT_SIZE; k++) mean[k] /= historySize;
        for (int k = 0; k < FFT_SIZE; k++) {
            int bin = k < FFT_SIZE / 2 ? k : k - FFT_SIZE;
            if (Math.abs(bin * RATE / FFT_SIZE) > 60000) continue;
            double score = score(k);
            if (score > maximum) {
                maximum = score;
                best = k;
            }
        }
        double a = Math.log(score((best - 1) & (FFT_SIZE - 1)) + 1e-300);
        double b = Math.log(score(best) + 1e-300);
        double c = Math.log(score((best + 1) & (FFT_SIZE - 1)) + 1e-300);
        double denominator = a - 2 * b + c;
        double fraction = denominator == 0 ? 0 : .5 * (a - c) / denominator;
        int bin = best < FFT_SIZE / 2 ? best : best - FFT_SIZE;
        double estimate = .5 * (bin + fraction) * RATE / FFT_SIZE;

        System.arraycopy(mean, 0, sorted, 0, FFT_SIZE);
        Arrays.sort(sorted);
        double median = .5 * (sorted[FFT_SIZE / 2 - 1] + sorted[FFT_SIZE / 2]);
        double db = 10 * Math.log10((mean[best] + 1e-300) / (median + 1e-300));
        if (db >= 12 && (Double.isNaN(frequency) || Math.abs(estimate - frequency) > 40))
            frequency = estimate;
    }

    private double score(int k) {
        return mean[k] + .5 * (mean[(k - PARTNER) & (FFT_SIZE - 1)]
                + mean[(k + PARTNER) & (FFT_SIZE - 1)]);
    }
}
