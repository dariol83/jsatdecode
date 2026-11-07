package eu.dariolucia.jsatdecode.hinode;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker;

import ru.r2cloud.jradio.ccsds.CcittScrambler;
import ru.r2cloud.jradio.fec.Viterbi;
import ru.r2cloud.jradio.fec.ccsds.ReedSolomon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.ServiceLoader;

/** Self-contained protocol, ownership, lifecycle and sample-partition regression. */
public final class HinodeTest {
    private HinodeTest() {}

    /** Runs synthetic checks without requiring recordings or hardware. */
    public static void main(String[] args) {
        dcblocker();
        randomizer();
        fec();
        receiver();
        check(
                HinodeSyntheticSignal.run(2) > 0,
                "End-to-end generated PM acquisition and corrected frames");
        check(HinodeSyntheticSignal.run(6, .017, true) > 0, "Positive Doppler with valid frames");
        check(HinodeSyntheticSignal.run(6, -.017, true) > 0, "Negative Doppler with valid frames");
        System.out.println(
                "Hinode synthetic tests passed (recorded/live qualification still required)");
    }

    private static void dcblocker() {
        ComplexDcBlocker enabled = new ComplexDcBlocker(true, 16384),
                bypass = new ComplexDcBlocker(false, 16384);
        enabled.sample(1, -2);
        check(
                enabled.i == -0x1.0p-56f && enabled.q == 0x1.0p-55f,
                "jradio four-average startup and independent I/Q");
        for (int n = 0; n < 200000; n++) enabled.sample(1, -2);
        check(
                Math.abs(enabled.i) < .001 && Math.abs(enabled.q) < .002,
                "Constant complex DC rejected");
        ComplexDcBlocker impulse = new ComplexDcBlocker(true, 16384);
        double sum = 0;
        float peak = 0;
        int peakAt = -1;
        boolean independent = true;
        for (int n = 0; n < 4 * 16384; n++) {
            impulse.sample(n == 0 ? 1 : 0, 0);
            sum += impulse.i;
            independent &= impulse.q == 0;
            if (impulse.i > peak) {
                peak = impulse.i;
                peakAt = n;
            }
        }
        check(independent, "Independent Q history with I impulse");
        check(peak > .99 && peakAt == 2 * (16384 - 1), "Long-form impulse group delay");
        check(Math.abs(sum) < .0001, "Impulse response rejects DC");
        for (float value : new float[] {0f, -0f, 1.25f, -42f, Float.MIN_VALUE}) {
            bypass.sample(value, -value);
            check(
                    Float.floatToRawIntBits(bypass.i) == Float.floatToRawIntBits(value)
                            && Float.floatToRawIntBits(bypass.q) == Float.floatToRawIntBits(-value),
                    "Bit-exact bypass");
        }
        for (Object value : new Object[] {true, false, "true", "false"}) {
            try (var decoder = new HinodeDecoder(new Configuration(Map.of("dcblocker", value)))) {
                check(decoder.state() == ComponentState.CONFIGURED, "DC configuration accepted");
            }
        }
        try {
            new HinodeDecoder(new Configuration(Map.of("dcblocker", "invalid")));
            throw new AssertionError("Malformed DC switch accepted");
        } catch (ConfigurationException expected) {
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void statusContract(Decoder decoder) {
        Map<String, eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor> descriptors =
                new HashMap<>();
        for (var descriptor : decoder.monitoringDescriptors())
            descriptors.put(descriptor.key(), descriptor);
        var values = decoder.monitoringSnapshot().values();
        for (var field : DecoderStatusKeys.class.getFields()) {
            try {
                String key = (String) field.get(null);
                check(
                        descriptors.containsKey(key) && values.containsKey(key),
                        "Mandatory status and descriptor: " + key);
                Class<?> expected =
                        switch (descriptors.get(key).type()) {
                            case DOUBLE -> Double.class;
                            case LONG -> Long.class;
                            case STRING -> String.class;
                            default -> throw new AssertionError("Invalid mandatory type");
                        };
                check(expected.isInstance(values.get(key)), "Mandatory metric type: " + key);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        long good = (Long) values.get(DecoderStatusKeys.FRAME_GOOD),
                bad = (Long) values.get(DecoderStatusKeys.FRAME_BAD);
        check(
                good >= 0
                        && bad >= 0
                        && (Long) values.get(DecoderStatusKeys.FRAME_TOTAL) == good + bad,
                "Frame accounting identity");
        check(
                values.get(DecoderStatusKeys.FRAME_GOOD)
                        .equals(values.get("decoder.hinode_s_tlm.frames")),
                "Legacy accepted count remains an alias");
        check(
                values.get(DecoderStatusKeys.FRAME_BAD)
                        .equals(values.get("decoder.hinode_s_tlm.rs_rejected")),
                "Rejected candidate boundary documented");
    }

    static void randomizer() {
        byte[] actual = new byte[1020], expected = new byte[1020];
        CcsdsDeframer.randomize(actual);
        CcittScrambler.shuffle(expected);
        check(
                Arrays.equals(actual, expected),
                "CCSDS randomizer agrees with independent jradio implementation");
        check(
                HexFormat.of().formatHex(Arrays.copyOf(actual, 5)).equals("ff480ec09a"),
                "Known PN prefix");
    }

    // Generate a conventional-basis RS codeword with jradio's encoder, then use
    // the CCSDS linear basis transform columns rather than the decoder's tables.
    static byte[] cadu(int seed, int corruptions) {
        ReedSolomon encoder = new ReedSolomon(8, 0x187, 112, 11, 32);
        byte[] payload = new byte[1020];
        Random random = new Random(seed);
        int[] columns = {0x7b, 0xaf, 0x99, 0xfa, 0x86, 0xec, 0xef, 0x8d};
        for (int interleave = 0; interleave < 4; interleave++) {
            byte[] data = new byte[223];
            random.nextBytes(data);
            byte[] encoded = encoder.encodeData(data);
            for (int j = 0; j < 255; j++) {
                int dual = 0;
                for (int b = 0; b < 8; b++) if (((encoded[j] >>> b) & 1) != 0) dual ^= columns[b];
                payload[j * 4 + interleave] = (byte) dual;
            }
            for (int j = 0; j < corruptions; j++) payload[j * 4 + interleave] ^= (byte) (j + 1);
        }
        CcittScrambler.shuffle(payload);
        byte[] cadu = new byte[1024];
        cadu[0] = 0x1a;
        cadu[1] = (byte) 0xcf;
        cadu[2] = (byte) 0xfc;
        cadu[3] = 0x1d;
        System.arraycopy(payload, 0, cadu, 4, 1020);
        return cadu;
    }

    static byte[] decode(byte[] cadu, int bitOffset) {
        List<byte[]> output = new ArrayList<>();
        CcsdsDeframer framer = new CcsdsDeframer(4, output::add);
        for (int i = 0; i < bitOffset; i++) framer.bit(i & 1);
        for (byte value : cadu) for (int b = 7; b >= 0; b--) framer.bit((value >>> b) & 1);
        check(output.size() == 1, "One RS-valid frame at arbitrary bit alignment " + bitOffset);
        return output.get(0);
    }

    static void fec() {
        byte[] clean = cadu(42, 0), expected = decode(clean, 0);
        for (int offset = 1; offset < 8; offset++)
            check(Arrays.equals(expected, decode(clean, offset)), "Bit alignment");
        check(
                Arrays.equals(expected, decode(cadu(42, 16), 3)),
                "Correct 16 symbol errors per interleave");
        List<byte[]> failed = new ArrayList<>();
        CcsdsDeframer bad = new CcsdsDeframer(4, failed::add);
        for (byte value : cadu(42, 40)) for (int b = 7; b >= 0; b--) bad.bit((value >>> b) & 1);
        check(failed.isEmpty() && bad.rejected > 0, "Reject uncorrectable RS codeword");
        // Independent packed convolutional encoder; test continuous Viterbi output
        // through real ASM/PN/dual-basis RS rather than mirroring butterfly logic.
        byte[] stream = new byte[1200];
        System.arraycopy(clean, 0, stream, 64, clean.length);
        byte[] coded = Viterbi.encode(stream, (byte) 0x4f, (byte) 0x6d, true);
        List<byte[]> frames = new ArrayList<>();
        CcsdsDeframer deframer = new CcsdsDeframer(4, frames::add);
        StreamingViterbi decoder = new StreamingViterbi(deframer::bit);
        for (byte value : coded)
            for (int b = 6; b >= 0; b -= 2)
                decoder.pair(
                        ((value >>> (b + 1)) & 1) == 0 ? -1 : 1, ((value >>> b) & 1) == 0 ? -1 : 1);
        check(
                frames.size() == 1 && Arrays.equals(expected, frames.get(0)),
                "Independent convolutional encoder to corrected frame");
    }

    static void receiver() {
        check(
                ServiceLoader.load(DecoderFactory.class).stream()
                        .anyMatch(p -> p.get().descriptor().id().equals("hinode_s_tlm")),
                "Factory discovery");
        // Runtime passes the whole unprefixed decoder section, including its
        // routing selector. Exercise the factory with precisely that shape.
        Configuration global =
                new Configuration(
                        Map.of(
                                "decoder.type",
                                "hinode_s_tlm",
                                "decoder.sample_rate_hz",
                                500000,
                                "decoder.asm_errors",
                                4,
                                "decoder.tap_capacity",
                                16384,
                                "source.type",
                                "wav"));
        try (Decoder configured = new HinodeDecoderFactory().create(global.getSection("decoder"))) {
            statusContract(configured);
            check(
                    configured.state() == ComponentState.CONFIGURED,
                    "Runtime-shaped decoder section accepted");
            check(
                    configured.monitoringDescriptors().stream()
                            .anyMatch(
                                    d ->
                                            d.key().equals(DecoderStatusKeys.CARRIER_OFFSET_HZ)
                                                    && d.unit().equals("Hz")),
                    "Carrier offset descriptor is available in Hz");
        }
        for (Map<String, ?> invalid : List.of(Map.of("type", "dummy"), Map.of("typo", 1))) {
            try {
                new HinodeDecoderFactory().create(new Configuration(invalid));
                throw new AssertionError("Invalid decoder setting accepted: " + invalid);
            } catch (ConfigurationException expected) {
            }
        }
        try {
            new HinodeDecoder(new Configuration(Map.of("sample_rate_hz", 48000)));
            throw new AssertionError("Bad rate accepted");
        } catch (ConfigurationException expected) {
        }
        float[] iq = new float[400000];
        Random noise = new Random(987);
        for (int i = 0; i < iq.length; i++) iq[i] = (float) (.05 * noise.nextGaussian());
        HinodeDecoder a = run(iq, 4096), b = run(iq, 137);
        var aa = a.taps().symbols().orElseThrow().snapshot(4096);
        var bb = b.taps().symbols().orElseThrow().snapshot(4096);
        check(
                aa.complexSamples() == bb.complexSamples(),
                "Symbol count independent of block boundaries");
        for (int i = 0; i < aa.complexSamples(); i++) {
            check(Float.floatToIntBits(aa.i(i)) == Float.floatToIntBits(bb.i(i)), "Exact symbol I");
            check(Float.floatToIntBits(aa.q(i)) == Float.floatToIntBits(bb.q(i)), "Exact symbol Q");
        }
        a.close();
        b.close();
        check(a.state() == ComponentState.CLOSED, "Close state");
        try (HinodeDecoder bypassA = run(iq, 4096, false);
                HinodeDecoder bypassB = run(iq, 137, false)) {
            var x = bypassA.taps().symbols().orElseThrow().snapshot(4096);
            var y = bypassB.taps().symbols().orElseThrow().snapshot(4096);
            check(x.complexSamples() == y.complexSamples(), "Bypass partition invariant count");
            boolean different = false;
            for (int i = 0; i < x.complexSamples(); i++) {
                check(
                        Float.floatToRawIntBits(x.i(i)) == Float.floatToRawIntBits(y.i(i))
                                && Float.floatToRawIntBits(x.q(i))
                                        == Float.floatToRawIntBits(y.q(i)),
                        "Bypass partition invariant IQ");
                different |= Float.floatToRawIntBits(x.i(i)) != Float.floatToRawIntBits(aa.i(i));
            }
            check(different, "DC switch changes actual receiver processing");
        }
        statusContract(a);
        a.close();
        HinodeDecoder failure = new HinodeDecoder(new Configuration(Map.of()));
        failure.setFrameSink(frame -> {});
        failure.start();
        try {
            failure.accept(new ComplexSampleBlock(new float[] {Float.NaN, 0}, 1, 0, 0, 500000));
            throw new AssertionError("NaN accepted");
        } catch (IllegalArgumentException expected) {
            check(failure.state() == ComponentState.FAILED, "Failure state");
            statusContract(failure);
            check(
                    Double.isNaN(
                            (Double)
                                    failure.monitoringSnapshot()
                                            .values()
                                            .get(DecoderStatusKeys.CARRIER_OFFSET_HZ)),
                    "Offset unavailable on failure");
        }
        failure.close();
    }

    static HinodeDecoder run(float[] iq, int blockSize) {
        return run(iq, blockSize, true);
    }

    static HinodeDecoder run(float[] iq, int blockSize, boolean dcblocker) {
        HinodeDecoder decoder =
                new HinodeDecoder(new Configuration(Map.of("dcblocker", dcblocker)));
        decoder.setFrameSink(
                frame -> {
                    throw new AssertionError("Noise produced a valid frame");
                });
        decoder.start();
        for (int n = 0; n < iq.length / 2; n += blockSize) {
            int count = Math.min(blockSize, iq.length / 2 - n);
            decoder.accept(
                    new ComplexSampleBlock(
                            Arrays.copyOfRange(iq, n * 2, (n + count) * 2),
                            count,
                            n,
                            n * 2000L,
                            500000));
        }
        decoder.stop();
        return decoder;
    }
}
