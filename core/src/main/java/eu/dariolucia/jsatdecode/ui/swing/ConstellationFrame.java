package eu.dariolucia.jsatdecode.ui.swing;

/**
 * Immutable bounded display symbols, oldest first. No arrays or live providers
 * escape; retained frames remain valid after worker scratch/history reuse.
 */
public final class ConstellationFrame {
    /**
     * Latest observation state.
     */
    public enum State {
        /**
         * Finite symbols available.
         */
        READY,
        /**
         * No symbols observed yet.
         */
        WAITING,
        /**
         * No symbols tap.
         */
        UNAVAILABLE,
        /**
         * Observation contained no finite symbols.
         */
        INVALID,
        /**
         * Symbol observer disabled after failure.
         */
        ERROR
    }

    private final State state;
    private final float[] iq;
    private final double extent;
    private final long timestamp;

    ConstellationFrame(State state, float[] values, int points, double extent, long timestamp) {
        this.state = state;
        this.iq = java.util.Arrays.copyOf(values, points * 2);
        this.extent = extent;
        this.timestamp = timestamp;
    }

    /**
     * Returns observation availability.
     *
     * @return current state
     */
    public State state() {
        return state;
    }

    /**
     * Returns bounded retained point count.
     *
     * @return complex symbols
     */
    public int points() {
        return iq.length / 2;
    }

    /**
     * Reads in-phase value without allocation.
     *
     * @param index valid symbol index
     * @return I
     */
    public float i(int index) {
        return iq[2 * java.util.Objects.checkIndex(index, points())];
    }

    /**
     * Reads quadrature value without allocation.
     *
     * @param index valid symbol index
     * @return Q
     */
    public float q(int index) {
        return iq[2 * java.util.Objects.checkIndex(index, points()) + 1];
    }

    /**
     * Returns shared symmetric I/Q half-range computed outside Swing.
     *
     * @return positive finite half-range
     */
    public double extent() {
        return extent;
    }

    /**
     * Returns newest accepted snapshot time; holding an empty observation does
     * not change it. Time is UTC epoch nanoseconds.
     *
     * @return snapshot nanoseconds
     */
    public long timestampNanos() {
        return timestamp;
    }
}
