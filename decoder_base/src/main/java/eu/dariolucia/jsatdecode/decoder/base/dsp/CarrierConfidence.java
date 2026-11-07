package eu.dariolucia.jsatdecode.decoder.base.dsp;

/**
 * Monitoring-only residual-carrier measurement after PLL derotation. A one-pole low-pass with
 * corner max(250 Hz, 8 * PLL bandwidth) suppresses PM sidebands before separate 10 Hz averages
 * measure complex mean, narrowband power and PLL frequency. Lock thresholds and persistence
 * remain caller-owned.
 *
 * <p>Instances belong to one sample-processing worker and are not thread-safe. History and
 * outputs start at zero and persist across input blocks. Processing is synchronous, nonblocking
 * and allocation-free. Callers validate samples; nonfinite values propagate into filter history.
 */
public final class CarrierConfidence {
    private final double channelAlpha, averageAlpha, minimumPower;
    private double narrowI, narrowQ, meanI, meanQ, power, coherence, frequency;

    /**
     * Creates a measurement with zero initial history and a caller-selected power floor.
     *
     * @param sampleRate validated positive sample rate at the measurement point, in samples/s
     * @param pllBandwidth validated positive PLL bandwidth, in Hz
     * @param minimumPower validated nonnegative narrowband power floor; confidence is zero
     *                     unless averaged power exceeds this value
     */
    public CarrierConfidence(double sampleRate, double pllBandwidth, double minimumPower) {
        this.minimumPower = minimumPower;
        // Leave room outside the tracking loop to distinguish a carrier from noise
        // followed by the PLL. Suppress PM sidebands before measuring confidence.
        double bandwidth = Math.max(250, 8 * pllBandwidth);
        channelAlpha = -Math.expm1(-2 * Math.PI * bandwidth / sampleRate);
        averageAlpha = -Math.expm1(-2 * Math.PI * 10 / sampleRate);
    }

    /**
     * Processes one PLL-derotated complex sample and replaces the latest measurements.
     *
     * @param i in-phase sample at the measurement point
     * @param q quadrature sample at the measurement point
     * @param pllFrequency current PLL frequency in radians per sample
     */
    public void sample(double i, double q, double pllFrequency) {
        narrowI += channelAlpha * (i - narrowI);
        narrowQ += channelAlpha * (q - narrowQ);
        meanI += averageAlpha * (narrowI - meanI);
        meanQ += averageAlpha * (narrowQ - meanQ);
        power += averageAlpha * (narrowI * narrowI + narrowQ * narrowQ - power);
        frequency += averageAlpha * (pllFrequency - frequency);
        coherence = power > minimumPower ? Math.min(1, (meanI * meanI + meanQ * meanQ) / power) : 0;
    }

    /**
     * Returns coherent mean power divided by averaged narrowband power, or zero below the floor.
     *
     * @return latest confidence in 0..1 for finite samples, initially zero
     */
    public double coherence() {
        return coherence;
    }

    /**
     * Returns the latest PLL frequency after 10 Hz averaging.
     *
     * @return smoothed frequency in radians per sample, initially zero
     */
    public double frequency() {
        return frequency;
    }
}
