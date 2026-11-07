package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.SampleSink;

/**
 * Optional synchronous source-to-decoder translation. Mutable oscillator and
 * scratch storage belong exclusively to the source's serial sample caller.
 * Positive offsets multiply by exp(+j*2*pi*offset/Fs), shifting tones upward.
 */
final class SourceFrequencyRotator implements SampleSink {
    private final SampleSink downstream;
    private final double offsetHz;
    private double sampleRate, stepI, stepQ;
    private double phaseI = 1, phaseQ;
    private int normalizationSamples;
    private float[] scratch = new float[0];

    private SourceFrequencyRotator(SampleSink downstream, double offsetHz) {
        this.downstream = downstream;
        this.offsetHz = offsetHz;
    }

    // The caller validates configuration once before acquiring resources. Zero
    // (including negative zero) installs no rotator and preserves raw IQ bits.
    static SampleSink connect(SampleSink downstream, double offsetHz) {
        return offsetHz == 0 ? downstream : new SourceFrequencyRotator(downstream, offsetHz);
    }

    @Override
    public void accept(ComplexSampleBlock block) {
        int count = block.complexSamples();
        if (count == 0) {
            downstream.accept(block);
            return;
        }
        if (sampleRate != block.sampleRate()) {
            sampleRate = block.sampleRate();
            // Reduce before multiplying, avoiding overflow for large finite
            // offsets. The delivered rate can differ from the hardware PHY rate.
            double turns = Math.IEEEremainder(offsetHz, sampleRate) / sampleRate;
            // Cardinal increments are exactly representable. In particular,
            // cos(pi/2)'s rounding residue must not accumulate into phase drift.
            if (turns == 0) {
                stepI = 1;
                stepQ = 0;
            } else if (Math.abs(turns) == 0.25) {
                stepI = 0;
                stepQ = Math.copySign(1, turns);
            } else if (Math.abs(turns) == 0.5) {
                stepI = -1;
                stepQ = 0;
            } else {
                double angle = turns * (2 * Math.PI);
                stepI = Math.cos(angle);
                stepQ = Math.sin(angle);
            }
        }
        if (scratch.length < count * 2) scratch = new float[count * 2];
        double real = phaseI, imaginary = phaseQ;
        for (int n = 0; n < count; n++) {
            double i = block.i(n), q = block.q(n);
            scratch[2 * n] = (float) (i * real - q * imaginary);
            scratch[2 * n + 1] = (float) (i * imaginary + q * real);
            double nextReal = real * stepI - imaginary * stepQ;
            imaginary = real * stepQ + imaginary * stepI;
            real = nextReal;
            // Sample-count cadence, independent of source block boundaries.
            if (++normalizationSamples == 512) {
                double magnitude = Math.hypot(real, imaginary);
                real /= magnitude;
                imaginary /= magnitude;
                normalizationSamples = 0;
            }
        }
        phaseI = real;
        phaseQ = imaginary;
        // The immutable block copies only the valid prefix. Scratch can be reused
        // immediately, even when a downstream consumer retains earlier blocks.
        downstream.accept(new ComplexSampleBlock(scratch, count, block.sampleIndex(),
                block.timestampNanos(), block.sampleRate()));
    }
}
