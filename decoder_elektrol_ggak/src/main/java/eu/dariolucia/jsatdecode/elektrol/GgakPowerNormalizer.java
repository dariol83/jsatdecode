package eu.dariolucia.jsatdecode.elektrol;

/** GRC power normalizer with deterministic, complete 250-sample segments. */
final class GgakPowerNormalizer {
    static final int SEGMENT = 250;
    private static final double ALPHA = 1 - Math.exp(-SEGMENT / (.25 * ElektroLGgakSettings.BASE_RATE));
    final float[] i = new float[SEGMENT], q = new float[SEGMENT];
    private int size;
    private double power = Double.NaN;

    boolean add(float inI, float inQ) {
        i[size] = inI;
        q[size++] = inQ;
        if (size < SEGMENT) return false;
        size = 0;
        double p = 0;
        for (int n = 0; n < SEGMENT; n++) p += (double) i[n] * i[n] + (double) q[n] * q[n];
        p /= SEGMENT;
        if (p <= 1e-30) {
            java.util.Arrays.fill(i, 0);
            java.util.Arrays.fill(q, 0);
        } else {
            power = Double.isNaN(power) ? p : power + ALPHA * (p - power);
            double amplitude = Math.sqrt(power);
            for (int n = 0; n < SEGMENT; n++) {
                i[n] = (float) (i[n] / amplitude);
                q[n] = (float) (q[n] / amplitude);
            }
        }
        return true;
    }
}
