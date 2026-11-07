package eu.dariolucia.jsatdecode.ui.swing;

import java.util.List;

/**
 * Immutable, bounded display data. No live source/decoder/tap reference crosses
 * to Swing; models may be overwritten before display when the EDT is slow.
 *
 * @param rows           bounded status rows
 * @param omittedMetrics number of undisplayed metrics
 * @param quality        precomputed signal quality
 * @param taps           RF/baseband/symbol availability, without sample arrays
 * @param timestampNanos UTC epoch-nanosecond observation time
 * @param spectra        immutable precomputed traces
 * @param constellation  immutable retained symbols, null for older status/spectrum-only callers
 */
public record UiViewModel(List<StatusRow> rows, int omittedMetrics, Quality quality, List<TapStatus> taps,
                          long timestampNanos, List<SpectrumFrame> spectra, ConstellationFrame constellation) {
    /**
     * Creates a spectrum-only model with no symbol observations.
     *
     * @param rows           status rows
     * @param omittedMetrics omitted count
     * @param quality        quality observation
     * @param taps           tap metadata
     * @param timestampNanos observation time
     * @param spectra        precomputed traces
     */
    public UiViewModel(List<StatusRow> rows, int omittedMetrics, Quality quality, List<TapStatus> taps, long timestampNanos, List<SpectrumFrame> spectra) {
        this(rows, omittedMetrics, quality, taps, timestampNanos, spectra, null);
    }

    /**
     * Creates a status-only model for compatibility.
     *
     * @param rows           status rows
     * @param omittedMetrics omitted count
     * @param quality        quality observation
     * @param taps           tap metadata
     * @param timestampNanos observation time
     */
    public UiViewModel(List<StatusRow> rows, int omittedMetrics, Quality quality, List<TapStatus> taps, long timestampNanos) {
        this(rows, omittedMetrics, quality, taps, timestampNanos, List.of());
    }

    /**
     * Copies model collections for safe publication.
     *
     * @param rows           status rows
     * @param omittedMetrics omitted row count
     * @param quality        computed quality
     * @param taps           tap observations
     * @param timestampNanos UTC epoch-nanosecond time
     * @param spectra        precomputed traces
     * @param constellation  retained symbols or null when not enabled
     */
    public UiViewModel {
        rows = List.copyOf(rows);
        taps = List.copyOf(taps);
        spectra = List.copyOf(spectra);
        java.util.Objects.requireNonNull(quality);
        if (omittedMetrics < 0) throw new IllegalArgumentException("Negative omitted metric count");
    }

    /**
     * Formatted on the observer worker; immutable strings only.
     *
     * @param metric metric key
     * @param value  display value
     * @param unit   descriptor unit, possibly empty
     */
    public record StatusRow(String metric, String value, String unit) {
    }

    /**
     * Actual independent lock observation; UNKNOWN is never treated as locked.
     */
    public enum LockState {
        /**
         * No valid observation.
         */
        UNKNOWN,
        /**
         * Recovery reports lock.
         */
        LOCKED,
        /**
         * Recovery reports no lock.
         */
        UNLOCKED,
        /**
         * Recovery reports acquisition in progress.
         */
        SEARCHING
    }

    /**
     * UI-only score, with separate true lock observations.
     *
     * @param percent        smoothed 0..100, or NaN when unavailable
     * @param snrDb          raw SNR, or NaN
     * @param snrValid       true only for a finite numeric SNR
     * @param carrier        independent carrier lock
     * @param symbol         independent symbol lock
     * @param frame          independent frame lock
     * @param timestampNanos UTC epoch-nanosecond observation time
     */
    public record Quality(double percent, double snrDb, boolean snrValid, LockState carrier, LockState symbol,
                          LockState frame, long timestampNanos) {
    }

    /**
     * Tap metadata only; spectrum traces are published separately.
     *
     * @param name       RF spectrum, Baseband spectrum or Constellation
     * @param available  provider has this tap
     * @param samples    observed sample count, possibly zero under contention
     * @param sampleRate positive samples/second if available, otherwise NaN
     */
    public record TapStatus(String name, boolean available, int samples, double sampleRate) {
    }
}
