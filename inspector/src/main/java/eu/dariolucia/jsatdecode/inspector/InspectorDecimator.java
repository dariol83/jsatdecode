package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

/* Stateful anti-aliasing power-of-two decimation, owned only by the acquisition
   worker. Cascaded 63-tap Blackman half-band filters retain delay and sample
   phase across arbitrary source blocks. Causal startup is zero padded; timestamps
   describe the output sample clock (the signal has the FIR group delay). */
final class InspectorDecimator {
    private final Stage[] stages;
    private final int factor;
    private long outputSamples;
    private long anchor;
    private boolean anchored;
    private float[] first = new float[0], second = new float[0];

    InspectorDecimator(int factor) {
        if (factor < 1 || factor > 64 || Integer.bitCount(factor) != 1)
            throw new IllegalArgumentException("Decimation must be a power of two in [1,64]");
        this.factor = factor;
        stages = new Stage[Integer.numberOfTrailingZeros(factor)];
        java.util.Arrays.setAll(stages, ignored -> new Stage());
    }

    ComplexSampleBlock process(ComplexSampleBlock input) {
        if (factor == 1) return input;
        if (!anchored) {
            anchor = input.timestampNanos();
            anchored = true;
        }
        int count = input.complexSamples();
        if (first.length < count * 2) {
            first = new float[count * 2];
            second = new float[count * 2];
        }
        for (int n = 0; n < count; n++) {
            first[n * 2] = input.i(n);
            first[n * 2 + 1] = input.q(n);
        }
        float[] source = first, target = second;
        for (Stage stage : stages) {
            count = stage.process(source, count, target);
            float[] swap = source;
            source = target;
            target = swap;
        }
        double rate = input.sampleRate() / factor;
        long timestamp = Math.addExact(anchor, (long) (outputSamples / rate * 1e9));
        ComplexSampleBlock output = new ComplexSampleBlock(source, count, outputSamples, timestamp, rate);
        outputSamples += count;
        return output;
    }

    private static final class Stage {
        private static final int LENGTH = 63, MIDDLE = LENGTH / 2;
        private static final double[] TAPS = taps();
        private final float[] historyI = new float[LENGTH], historyQ = new float[LENGTH];
        private int position;
        private boolean emit = true;

        int process(float[] input, int count, float[] output) {
            int written = 0;
            for (int n = 0; n < count; n++) {
                historyI[position] = input[n * 2];
                historyQ[position] = input[n * 2 + 1];
                if (emit) {
                    int center = position - MIDDLE;
                    if (center < 0) center += LENGTH;
                    double i = TAPS[MIDDLE] * historyI[center];
                    double q = TAPS[MIDDLE] * historyQ[center];
                    // Every other half-band coefficient is exactly zero. Pair
                    // equal symmetric coefficients for half as many products.
                    for (int tap = 0; tap < MIDDLE; tap += 2) {
                        int left = position - tap;
                        if (left < 0) left += LENGTH;
                        int right = position - (LENGTH - 1 - tap);
                        if (right < 0) right += LENGTH;
                        i += TAPS[tap] * (historyI[left] + historyI[right]);
                        q += TAPS[tap] * (historyQ[left] + historyQ[right]);
                    }
                    output[written * 2] = (float) i;
                    output[written * 2 + 1] = (float) q;
                    written++;
                }
                emit = !emit;
                if (++position == LENGTH) position = 0;
            }
            return written;
        }

        private static double[] taps() {
            double[] result = new double[LENGTH];
            double sum = 0;
            for (int n = 0; n < LENGTH; n++) {
                int distance = n - MIDDLE;
                double sinc = distance == 0 ? 0.5 : distance % 2 == 0 ? 0 : Math.sin(Math.PI * distance / 2) / (Math.PI * distance);
                double window = 0.42 - 0.5 * Math.cos(2 * Math.PI * n / (LENGTH - 1)) + 0.08 * Math.cos(4 * Math.PI * n / (LENGTH - 1));
                result[n] = sinc * window;
                sum += result[n];
            }
            for (int n = 0; n < LENGTH; n++) result[n] /= sum;
            return result;
        }
    }
}
