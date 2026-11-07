package eu.dariolucia.jsatdecode.hinode;

import ru.r2cloud.jradio.fec.ccsds.ReedSolomon;
import ru.r2cloud.jradio.fec.ccsds.UncorrectableException;

import java.util.function.Consumer;

/** Bit-aligned ASM search, overlapping candidates, randomizer reset and private RS state. */
final class CcsdsDeframer {
    private final int threshold;
    private final Consumer<byte[]> output;
    private final byte[] ring = new byte[1024], codeword = new byte[1020];
    private final long[] candidates = new long[64];
    private final ReedSolomon rs = new ReedSolomon(8, 0x187, 112, 11, 32);
    private long bits;
    private int sync, head, size;
    volatile long rejected, candidateDrops, valid;

    CcsdsDeframer(int threshold, Consumer<byte[]> output) {
        this.threshold = threshold;
        this.output = output;
    }

    void bit(int bit) {
        int slot = (int) ((bits >>> 3) & 1023), offset = (int) (bits & 7);
        if (offset == 0) ring[slot] = 0;
        ring[slot] |= (byte) (bit << (7 - offset));
        bits++;
        sync = (sync << 1) | bit;
        if (size > 0 && candidates[head] == bits) {
            long start = bits - 8160;
            for (int i = 0; i < 1020; i++) {
                long p = start + i * 8L;
                int n = (int) ((p >>> 3) & 1023), shift = (int) (p & 7);
                codeword[i] =
                        (byte)
                                (((ring[n] & 255) << shift)
                                        | ((ring[(n + 1) & 1023] & 255) >>> (8 - shift)));
            }
            head = (head + 1) & 63;
            size--;
            randomize(codeword);
            try {
                byte[] frame = rs.decodeDualBasis(codeword, 4);
                valid++;
                output.accept(frame);
            } catch (UncorrectableException e) {
                rejected = Math.incrementExact(rejected);
            }
        }
        if (bits >= 32 && Integer.bitCount(sync ^ 0x1acffc1d) <= threshold) {
            if (size == 64) candidateDrops++;
            else {
                candidates[(head + size) & 63] = bits + 8160;
                size++;
            }
        }
    }

    // CCSDS x^8+x^7+x^5+x^3+1, MSB first. Initial sequence FF 48 0E C0 9A.
    static void randomize(byte[] data) {
        int register = 255;
        for (int i = 0; i < data.length; i++) {
            int mask = 0;
            for (int b = 0; b < 8; b++) {
                mask = (mask << 1) | (register & 1);
                int feedback =
                        ((register >>> 7) ^ (register >>> 5) ^ (register >>> 3) ^ register) & 1;
                register = (register >>> 1) | (feedback << 7);
            }
            data[i] ^= (byte) mask;
        }
    }
}
