package eu.dariolucia.jsatdecode.elektrol;

import java.util.Objects;

/**
 * Stateful port of the normative GRC embedded ASM deframer. One serial producer supplies sliced
 * bits. A 32-bit ASM match establishes a candidate; the receiver validates its additive checksum
 * before functional delivery. No CRC, FEC, descrambling, differential decoding or duplicate
 * filtering is performed. Both BPSK polarities produce MSB-first bytes.
 */
final class GgakDeframer {
    static final int ASM = 0x1ACFFC1D;
    static final int CADU_BITS = 1792;
    static final int CADU_BYTES = CADU_BITS / 8;
    static final int PAYLOAD_BYTES = CADU_BYTES - Integer.BYTES;
    private static final int PAYLOAD_BITS = PAYLOAD_BYTES * Byte.SIZE;

    @FunctionalInterface
    interface Output {
        /** Supplies a complete canonical candidate for checksum validation; exceptions propagate. */
        void frame(byte[] payload, int asmErrors, boolean inverted);
    }

    private final int searchErrors, lockedErrors;
    private final Output output;
    private int window, windowBits, payloadBits, asmErrors;
    private boolean inverted, locked;
    private byte[] payload;

    GgakDeframer(int searchErrors, int lockedErrors, Output output) {
        if (searchErrors < 0 || searchErrors > 32 || lockedErrors < 0 || lockedErrors > 32)
            throw new IllegalArgumentException("ASM error thresholds must be in [0,32]");
        this.searchErrors = searchErrors;
        this.lockedErrors = lockedErrors;
        this.output = Objects.requireNonNull(output);
    }

    /** Processes one bit; preserves the GRC's non-overlapping collection and sticky threshold. */
    void bit(int bit) {
        if ((bit & ~1) != 0) throw new IllegalArgumentException("Expected a binary bit");
        if (payload != null) {
            int corrected = bit ^ (inverted ? 1 : 0);
            payload[payloadBits >>> 3] |= (byte) (corrected << (7 - (payloadBits & 7)));
            if (++payloadBits == PAYLOAD_BITS) {
                byte[] complete = payload;
                payload = null;
                output.frame(complete, asmErrors, inverted);
                // GRC _publish returns normally on a checksum mismatch. Any complete candidate
                // therefore selects the sticky hold threshold, including rejected candidates.
                locked = true;
            }
            return;
        }
        window = (window << 1) | bit;
        if (windowBits < Integer.SIZE) windowBits++;
        if (windowBits != Integer.SIZE) return;
        int errors = Integer.bitCount(window ^ ASM);
        int invertedErrors = Integer.SIZE - errors;
        int threshold = locked ? lockedErrors : searchErrors;
        if (Math.min(errors, invertedErrors) <= threshold) {
            inverted = invertedErrors < errors;
            asmErrors = Math.min(errors, invertedErrors);
            payload = new byte[PAYLOAD_BYTES];
            payloadBits = 0;
            window = windowBits = 0;
        }
    }

    /** Reports the lifetime acquisition history, separately from receiver activity monitoring. */
    boolean locked() {
        return locked;
    }
}
