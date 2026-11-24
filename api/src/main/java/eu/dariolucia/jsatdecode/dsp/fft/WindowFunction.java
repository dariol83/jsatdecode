package eu.dariolucia.jsatdecode.dsp.fft;

/**
 * Symmetric windows with denominator N-1; each singleton window is defined as one.
 */
public enum WindowFunction {
    /**
     * Unit coefficients; no taper.
     */
    RECTANGULAR,
    /**
     * 0.5 - 0.5 cos(2 pi n/(N-1)); recommended monitoring default.
     */
    HANN,
    /**
     * 0.54 - 0.46 cos(2 pi n/(N-1)).
     */
    HAMMING,
    /**
     * 0.42 - 0.5 cos(2 pi n/(N-1)) + 0.08 cos(4 pi n/(N-1)).
     */
    BLACKMAN;

    double coefficient(int index, int size) {
        if (size == 1 || this == RECTANGULAR) return 1;
        double angle = 2 * Math.PI * index / (size - 1);
        return switch (this) {
            case RECTANGULAR -> 1;
            case HANN -> 0.5 - 0.5 * Math.cos(angle);
            case HAMMING -> 0.54 - 0.46 * Math.cos(angle);
            case BLACKMAN -> 0.42 - 0.5 * Math.cos(angle) + 0.08 * Math.cos(2 * angle);
        };
    }
}
