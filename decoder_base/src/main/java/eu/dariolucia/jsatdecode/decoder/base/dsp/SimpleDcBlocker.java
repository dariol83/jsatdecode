package eu.dariolucia.jsatdecode.decoder.base.dsp;

/**
 * Push-based complex DC removal using the original Hinode single-pole estimator.
 * Each channel updates its mean with {@code mean = .0001f * input + .9999f * mean}
 * and outputs {@code input - mean}, using float arithmetic in that order.
 * Histories and outputs start at zero and persist across arbitrary input blocks.
 * Bypass copies input bits exactly and does not update the estimator.
 *
 * <p>Instances belong to one sample-processing worker and are not thread-safe.
 * Processing is synchronous, nonblocking and allocation-free. Callers validate
 * samples; nonfinite values are propagated and can contaminate enabled history.
 * This utility has no external DSP dependency and is not wired into any decoder.
 */
public final class SimpleDcBlocker {
    private final boolean enabled;
    private float dcI, dcQ, i, q;

    /**
     * Creates an enabled blocker with zero initial history.
     */
    public SimpleDcBlocker() {
        this(true);
    }

    /**
     * Creates a blocker with fixed enablement and zero initial history.
     *
     * @param enabled true for single-pole DC removal, false for exact bypass
     */
    public SimpleDcBlocker(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Processes one complex sample and replaces the outputs read by {@link #i()}
     * and {@link #q()}. No sample is delayed or buffered for later emission.
     *
     * @param inputI input in-phase value
     * @param inputQ input quadrature value
     */
    public void sample(float inputI, float inputQ) {
        if (enabled) {
            dcI = .0001f * inputI + .9999f * dcI;
            dcQ = .0001f * inputQ + .9999f * dcQ;
            i = inputI - dcI;
            q = inputQ - dcQ;
        } else {
            i = inputI;
            q = inputQ;
        }
    }

    /**
     * Returns the latest in-phase output, or zero before the first sample.
     *
     * @return latest in-phase value
     */
    public float i() {
        return i;
    }

    /**
     * Returns the latest quadrature output, or zero before the first sample.
     *
     * @return latest quadrature value
     */
    public float q() {
        return q;
    }
}
