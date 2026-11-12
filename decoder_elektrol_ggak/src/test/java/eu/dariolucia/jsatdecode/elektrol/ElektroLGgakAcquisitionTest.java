package eu.dariolucia.jsatdecode.elektrol;

/** Independent tone and analytic step-response checks for the v1.5 acquisition stages. */
final class ElektroLGgakAcquisitionTest {
    private static final double CHANNEL_RATE = 250_000;
    private static int checks;

    private ElektroLGgakAcquisitionTest() {}

    static int run() {
        checks = 0;
        silentAcquisition();
        offBinTone(23_017.3);
        offBinTone(-23_129.7);
        powerNormalization();
        return checks;
    }

    /** Allows a focused run without the longer complete decoder tests. */
    public static void main(String[] args) {
        System.out.println("Elektro-L GGAK acquisition: " + run() + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static void silentAcquisition() {
        var acquisition = new GgakCarrierAcquisition();
        boolean silent = true, searching = true;
        // Include several FFT windows and a partial final window. No EOF samples are supplied.
        for (int n = 0; n < 3 * GgakCarrierAcquisition.FFT_SIZE + 17; n++) {
            acquisition.sample(0, 0);
            silent &= acquisition.i == 0 && acquisition.q == 0;
            searching &= Double.isNaN(acquisition.frequencyHz());
        }
        check(silent, "Silent AFC startup emits only zeros, including its partial FFT window");
        check(searching, "Silence never qualifies as a carrier acquisition");
        check(GgakCarrierAcquisition.DELAY == 2 * GgakCarrierAcquisition.FFT_SIZE,
                "AFC delay preserves the two-window reference acquisition lookahead");
    }

    private static void offBinTone(double offsetHz) {
        var acquisition = new GgakCarrierAcquisition();
        double amplitude = 1e-4, step = 2 * Math.PI * offsetHz / CHANNEL_RATE;
        boolean initialSilence = true, initialSearch = true;
        double maximumMagnitudeError = 0, maximumPhaseStepError = 0;
        double cross = 0, dot = 0;
        float previousI = 0, previousQ = 0;
        int outputs = 0;
        int length = 4 * GgakCarrierAcquisition.FFT_SIZE + 123;
        for (int n = 0; n < length; n++) {
            double phase = step * n + .37;
            acquisition.sample((float) (amplitude * Math.cos(phase)), (float) (amplitude * Math.sin(phase)));
            if (n < GgakCarrierAcquisition.DELAY - 1)
                initialSearch &= Double.isNaN(acquisition.frequencyHz());
            if (n < GgakCarrierAcquisition.DELAY) {
                initialSilence &= acquisition.i == 0 && acquisition.q == 0;
                continue;
            }
            outputs++;
            maximumMagnitudeError = Math.max(maximumMagnitudeError,
                    Math.abs(Math.hypot(acquisition.i, acquisition.q) / amplitude - 1));
            if (outputs > 1) {
                double currentCross = (double) previousI * acquisition.q - (double) previousQ * acquisition.i;
                double currentDot = (double) previousI * acquisition.i + (double) previousQ * acquisition.q;
                cross += currentCross;
                dot += currentDot;
                // A phase jump at a later FFT boundary must not hide in a long-term mean.
                double expectedStep = 2 * Math.PI * (offsetHz - acquisition.frequencyHz()) / CHANNEL_RATE;
                maximumPhaseStepError = Math.max(maximumPhaseStepError,
                        Math.abs(Math.atan2(currentCross, currentDot) - expectedStep));
            }
            previousI = acquisition.i;
            previousQ = acquisition.q;
        }
        double residualHz = Math.atan2(cross, dot) * CHANNEL_RATE / (2 * Math.PI);
        check(initialSearch, "AFC waits for two complete FFT windows at offset " + offsetHz);
        check(initialSilence, "AFC fixed startup delay at offset " + offsetHz);
        check(Double.isFinite(acquisition.frequencyHz()) && Math.abs(acquisition.frequencyHz() - offsetHz) < .2,
                "Weak off-bin tone acquires with sub-bin accuracy at offset " + offsetHz);
        check(outputs == length - GgakCarrierAcquisition.DELAY && maximumMagnitudeError < 2e-7,
                "AFC preserves delayed weak amplitude through a partial FFT window at offset " + offsetHz);
        check(Math.abs(residualHz) < .2, "AFC removes the measured carrier at offset " + offsetHz);
        check(maximumPhaseStepError < 1e-6,
                "AFC correction phase is continuous across subsequent FFT boundaries at offset " + offsetHz);
    }

    private static void powerNormalization() {
        check(GgakPowerNormalizer.SEGMENT == 250, "Power normalization uses 5 ms segments at 50 ksps");
        var ordinary = new GgakPowerNormalizer();
        var weak = new GgakPowerNormalizer();
        boolean boundaries = true;
        for (int n = 0; n < 250; n++) {
            float i = n % 2 == 0 ? 3 : -4, q = n % 2 == 0 ? 4 : 3;
            boundaries &= ordinary.add(i, q) == (n == 249);
            boundaries &= weak.add(i * 1e-4f, q * 1e-4f) == (n == 249);
        }
        check(boundaries, "Normalizer publishes only a complete segment");
        double meanPower = 0;
        boolean phasePreserved = true, scaleInvariant = true;
        for (int n = 0; n < 250; n++) {
            meanPower += (double) ordinary.i[n] * ordinary.i[n] + (double) ordinary.q[n] * ordinary.q[n];
            phasePreserved &= Math.abs(ordinary.i[n] - (n % 2 == 0 ? .6 : -.8)) < 1e-7
                    && Math.abs(ordinary.q[n] - (n % 2 == 0 ? .8 : .6)) < 1e-7;
            scaleInvariant &= Math.abs(ordinary.i[n] - weak.i[n]) < 2e-7
                    && Math.abs(ordinary.q[n] - weak.q[n]) < 2e-7;
        }
        check(Math.abs(meanPower / 250 - 1) < 1e-7 && phasePreserved,
                "First varying-phase segment has unit mean power and preserves complex direction");

        boolean zeroOutput = true;
        // A silent interval must neither divide by zero nor age the preceding measured power.
        for (int segment = 0; segment < 20; segment++) {
            boundaries &= segment(ordinary, 0, 0);
            boundaries &= segment(weak, 0, 0);
            for (int n = 0; n < 250; n++)
                zeroOutput &= ordinary.i[n] == 0 && ordinary.q[n] == 0
                        && weak.i[n] == 0 && weak.q[n] == 0;
        }
        check(zeroOutput, "Silent complete segments emit finite zeros");

        double maximumResponseError = 0;
        for (int segment = 1; segment <= 50; segment++) {
            boundaries &= segment(ordinary, 6, 8);
            boundaries &= segment(weak, 6e-4f, 8e-4f);
            // Independent continuous-time step response: initial power 25, final power 100,
            // elapsed segment * 5 ms, and a 250 ms time constant. Silence adds no elapsed time.
            double measuredPower = 100 - 75 * Math.exp(-(segment * .005) / .25);
            double expectedI = 6 / Math.sqrt(measuredPower), expectedQ = 8 / Math.sqrt(measuredPower);
            for (int n = 0; n < 250; n++) {
                maximumResponseError = Math.max(maximumResponseError,
                        Math.max(Math.abs(ordinary.i[n] - expectedI), Math.abs(ordinary.q[n] - expectedQ)));
                scaleInvariant &= Math.abs(ordinary.i[n] - weak.i[n]) < 3e-7
                        && Math.abs(ordinary.q[n] - weak.q[n]) < 3e-7;
            }
        }
        check(boundaries, "Normalizer segment boundaries remain fixed across silence and amplitude steps");
        check(maximumResponseError < 2e-7,
                "Power estimate follows the 250 ms analytic response and holds its state across silence");
        check(scaleInvariant, "Normalizer output is invariant to a 1e-4 input amplitude scale");
        boolean incomplete = true;
        for (int n = 0; n < 249; n++) incomplete &= !ordinary.add(1, 0);
        check(incomplete, "Normalizer does not publish an incomplete terminal segment");
    }

    private static boolean segment(GgakPowerNormalizer normalizer, float i, float q) {
        boolean boundaries = true;
        for (int n = 0; n < 250; n++) boundaries &= normalizer.add(i, q) == (n == 249);
        return boundaries;
    }
}
