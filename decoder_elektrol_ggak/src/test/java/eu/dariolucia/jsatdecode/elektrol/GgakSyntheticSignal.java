package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import java.util.Random;
import java.util.function.Consumer;

/** Independent deterministic test transmitter; this is not a spacecraft waveform specification. */
final class GgakSyntheticSignal {
    static final long EPOCH = 1_790_000_000_000_000_000L;
    static final int PREAMBLE_BITS = 10000;
    private GgakSyntheticSignal() {}

    static byte[] payload(int number) {
        byte[] result = new byte[220];
        new Random(0x4747414bL + number).nextBytes(result);
        result[0] = (byte) (number >>> 8);
        result[1] = (byte) number;
        checksum(result);
        return result;
    }

    /** Independent transmitter implementation of the empirical additive checksum. */
    static void checksum(byte[] frame) {
        int sum = 514;
        for (int n = 0; n < 218; n++) sum += Byte.toUnsignedInt(frame[n]);
        frame[218] = (byte) (sum / 256);
        frame[219] = (byte) sum;
    }

    static int[] bits(int frames) {
        byte[][] payloads = new byte[frames][];
        for (int f = 0; f < frames; f++) payloads[f] = payload(f);
        return bits(payloads);
    }

    static int[] bits(byte[]... payloads) {
        // Real transmitted tail carries the last frame through the v1.5 AFC delay.
        int[] result = new int[PREAMBLE_BITS + 1792 * payloads.length + 4096];
        Random random = new Random(0x5000);
        for (int n = 0; n < result.length; n++) result[n] = random.nextBoolean() ? 1 : 0;
        int p = PREAMBLE_BITS;
        for (byte[] payload : payloads) {
            for (int n = 31; n >= 0; n--) result[p++] = (0x1acffc1d >>> n) & 1;
            for (byte value : payload)
                for (int n = 7; n >= 0; n--) result[p++] = (value >>> n) & 1;
        }
        return result;
    }

    static void transmit(int rate, int block, int frames, double offset, Consumer<ComplexSampleBlock> sink) {
        int[] bits = bits(frames);
        int samplesPerBit = rate / 5000;
        long length = (long) bits.length * samplesPerBit;
        double subStep = 2 * Math.PI * 5000 / rate;
        double carrierStep = 2 * Math.PI * offset / rate;
        for (long first = 0; first < length; first += block) {
            int count = (int) Math.min(block, length - first);
            float[] iq = new float[count * 2];
            for (int n = 0; n < count; n++) {
                long position = first + n;
                double sign = bits[(int) (position / samplesPerBit)] == 0 ? -1 : 1;
                double phase = .55 * sign * Math.cos(subStep * position) + carrierStep * position + .37;
                iq[2 * n] = (float) (.8 * Math.cos(phase));
                iq[2 * n + 1] = (float) (.8 * Math.sin(phase));
            }
            sink.accept(new ComplexSampleBlock(iq, count, first,
                    EPOCH + Math.round(first * 1e9 / rate), rate));
        }
    }
}
