package eu.dariolucia.jsatdecode.decoder.base.monitoring;

import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.util.Arrays;
import java.util.Map;

/**
 * Stateless snapshot assembly for polling callers. Producers retain their existing fields, update
 * cadence and coherent publication records. Callers capture counters and carrier pairs and apply
 * mission availability rules before calling; this helper infers no locks and reads no live DSP.
 */
public final class DecoderSnapshots {
    @SuppressWarnings("unchecked")
    private static final Map.Entry<String, Object>[] NO_DIAGNOSTICS =
            (Map.Entry<String, Object>[]) new Map.Entry<?, ?>[0];

    private DecoderSnapshots() {}

    /**
     * Computes lifetime frame rate in the original multiply-then-divide order.
     *
     * @param accepted captured accepted frames
     * @param sampleRate input complex samples per second
     * @param samples captured processed input samples
     * @return accepted*sampleRate/samples, or zero before signal time
     */
    public static double frameRate(long accepted, double sampleRate, long samples) {
        return samples > 0 ? accepted * sampleRate / samples : 0.0;
    }

    /**
     * Materializes the mandatory schema on the polling caller; allocations stay outside DSP.
     *
     * @param timestampNanos UTC epoch-nanosecond observation time
     * @param snrDb mission-gated SNR or NaN
     * @param carrierLock captured carrier state
     * @param offsetHz coherently captured, mission-gated offset or NaN
     * @param symbolLock captured symbol state
     * @param symbolRateBaud documented nominal or measured rate
     * @param frameLock captured frame state
     * @param accepted captured accepted frames
     * @param rejected captured rejected complete candidates
     * @param frameRateHz lifetime accepted frames per signal second
     * @return immutable snapshot with good+bad derived from the supplied counters
     * @throws ArithmeticException if the total overflows
     */
    public static MonitoringSnapshot create(
            long timestampNanos,
            double snrDb,
            String carrierLock,
            double offsetHz,
            String symbolLock,
            double symbolRateBaud,
            String frameLock,
            long accepted,
            long rejected,
            double frameRateHz) {
        return withDiagnostics(
                timestampNanos,
                snrDb,
                carrierLock,
                offsetHz,
                symbolLock,
                symbolRateBaud,
                frameLock,
                accepted,
                rejected,
                frameRateHz,
                NO_DIAGNOSTICS);
    }

    /**
     * Materializes the common schema and mission-specific diagnostics on the polling caller.
     *
     * @param timestampNanos UTC epoch-nanosecond observation time
     * @param snrDb mission-gated SNR or NaN
     * @param carrierLock captured carrier state
     * @param offsetHz coherently captured, mission-gated offset or NaN
     * @param symbolLock captured symbol state
     * @param symbolRateBaud documented nominal or measured rate
     * @param frameLock captured frame state
     * @param accepted captured accepted frames
     * @param rejected captured rejected complete candidates
     * @param frameRateHz lifetime accepted frames per signal second
     * @param diagnostics additional immutable-valued entries; duplicate keys are rejected
     * @return immutable snapshot retaining every supplied diagnostic
     * @throws ArithmeticException if the total overflows
     */
    @SafeVarargs
    public static MonitoringSnapshot withDiagnostics(
            long timestampNanos,
            double snrDb,
            String carrierLock,
            double offsetHz,
            String symbolLock,
            double symbolRateBaud,
            String frameLock,
            long accepted,
            long rejected,
            double frameRateHz,
            Map.Entry<String, Object>... diagnostics) {
        // Copy the caller's typed entries; the array never escapes snapshot construction.
        @SuppressWarnings("varargs")
        Map.Entry<String, Object>[] entries = Arrays.copyOf(diagnostics, 10 + diagnostics.length);
        System.arraycopy(entries, 0, entries, 10, diagnostics.length);
        entries[0] = Map.entry(DecoderStatusKeys.SIGNAL_SNR_DB, snrDb);
        entries[1] = Map.entry(DecoderStatusKeys.CARRIER_LOCK, carrierLock);
        entries[2] = Map.entry(DecoderStatusKeys.CARRIER_OFFSET_HZ, offsetHz);
        entries[3] = Map.entry(DecoderStatusKeys.SYMBOL_LOCK, symbolLock);
        entries[4] = Map.entry(DecoderStatusKeys.SYMBOL_RATE_BAUD, symbolRateBaud);
        entries[5] = Map.entry(DecoderStatusKeys.FRAME_LOCK, frameLock);
        entries[6] = Map.entry(DecoderStatusKeys.FRAME_GOOD, accepted);
        entries[7] = Map.entry(DecoderStatusKeys.FRAME_BAD, rejected);
        entries[8] = Map.entry(DecoderStatusKeys.FRAME_TOTAL, Math.addExact(accepted, rejected));
        entries[9] = Map.entry(DecoderStatusKeys.FRAME_RATE_HZ, frameRateHz);
        return new MonitoringSnapshot(timestampNanos, Map.ofEntries(entries));
    }
}
