package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Edge cases exercising actual DSP frame completion rather than injected decoder internals. */
final class ElektroLGgakEdgeTest {
    private static final int RATE = 250_000;
    private static final int SAMPLES_PER_BIT = RATE / 5_000;
    private static final long NANOS_PER_SAMPLE = 1_000_000_000L / RATE;
    private static int checks;

    private ElektroLGgakEdgeTest() {}

    /** Runs deterministic edge cases and returns the independent assertion count. */
    static int run() {
        checks = 0;
        timestampOverflow();
        sampleIndexOverflow();
        checksumAcceptance();
        frameActivityAndStickyThreshold();
        unavailableSnr();
        return checks;
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static ElektroLGgakDecoder decoder() {
        return new ElektroLGgakDecoder(new Configuration(Map.of(
                "type", "elektrol_ggak", "sample_rate_hz", RATE, "dcblocker", false)));
    }

    private static void timestampOverflow() {
        List<AnnotatedFrame> frames = new ArrayList<>();
        try (var decoder = decoder()) {
            decoder.setFrameSink(frames::add);
            decoder.start();
            ArithmeticException original;
            try {
                // A single block places the completed frame well beyond sample zero. Its source
                // UTC is legal, but UTC plus the actual causal frame offset exceeds signed long.
                GgakSyntheticSignal.transmit(RATE, 1_000_000, 1, 0, block -> decoder.accept(
                        new ComplexSampleBlock(block.iq(), block.complexSamples(),
                                block.sampleIndex(), Long.MAX_VALUE - 1_000, RATE)));
                throw new AssertionError("Overflowing completed-frame UTC was accepted");
            } catch (ArithmeticException expected) {
                original = expected;
                checks++;
            }
            check(decoder.state() == ComponentState.FAILED, "Completed-frame UTC overflow fails decoder");
            check(decoder.failure().orElseThrow() == original, "Original UTC overflow retained");
            check(frames.isEmpty(), "UTC overflow does not hand a malformed frame to the sink");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_GOOD) == 0,
                    "UTC overflow does not classify a valid accepted frame");
            check(Double.isNaN((Double) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.SIGNAL_SNR_DB)),
                    "SNR unavailable after failed UTC conversion");
            decoder.close();
            check(decoder.failure().orElseThrow() == original, "Close retains the original UTC failure");
        }
    }

    private static void sampleIndexOverflow() {
        try (var decoder = decoder()) {
            decoder.setFrameSink(frame -> { throw new AssertionError("Unexpected frame from two input samples"); });
            decoder.start();
            decoder.accept(new ComplexSampleBlock(new float[2], 1, Long.MAX_VALUE - 1,
                    GgakSyntheticSignal.EPOCH, RATE));
            check(decoder.state() == ComponentState.RUNNING, "Largest representable next input index accepted");
            decoder.accept(new ComplexSampleBlock(new float[0], 0, Long.MAX_VALUE,
                    GgakSyntheticSignal.EPOCH, RATE));
            check(decoder.state() == ComponentState.RUNNING, "Empty terminal-index block does not overflow");
            try {
                decoder.accept(new ComplexSampleBlock(new float[2], 1, Long.MAX_VALUE,
                        GgakSyntheticSignal.EPOCH, RATE));
                throw new AssertionError("Input sample index overflow was accepted");
            } catch (ArithmeticException expected) {
                checks++;
                check(decoder.failure().orElseThrow() == expected, "Original sample-index overflow retained");
            }
            check(decoder.state() == ComponentState.FAILED, "Input sample-index overflow fails decoder");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_TOTAL) == 0,
                    "Sample-index overflow invents no classified frame");
        }
    }

    private static void frameActivityAndStickyThreshold() {
        List<AnnotatedFrame> frames = new ArrayList<>();
        try (var decoder = decoder()) {
            decoder.setFrameSink(frames::add);
            decoder.start();
            GgakSyntheticSignal.transmit(RATE, 4096, 1, 0, decoder::accept);
            check(frames.size() == 1, "Initial frame acquires the deframer");
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("LOCKED"),
                    "Recent completed frame sets activity lock");
            long nextInput = (long) GgakSyntheticSignal.bits(1).length * SAMPLES_PER_BIT;
            long silenceEnd = nextInput + 3L * RATE;
            while (nextInput < silenceEnd) {
                int count = (int) Math.min(4096, silenceEnd - nextInput);
                decoder.accept(new ComplexSampleBlock(new float[count * 2], count, nextInput,
                        GgakSyntheticSignal.EPOCH + nextInput * NANOS_PER_SAMPLE, RATE));
                nextInput += count;
            }
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("UNLOCKED"),
                    "Three signal seconds without a frame expire activity lock");
            check(frames.size() == 1, "Silence does not manufacture completed frames");
            byte[] rejected = GgakSyntheticSignal.payload(2);
            rejected[217] ^= 1;
            int[] rejectedBits = GgakSyntheticSignal.bits(rejected);
            transmit(rejectedBits, nextInput, decoder::accept);
            nextInput += (long) rejectedBits.length * SAMPLES_PER_BIT;
            check(frames.size() == 1, "Checksum reject after timeout is not delivered");
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("UNLOCKED"),
                    "Rejected frame does not refresh expired activity lock");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_BAD) == 1,
                    "Timed-out checksum rejection is counted");
            int[] bits = GgakSyntheticSignal.bits(1);
            for (int n = 0; n < 4; n++) bits[GgakSyntheticSignal.PREAMBLE_BITS + n] ^= 1;
            transmit(bits, nextInput, decoder::accept);
            check(frames.size() == 2, "Four-error ASM still accepted after activity timeout");
            check(frames.getLast().annotations().get("asm_errors").equals(4),
                    "Post-timeout frame used sticky four-error threshold");
            check(java.util.Arrays.equals(frames.getFirst().data(), frames.getLast().data()),
                    "Identical payload retransmission retained across signal gap");
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("LOCKED"),
                    "Completed post-timeout frame restores activity lock");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_GOOD) == 2
                            && (Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_TOTAL) == 3,
                    "Frame activity uses accepted deliveries while totals include checksum rejection");
        }
    }

    private static void checksumAcceptance() {
        byte[] zero = new byte[220];
        zero[218] = 2;
        zero[219] = 2;
        byte[] maximum = new byte[220];
        Arrays.fill(maximum, 0, 218, (byte) 0xff);
        // 218 * 255 + 514 = 56104 = 0xdb28, independently specified vector.
        maximum[218] = (byte) 0xdb;
        maximum[219] = 0x28;
        byte[] highLowOctet = new byte[220];
        highLowOctet[0] = (byte) 0x80;
        highLowOctet[218] = 2;
        highLowOctet[219] = (byte) 0x82;
        byte[][] candidates = new byte[15][];
        candidates[0] = zero.clone();
        candidates[0][0] = 1;
        candidates[1] = zero;
        candidates[2] = GgakSyntheticSignal.payload(2);
        candidates[2][217] ^= 1;
        candidates[3] = maximum;
        candidates[4] = maximum.clone();
        candidates[4][218] ^= 1;
        candidates[5] = highLowOctet;
        candidates[6] = GgakSyntheticSignal.payload(6);
        candidates[6][219] ^= 1;
        candidates[7] = GgakSyntheticSignal.payload(7);
        candidates[8] = maximum.clone();
        candidates[8][218] = 0x28;
        candidates[8][219] = (byte) 0xdb;
        candidates[9] = GgakSyntheticSignal.payload(9);
        candidates[10] = new byte[220];
        candidates[11] = GgakSyntheticSignal.payload(11);
        candidates[12] = new byte[220];
        Arrays.fill(candidates[12], (byte) 0xff);
        candidates[13] = GgakSyntheticSignal.payload(13);
        candidates[14] = GgakSyntheticSignal.payload(14);
        int[] bits = GgakSyntheticSignal.bits(candidates);
        for (int n = 0; n < 4; n++) bits[GgakSyntheticSignal.PREAMBLE_BITS + 1792 + n] ^= 1;
        for (int frame : new int[] {4, 5, 6, 7, 13}) {
            int begin = GgakSyntheticSignal.PREAMBLE_BITS + 1792 * frame;
            for (int n = begin; n < begin + 1792; n++) bits[n] ^= 1;
        }
        // At the decoder output preserve fourteen complete candidates, then terminate halfway
        // through the fifteenth. The genuine input includes the AFC's delayed signal tail.
        int delayBits = (GgakCarrierAcquisition.DELAY + SAMPLES_PER_BIT - 1) / SAMPLES_PER_BIT;
        bits = Arrays.copyOf(bits, GgakSyntheticSignal.PREAMBLE_BITS + 14 * 1792 + 32 + 900 + delayBits);
        List<AnnotatedFrame> frames = new ArrayList<>();
        try (var decoder = decoder()) {
            decoder.setFrameSink(frames::add);
            decoder.start();
            transmit(bits, 0, decoder::accept);
            check(decoder.state() == ComponentState.RUNNING && decoder.failure().isEmpty(),
                    "Checksum rejection keeps the DSP running without a decoder failure");
            check(frames.size() == 7, "Only seven valid candidates are delivered from a mixed full-DSP stream");
            var values = decoder.monitoringSnapshot().values();
            check((Long) values.get(DecoderStatusKeys.FRAME_GOOD) == 7,
                    "Only successfully delivered checksum-valid frames are good");
            check((Long) values.get(DecoderStatusKeys.FRAME_BAD) == 7,
                    "First/last covered octet, checksum octets, byte order and fill corruption are rejected");
            check((Long) values.get(DecoderStatusKeys.FRAME_TOTAL) == 14,
                    "Incomplete final candidate is excluded from good/bad/total");
            boolean carrierPolarity = Boolean.TRUE.equals(frames.getFirst().annotations().get("polarity_inverted"));
            for (int n = 0; n < frames.size(); n++) {
                AnnotatedFrame received = frames.get(n);
                int candidate = 2 * n + 1;
                check(Arrays.equals(received.data(), candidates[candidate]) && received.data().length == 220,
                        "Accepted frame remains byte-identical including checksum octets " + candidate);
                int expected = candidate == 1 ? 0x0202 : candidate == 3 ? 0xdb28 : candidate == 5 ? 0x0282
                        : Byte.toUnsignedInt(candidates[candidate][218]) * 256
                                + Byte.toUnsignedInt(candidates[candidate][219]);
                check(Boolean.TRUE.equals(received.annotations().get("checksum_valid"))
                                && Integer.valueOf(expected).equals(received.annotations().get("checksum")),
                        "Accepted checksum value is unsigned, big-endian and typed " + candidate);
                check("UNKNOWN".equals(received.annotations().get("quality")),
                        "Passing additive checksum does not claim guaranteed integrity " + candidate);
                check(Boolean.valueOf(carrierPolarity ^ (candidate == 5 || candidate == 7 || candidate == 13))
                                .equals(received.annotations().get("polarity_inverted")),
                        "Checksum is checked after whole-frame polarity normalization " + candidate);
            }
            check(Integer.valueOf(4).equals(frames.getFirst().annotations().get("asm_errors")),
                    "First checksum rejection still activates the GRC sticky four-error ASM threshold");
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("LOCKED"),
                    "Valid frame immediately following a rejection restores frame activity");
        }
    }

    private static void transmit(int[] bits, long firstInput, Consumer<ComplexSampleBlock> sink) {
        long end = firstInput + (long) bits.length * SAMPLES_PER_BIT;
        double subStep = 2 * Math.PI * 5_000 / RATE;
        for (long first = firstInput; first < end; first += 4096) {
            int count = (int) Math.min(4096, end - first);
            float[] iq = new float[count * 2];
            for (int n = 0; n < count; n++) {
                long position = first + n;
                double sign = bits[(int) ((position - firstInput) / SAMPLES_PER_BIT)] == 0 ? -1 : 1;
                double phase = .55 * sign * Math.cos(subStep * position) + .37;
                iq[n * 2] = (float) (.8 * Math.cos(phase));
                iq[n * 2 + 1] = (float) (.8 * Math.sin(phase));
            }
            sink.accept(new ComplexSampleBlock(iq, count, first,
                    GgakSyntheticSignal.EPOCH + first * NANOS_PER_SAMPLE, RATE));
        }
    }

    private static void unavailableSnr() {
        try (var decoder = decoder()) {
            check(Double.isNaN(snr(decoder)), "Configured SNR is unavailable");
            decoder.setFrameSink(frame -> {});
            decoder.start();
            check(Double.isNaN(snr(decoder)), "Starting DSP does not invent SNR");
            decoder.accept(new ComplexSampleBlock(new float[2], 1, 0, GgakSyntheticSignal.EPOCH, RATE));
            check(Double.isNaN(snr(decoder)), "Running DSP keeps unsupported SNR unavailable");
            decoder.stop();
            check(Double.isNaN(snr(decoder)), "Stopped SNR is unavailable");
            decoder.close();
            check(Double.isNaN(snr(decoder)), "Closed SNR is unavailable");
        }
    }

    private static double snr(ElektroLGgakDecoder decoder) {
        return (Double) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.SIGNAL_SNR_DB);
    }
}
