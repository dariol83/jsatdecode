package eu.dariolucia.jsatdecode.core.runtime;

import eu.dariolucia.jsatdecode.api.source.IqBufferLease;
import eu.dariolucia.jsatdecode.api.source.IqBufferPool;
import eu.dariolucia.jsatdecode.api.source.IqSampleView;
import eu.dariolucia.jsatdecode.api.source.IqWriteAccess;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleSink;
import eu.dariolucia.jsatdecode.api.source.SourceException;

/* Serial source-worker translation. The input is never modified; the output
   has a distinct pooled owner and may outlive this callback if retained. */
final class LeasedFrequencyRotator implements LeasedSampleSink {
    private final LeasedSampleSink downstream;
    private final IqBufferPool pool;
    private final double offsetHz;
    private double sampleRate, stepI, stepQ, phaseI = 1, phaseQ;
    private int normalizationSamples;

    private LeasedFrequencyRotator(LeasedSampleSink downstream, double offsetHz, IqBufferPool pool) {
        this.downstream = downstream;
        this.offsetHz = offsetHz;
        this.pool = pool;
    }

    static LeasedSampleSink connect(LeasedSampleSink downstream, double offsetHz, IqBufferPool pool) {
        return offsetHz == 0 ? downstream : new LeasedFrequencyRotator(downstream, offsetHz, pool);
    }

    @Override
    public void accept(LeasedSampleBlock block) {
        int count = block.complexSamples();
        if (count == 0) {
            downstream.accept(block);
            return;
        }
        try (IqBufferLease output = pool.tryAcquire(count).orElseThrow(
                () -> new SourceException("Frequency rotator IQ buffer pool exhausted"))) {
            block.withView(input -> output.withWriteAccess(target -> rotate(input, target)));
            output.publish(0, count);
            try (LeasedSampleBlock translated = LeasedSampleBlock.from(output,
                    block.sampleIndex(), block.timestampNanos(), block.sampleRate())) {
                downstream.accept(translated);
            }
        }
    }

    private void rotate(IqSampleView block, IqWriteAccess target) {
        if (sampleRate != block.sampleRate()) {
            sampleRate = block.sampleRate();
            double turns = Math.IEEEremainder(offsetHz, sampleRate) / sampleRate;
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
        double real = phaseI, imaginary = phaseQ;
        for (int n = 0; n < block.complexSamples(); n++) {
            double i = block.i(n), q = block.q(n);
            float outI = (float) (i * real - q * imaginary);
            float outQ = (float) (i * imaginary + q * real);
            target.put(n, outI, outQ);
            double nextReal = real * stepI - imaginary * stepQ;
            imaginary = real * stepQ + imaginary * stepI;
            real = nextReal;
            if (++normalizationSamples == 512) {
                double magnitude = Math.hypot(real, imaginary);
                real /= magnitude;
                imaginary /= magnitude;
                normalizationSamples = 0;
            }
        }
        phaseI = real;
        phaseQ = imaginary;
    }
}
