package eu.dariolucia.jsatdecode.hinode;

import java.util.function.IntConsumer;

/**
 * Continuous K=7 CCSDS 171/inverted-133 soft decoder, register-exchange depth 56. Confined to the
 * sample worker. Fixed arrays; no allocation on coded-bit updates.
 */
final class StreamingViterbi {
    private float[] metrics = new float[64], next = new float[64];
    private long[] paths = new long[64], nextPaths = new long[64];
    private final int[] sign0 = new int[32], sign1 = new int[32];
    private final IntConsumer output;
    private int steps;

    StreamingViterbi(IntConsumer output) {
        this.output = output;
        for (int i = 0; i < 32; i++) {
            // GNU Radio CCSDS uses [79,-109] with newest bit in the low bit.
            // These are the bit-reversed 171/133 octal generator representations.
            sign0[i] = (Integer.bitCount((i * 2) & 0x4f) & 1) == 0 ? -1 : 1;
            sign1[i] = (Integer.bitCount((i * 2) & 0x6d) & 1) == 0 ? 1 : -1;
        }
    }

    void pair(float a, float b) {
        float best = -Float.MAX_VALUE;
        int bestState = 0;
        // Each butterfly shares complementary branch scores. Normalize every step
        // to prevent loss of float precision during arbitrarily long live streams.
        for (int i = 0; i < 32; i++) {
            float score = sign0[i] * a + sign1[i] * b;
            for (int bit = 0; bit < 2; bit++) {
                float signed = bit == 0 ? score : -score;
                float lo = metrics[i] + signed, hi = metrics[i + 32] - signed;
                int predecessor = lo >= hi ? i : i + 32, state = i * 2 + bit;
                float value = Math.max(lo, hi);
                next[state] = value;
                nextPaths[state] = (paths[predecessor] << 1) | bit;
                if (value > best) {
                    best = value;
                    bestState = state;
                }
            }
        }
        for (int i = 0; i < 64; i++) next[i] -= best;
        float[] m = metrics;
        metrics = next;
        next = m;
        long[] p = paths;
        paths = nextPaths;
        nextPaths = p;
        if (++steps >= 56) {
            steps = 56;
            output.accept((int) ((paths[bestState] >>> 55) & 1));
        }
    }
}
