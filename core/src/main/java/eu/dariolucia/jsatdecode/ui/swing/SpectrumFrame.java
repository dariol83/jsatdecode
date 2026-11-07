package eu.dariolucia.jsatdecode.ui.swing;

import eu.dariolucia.jsatdecode.dsp.fft.WindowFunction;

import java.util.Objects;

/**
 * Immutable, lifetime-isolated reduced spectrum for EDT rendering. It owns its
 * primitive trace, never exposes arrays, and contains no live provider reference.
 * Frequencies use shifted FFT order; decibels are uncalibrated bin power relative
 * to one, without FFT-size, window-gain, hardware or bandwidth normalization.
 */
public final class SpectrumFrame {
    /**
     * Observation outcome; WAITING/UNAVAILABLE are normal, not failures.
     */
    public enum State {
        /**
         * A complete analysis is present.
         */
        READY,
        /**
         * No complete snapshot yet.
         */
        WAITING,
        /**
         * Decoder supplies no such tap.
         */
        UNAVAILABLE,
        /**
         * Latest input/axis contained invalid values.
         */
        INVALID,
        /**
         * This panel's provider/analysis has been disabled after failure.
         */
        ERROR
    }

    private final String name;
    private final State state;
    private final int fftSize;
    private final double sampleRate, centerHz, floorDb, ceilingDb;
    private final boolean absolute;
    private final WindowFunction window;
    private final long timestampNanos;
    private final double[] db;

    /**
     * Copies a reduced trace using the configured display range. Construction
     * may run on an analysis worker; the completed frame is safe to share.
     *
     * @param name plot title
     * @param state observation availability
     * @param settings FFT, window, range and column limits
     * @param sampleRate complex samples per second, or NaN while waiting
     * @param centerHz center frequency in Hz
     * @param absolute whether the center is an absolute RF frequency
     * @param timestampNanos snapshot UTC epoch nanoseconds
     * @param values shifted maximum-per-column relative dB, copied before return
     * @throws IllegalArgumentException if the trace exceeds configured bounds
     */
    public SpectrumFrame(String name, State state, SpectrumSettings settings, double sampleRate, double centerHz, boolean absolute,
                  long timestampNanos, double[] values) {
        this(name, state, settings, sampleRate, centerHz, absolute, timestampNanos, values, settings.floorDb(), settings.ceilingDb());
    }

    /**
     * Copies a reduced trace with an independently chosen display range. The
     * input remains caller-owned and may be reused as soon as this returns.
     *
     * @param name plot title
     * @param state observation availability
     * @param settings FFT, window and column limits
     * @param sampleRate complex samples per second, or NaN while waiting
     * @param centerHz center frequency in Hz
     * @param absolute whether the center is an absolute RF frequency
     * @param timestampNanos snapshot UTC epoch nanoseconds
     * @param values shifted maximum-per-column relative dB, copied before return
     * @param displayFloor relative-dB lower display limit
     * @param displayCeiling relative-dB upper display limit
     * @throws IllegalArgumentException if the trace exceeds configured bounds
     */
    public SpectrumFrame(String name, State state, SpectrumSettings settings, double sampleRate, double centerHz, boolean absolute,
                  long timestampNanos, double[] values, double displayFloor, double displayCeiling) {
        this.name = Objects.requireNonNull(name);
        this.state = Objects.requireNonNull(state);
        fftSize = settings.fftSize();
        window = settings.window();
        floorDb = displayFloor;
        ceilingDb = displayCeiling;
        this.sampleRate = sampleRate;
        this.centerHz = centerHz;
        this.absolute = absolute;
        this.timestampNanos = timestampNanos;
        if (values.length > Math.min(fftSize, settings.maximumColumns()))
            throw new IllegalArgumentException("Spectrum column bound exceeded");
        db = values.clone();
    }

    /**
     * Returns the plot name.
     *
     * @return RF spectrum or Baseband spectrum
     */
    public String name() {
        return name;
    }

    /**
     * Returns current availability.
     *
     * @return observation state
     */
    public State state() {
        return state;
    }

    /**
     * Returns the bounded column count, zero without a trace.
     *
     * @return number of reduced columns
     */
    public int columns() {
        return db.length;
    }

    /**
     * Reads one immutable relative-dB value without allocation.
     *
     * @param column valid display column
     * @return maximum bin value represented by that column
     */
    public double dbAt(int column) {
        return db[Objects.checkIndex(column, db.length)];
    }

    /**
     * Returns the center index of the original FFT-bin group in a column.
     *
     * @param column valid display column
     * @return potentially fractional shifted-bin index, preserving uneven groups
     */
    public double binAtColumn(int column) {
        Objects.checkIndex(column, db.length);
        int begin = (int) ((long) column * fftSize / db.length), end = (int) ((long) (column + 1) * fftSize / db.length);
        return (begin + end - 1) / 2.0;
    }

    /**
     * Returns the group's representative center frequency, without an axis array.
     *
     * @param column valid display column
     * @return absolute RF Hz or relative Hz according to absoluteFrequency()
     */
    public double frequencyAtColumn(int column) {
        return centerHz + (binAtColumn(column) - fftSize / 2) * (sampleRate / fftSize);
    }

    /**
     * Returns complex sample rate.
     *
     * @return samples/second, or NaN before a valid observation
     */
    public double sampleRate() {
        return sampleRate;
    }

    /**
     * Returns RF center or zero for a relative axis.
     *
     * @return center Hz
     */
    public double centerHz() {
        return centerHz;
    }

    /**
     * Reports whether the axis uses known absolute RF frequency.
     *
     * @return false for unknown RF center and for baseband
     */
    public boolean absoluteFrequency() {
        return absolute;
    }

    /**
     * Returns fixed FFT size.
     *
     * @return transform length
     */
    public int fftSize() {
        return fftSize;
    }

    /**
     * Returns window convention.
     *
     * @return immutable window kind
     */
    public WindowFunction window() {
        return window;
    }

    /**
     * Returns relative-dB display floor.
     *
     * @return finite lower limit
     */
    public double floorDb() {
        return floorDb;
    }

    /**
     * Returns relative-dB display ceiling, adapted at the observer's range cadence.
     *
     * @return finite upper limit
     */
    public double ceilingDb() {
        return ceilingDb;
    }

    /**
     * Returns the underlying snapshot's UTC epoch-nanosecond timestamp.
     *
     * @return observation nanoseconds
     */
    public long timestampNanos() {
        return timestampNanos;
    }
}
