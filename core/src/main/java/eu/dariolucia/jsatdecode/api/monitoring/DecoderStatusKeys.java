package eu.dariolucia.jsatdecode.api.monitoring;

/**
 * Mandatory decoder monitoring key vocabulary. Every Decoder exposes all keys
 * in both its descriptors and snapshots, including before start and after stop.
 * Unknown floating-point measurements use Double.NaN; unsupported lock detection
 * uses UNKNOWN rather than invented lock. Source/runtime/output keys are outside
 * this decoder-owned contract. See docs/decoder-implementation-specification.md
 * for types, units, accounting, lifecycle and concurrency requirements.
 * Constants preserve existing wire strings; accessing them allocates nothing.
 */
public final class DecoderStatusKeys {
    private DecoderStatusKeys() {
    }

    /**
     * DOUBLE, dB: documented signal SNR estimator; NaN when unavailable.
     */
    public static final String SIGNAL_SNR_DB = "signal.snr_db";
    /**
     * STRING: independent carrier state, SEARCHING/LOCKED/UNLOCKED/UNKNOWN.
     */
    public static final String CARRIER_LOCK = "carrier.lock";
    /**
     * DOUBLE, Hz: received carrier minus input centre; NaN unless running and carrier LOCKED.
     */
    public static final String CARRIER_OFFSET_HZ = "carrier.offset_hz";
    /**
     * STRING: independent timing recovery state, SEARCHING/LOCKED/UNLOCKED/UNKNOWN.
     */
    public static final String SYMBOL_LOCK = "symbol.lock";
    /**
     * DOUBLE, baud: documented nominal or measured symbol rate; NaN if unknown.
     */
    public static final String SYMBOL_RATE_BAUD = "symbol.rate_baud";
    /**
     * STRING: frame synchronization/validation state, SEARCHING/LOCKED/UNLOCKED/UNKNOWN.
     */
    public static final String FRAME_LOCK = "frame.lock";
    /**
     * LONG, frames: lifetime accepted valid frames after duplicate suppression.
     */
    public static final String FRAME_GOOD = "frame.good";
    /**
     * LONG, frames: lifetime rejected complete candidates at the documented validation boundary.
     */
    public static final String FRAME_BAD = "frame.bad";
    /**
     * LONG, frames: FRAME_GOOD plus FRAME_BAD in the same snapshot; excludes duplicates and incomplete candidates.
     */
    public static final String FRAME_TOTAL = "frame.total";
    /**
     * DOUBLE, Hz: lifetime accepted frames per processed signal second; zero before signal arrives.
     */
    public static final String FRAME_RATE_HZ = "frame.rate_hz";
}
