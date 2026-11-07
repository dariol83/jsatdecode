package eu.dariolucia.jsatdecode.distribution.zmq.client;

import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

/**
 * Standard keys inside a status envelope's metrics and metric_timestamps_ns maps.
 * Decoder keys reuse the mandatory framework vocabulary; the two output totals
 * belong to the runtime. Initial/direct-publisher status maps can omit these keys.
 * Additional source, decoder and output metrics remain extensible string keys.
 */
public enum MonitoringMetricKey {
    /** Double signal SNR in dB, or NaN when unavailable. */
    SIGNAL_SNR_DB(DecoderStatusKeys.SIGNAL_SNR_DB),
    /** String independent carrier state: SEARCHING/LOCKED/UNLOCKED/UNKNOWN. */
    CARRIER_LOCK(DecoderStatusKeys.CARRIER_LOCK),
    /** Double carrier offset in Hz; NaN unless running and carrier locked. */
    CARRIER_OFFSET_HZ(DecoderStatusKeys.CARRIER_OFFSET_HZ),
    /** String independent timing recovery state: SEARCHING/LOCKED/UNLOCKED/UNKNOWN. */
    SYMBOL_LOCK(DecoderStatusKeys.SYMBOL_LOCK),
    /** Double symbol rate in baud, or NaN when unavailable. */
    SYMBOL_RATE_BAUD(DecoderStatusKeys.SYMBOL_RATE_BAUD),
    /** String independent frame synchronization state: SEARCHING/LOCKED/UNLOCKED/UNKNOWN. */
    FRAME_LOCK(DecoderStatusKeys.FRAME_LOCK),
    /** Long accepted valid frame count after duplicate suppression. */
    FRAME_GOOD(DecoderStatusKeys.FRAME_GOOD),
    /** Long rejected complete candidate count. */
    FRAME_BAD(DecoderStatusKeys.FRAME_BAD),
    /** Long good plus bad frame count; excludes duplicates/incomplete candidates. */
    FRAME_TOTAL(DecoderStatusKeys.FRAME_TOTAL),
    /** Double lifetime accepted frames per processed signal second. */
    FRAME_RATE_HZ(DecoderStatusKeys.FRAME_RATE_HZ),
    /** Long locally accepted output frame total; not subscriber delivery. */
    OUTPUT_FRAMES_SENT("output.frames_sent"),
    /** Long locally observable output frame drop total. */
    OUTPUT_FRAMES_DROPPED("output.frames_dropped");

    private final String key;

    MonitoringMetricKey(String key) {
        this.key = key;
    }

    /**
     * Returns the exact standardized string used in the nested status maps.
     * @return metric key, unchanged from the publisher
     */
    public String key() {
        return key;
    }
}
