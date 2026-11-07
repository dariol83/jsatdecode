package eu.dariolucia.jsatdecode.ui.swing;

/**
 * Immutable UI-only SNR calibration; no decoder parameters are modified.
 * Five strictly increasing finite thresholds map to 0, 25, 50, 75 and 100 percent.
 *
 * @param unusableSnrDb  lower display threshold
 * @param poorSnrDb      poor threshold
 * @param fairSnrDb      fair threshold
 * @param goodSnrDb      good threshold
 * @param excellentSnrDb upper display threshold
 */
public record SignalQualityProfile(double unusableSnrDb, double poorSnrDb, double fairSnrDb,
                                   double goodSnrDb, double excellentSnrDb) {
    /**
     * Validates a descriptive display profile.
     *
     * @param unusableSnrDb  lower threshold
     * @param poorSnrDb      poor threshold
     * @param fairSnrDb      fair threshold
     * @param goodSnrDb      good threshold
     * @param excellentSnrDb upper threshold
     */
    public SignalQualityProfile {
        if (!Double.isFinite(unusableSnrDb) || !Double.isFinite(poorSnrDb) || !Double.isFinite(fairSnrDb) ||
                !Double.isFinite(goodSnrDb) || !Double.isFinite(excellentSnrDb) ||
                !(unusableSnrDb < poorSnrDb && poorSnrDb < fairSnrDb && fairSnrDb < goodSnrDb && goodSnrDb < excellentSnrDb))
            throw new IllegalArgumentException("Quality SNR thresholds must be finite and strictly increasing");
    }

    /**
     * Piecewise-linear normalization with endpoint clamping. Shareable across
     * workers; NaN/infinity produce NaN rather than a false confidence score.
     *
     * @param snrDb observed SNR
     * @return 0 through 100, or NaN when unavailable
     */
    public double percent(double snrDb) {
        if (!Double.isFinite(snrDb)) return Double.NaN;
        if (snrDb <= unusableSnrDb) return 0;
        if (snrDb >= excellentSnrDb) return 100;
        if (snrDb < poorSnrDb) return 25 * fraction(snrDb, unusableSnrDb, poorSnrDb);
        if (snrDb < fairSnrDb) return 25 + 25 * fraction(snrDb, poorSnrDb, fairSnrDb);
        if (snrDb < goodSnrDb) return 50 + 25 * fraction(snrDb, fairSnrDb, goodSnrDb);
        return 75 + 25 * fraction(snrDb, goodSnrDb, excellentSnrDb);
    }

    private static double fraction(double value, double low, double high) {
        // Half-scaled differences also handle finite thresholds spanning a range
        // whose direct subtraction would overflow a double.
        double span = high - low;
        return Double.isFinite(span) ? (value - low) / span : (value / 2 - low / 2) / (high / 2 - low / 2);
    }
}
