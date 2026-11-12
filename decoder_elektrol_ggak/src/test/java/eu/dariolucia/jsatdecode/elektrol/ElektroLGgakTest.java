package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.core.config.ConfigurationLoader;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained framing, synthetic IQ, lifecycle and framework contract qualification. */
public final class ElektroLGgakTest {
    private static int checks;
    private ElektroLGgakTest() {}

    /** Runs deterministic tests; no recording, GNU Radio, network peer or SDR is required. */
    public static void main(String[] args) throws Exception {
        configuration();
        dcblocker();
        framing();
        checks += ElektroLGgakAcquisitionTest.run();
        synthetic();
        failures();
        checks += ElektroLGgakEdgeTest.run();
        examples();
        System.out.println("Elektro-L GGAK: " + checks + " checks passed");
    }

    private static void check(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }

    private static Configuration config(Object... overrides) {
        Map<String, Object> map = new HashMap<>();
        map.put("type", "elektrol_ggak");
        for (int n = 0; n < overrides.length; n += 2) map.put((String) overrides[n], overrides[n + 1]);
        return new Configuration(map);
    }

    private static void configuration() {
        check(ServiceLoader.load(DecoderFactory.class).stream().map(ServiceLoader.Provider::get)
                .anyMatch(f -> f.descriptor().id().equals("elektrol_ggak")), "ServiceLoader factory");
        try (var decoder = new ElektroLGgakDecoder(new Configuration(Map.of()))) {
            check(decoder.state() == ComponentState.CONFIGURED, "Direct construction without type");
            schema(decoder);
        }
        for (var values : List.of(config("type", "wrong"), config("unknown", 1),
                config("dcblocker", "yes"), config("sample_rate_hz", "NaN"),
                config("sample_rate_hz", 1000001), config("sample_rate_hz", 0),
                config("sample_rate_hz", 249999), config("tap_capacity", 0),
                config("sync_search_errors", -1), config("source.sample_rate", 1000000))) {
            try {
                new ElektroLGgakDecoder(values);
                throw new AssertionError("Invalid configuration accepted: " + values.asMap());
            } catch (ConfigurationException expected) { checks++; }
        }
        for (int rate : new int[] {250000, 1000000, 2000000})
            try (var decoder = new ElektroLGgakDecoder(config("sample_rate_hz", rate))) {
                check(decoder.state() == ComponentState.CONFIGURED, "Supported rate " + rate);
            }
    }

    private static void dcblocker() {
        var enabled = new ComplexDcBlocker(true, 16384);
        var bypass = new ComplexDcBlocker(false, 16384);
        for (float value : new float[] {0f, -0f, 1.25f, -42f, Float.MIN_VALUE}) {
            bypass.sample(value, -value);
            check(Float.floatToRawIntBits(bypass.i) == Float.floatToRawIntBits(value)
                    && Float.floatToRawIntBits(bypass.q) == Float.floatToRawIntBits(-value), "Exact bypass");
        }
        for (int n = 0; n < 200000; n++) enabled.sample(1, -2);
        check(Math.abs(enabled.i) < 1e-3 && Math.abs(enabled.q) < 2e-3, "Independent I/Q DC rejection");
    }

    private static void frame(GgakDeframer deframer, byte[] data, int inversion, int asmMask, int bits) {
        int marker = GgakDeframer.ASM ^ asmMask;
        for (int n = 31; n >= 0; n--) deframer.bit(((marker >>> n) & 1) ^ inversion);
        for (int n = 0; n < bits; n++) deframer.bit(((data[n / 8] >>> (7 - n % 8)) & 1) ^ inversion);
    }

    private static void framing() {
        List<byte[]> frames = new ArrayList<>();
        List<Integer> errors = new ArrayList<>();
        List<Boolean> polarities = new ArrayList<>();
        var deframer = new GgakDeframer(2, 4, (p, e, i) -> { frames.add(p); errors.add(e); polarities.add(i); });
        byte[] payload = GgakSyntheticSignal.payload(7);
        for (int n = 0; n < 11; n++) deframer.bit(n & 1);
        frame(deframer, payload, 0, 3, 1760);
        check(frames.size() == 1 && Arrays.equals(frames.getFirst(), payload), "Bit alignment/MSB packing");
        check(errors.getFirst() == 2 && !polarities.getFirst(), "Search ASM mismatch annotation");
        // The lifetime GRC locked threshold remains active even after a long unmatched gap.
        for (int n = 0; n < 25000; n++) deframer.bit(0);
        frame(deframer, payload, 1, 15, 1760);
        check(frames.size() == 2 && Arrays.equals(frames.getLast(), payload), "Full inverted payload normalized");
        check(errors.getLast() == 4 && polarities.getLast(), "Sticky locked threshold after gap");
        frame(deframer, payload, 0, 0, 1760);
        check(frames.size() == 3 && Arrays.equals(frames.getLast(), payload), "Repeated transmitted payload retained");
        frame(deframer, payload, 0, 0, 1759);
        check(frames.size() == 3, "Incomplete last candidate not emitted");
        List<byte[]> search = new ArrayList<>();
        var strict = new GgakDeframer(2, 4, (p, e, i) -> search.add(p));
        frame(strict, new byte[220], 0, 7, 1760);
        check(search.isEmpty(), "Three-error marker rejected before first frame");
        frame(strict, payload, 1, 0, 1760);
        check(search.size() == 1 && Arrays.equals(search.getFirst(), payload), "Search recovery following rejected marker");
    }

    private static void schema(ElektroLGgakDecoder decoder) {
        var values = decoder.monitoringSnapshot().values();
        Map<String, Class<?>> expected = Map.of(
                DecoderStatusKeys.SIGNAL_SNR_DB, Double.class,
                DecoderStatusKeys.CARRIER_LOCK, String.class,
                DecoderStatusKeys.CARRIER_OFFSET_HZ, Double.class,
                DecoderStatusKeys.SYMBOL_LOCK, String.class,
                DecoderStatusKeys.SYMBOL_RATE_BAUD, Double.class,
                DecoderStatusKeys.FRAME_LOCK, String.class,
                DecoderStatusKeys.FRAME_GOOD, Long.class,
                DecoderStatusKeys.FRAME_BAD, Long.class,
                DecoderStatusKeys.FRAME_TOTAL, Long.class,
                DecoderStatusKeys.FRAME_RATE_HZ, Double.class);
        check(values.keySet().containsAll(expected.keySet()), "Mandatory snapshot keys");
        check(decoder.monitoringDescriptors().stream().map(d -> d.key()).toList().containsAll(expected.keySet()), "Mandatory descriptors");
        check(expected.entrySet().stream().allMatch(e -> e.getValue().isInstance(values.get(e.getKey()))), "Mandatory metric types");
        check((Long) values.get(DecoderStatusKeys.FRAME_TOTAL)
                == (Long) values.get(DecoderStatusKeys.FRAME_GOOD) + (Long) values.get(DecoderStatusKeys.FRAME_BAD), "Counter identity");
        check(Double.isNaN((Double) values.get(DecoderStatusKeys.CARRIER_OFFSET_HZ)), "Unavailable offset is NaN");
        check(values.get(DecoderStatusKeys.CARRIER_LOCK).equals("UNKNOWN")
                && values.get(DecoderStatusKeys.SYMBOL_LOCK).equals("UNKNOWN"), "Unsupported detectors stay UNKNOWN");
        check((Double) values.get(DecoderStatusKeys.SYMBOL_RATE_BAUD) == 5000, "Nominal symbol rate");
    }

    private static List<AnnotatedFrame> decode(int rate, int block, boolean dc, boolean observe) throws Exception {
        List<AnnotatedFrame> frames = new ArrayList<>();
        try (var decoder = new ElektroLGgakDecoder(config("sample_rate_hz", rate, "dcblocker", dc))) {
            schema(decoder);
            decoder.setFrameSink(frames::add);
            decoder.start();
            AtomicBoolean running = new AtomicBoolean(observe);
            AtomicReference<Throwable> observerFailure = new AtomicReference<>();
            Thread observer = observe ? Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (running.get()) {
                        decoder.monitoringSnapshot();
                        decoder.taps().rf().ifPresent(t -> t.snapshot(1024));
                        decoder.taps().baseband().ifPresent(t -> t.snapshot(1024));
                        decoder.taps().symbols().ifPresent(t -> t.snapshot(1024));
                        Thread.yield();
                    }
                } catch (Throwable failure) { observerFailure.set(failure); }
            }) : null;
            try {
                GgakSyntheticSignal.transmit(rate, block, 5, 0, decoder::accept);
                schema(decoder);
                var values = decoder.monitoringSnapshot().values();
                check((Long) values.get(DecoderStatusKeys.FRAME_GOOD) == frames.size(), "Sink success counted");
                check((Long) values.get(DecoderStatusKeys.FRAME_BAD) == 0, "All transmitted checksums validate");
                check(decoder.taps().rf().orElseThrow().snapshot(8).sampleRate() == rate, "RF tap rate");
                check(decoder.taps().baseband().orElseThrow().snapshot(8).sampleRate() == 50000, "Baseband tap rate");
                check(decoder.taps().symbols().orElseThrow().snapshot(8).sampleRate() == 5000, "Symbol tap rate");
            } finally {
                running.set(false);
                if (observer != null) observer.join(5000);
            }
            check(observerFailure.get() == null, "Concurrent observer isolated");
            decoder.stop();
            schema(decoder);
            check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_LOCK).equals("UNLOCKED"), "Stopped frame lock");
            decoder.stop();
            decoder.close();
            decoder.close();
            schema(decoder);
        }
        return frames;
    }

    private static void synthetic() throws Exception {
        var first = decode(250000, 4096, false, false);
        var partitioned = decode(250000, 137, false, true);
        check(first.size() == 5, "Five independently transmitted frames received");
        check(first.equals(partitioned), "Exact frame bytes/order/UTC/metadata across partitions and observers");
        for (int n = 0; n < first.size(); n++) {
            var f = first.get(n);
            check(Arrays.equals(f.data(), GgakSyntheticSignal.payload(n)), "Synthetic truth frame " + n);
            check(f.timestampNanos() >= GgakSyntheticSignal.EPOCH, "Source UTC preserved");
            check(f.annotations().get("frame_type").equals("GGAK") && f.annotations().get("quality").equals("UNKNOWN"),
                    "Checksum acceptance keeps empirical frame quality UNKNOWN");
            int received = Byte.toUnsignedInt(f.data()[218]) * 256 + Byte.toUnsignedInt(f.data()[219]);
            check(Boolean.TRUE.equals(f.annotations().get("checksum_valid"))
                    && Integer.valueOf(received).equals(f.annotations().get("checksum")),
                    "Validated checksum metadata preserves transmitted field");
        }
        // Enabling DC removal changes the residual carrier, but never block-partition semantics.
        var dcFirst = decode(250000, 4096, true, false);
        var dcPartitioned = decode(250000, 137, true, false);
        check(dcFirst.equals(dcPartitioned), "Enabled DC whole-chain partition invariance");
    }

    private static void failures() {
        try (var decoder = new ElektroLGgakDecoder(config())) {
            try { decoder.start(); throw new AssertionError("Started without sink"); }
            catch (IllegalStateException expected) { checks++; }
            decoder.setFrameSink(f -> {});
            decoder.start();
            try { decoder.setFrameSink(f -> {}); throw new AssertionError("Rewired running sink"); }
            catch (IllegalStateException expected) { checks++; }
            try { decoder.accept(new ComplexSampleBlock(new float[2], 1, 0, GgakSyntheticSignal.EPOCH, 500000));
                throw new AssertionError("Mismatched rate accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
            check(decoder.state() == ComponentState.FAILED && decoder.failure().isPresent(), "Rate mismatch failure retained");
            schema(decoder);
        }
        RuntimeException sinkFailure = new IllegalStateException("intentional sink rejection");
        try (var decoder = new ElektroLGgakDecoder(config("sample_rate_hz", 250000))) {
            decoder.setFrameSink(f -> { throw sinkFailure; });
            decoder.start();
            try { GgakSyntheticSignal.transmit(250000, 4096, 1, 0, decoder::accept);
                throw new AssertionError("Sink failure swallowed"); }
            catch (RuntimeException actual) { check(actual == sinkFailure, "Original sink exception propagated"); }
            check(decoder.state() == ComponentState.FAILED && decoder.failure().orElseThrow() == sinkFailure, "Sink failure state");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_GOOD) == 0, "Failed delivery excluded");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_BAD) == 0,
                    "Sink exception is not a checksum rejection");
            check((Long) decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_TOTAL) == 0,
                    "Failed delivery is not a classified frame");
        }
        for (boolean discontinuity : new boolean[] {false, true}) {
            try (var decoder = new ElektroLGgakDecoder(config())) {
                decoder.setFrameSink(f -> {});
                decoder.start();
                decoder.accept(new ComplexSampleBlock(new float[2], 1, 0, GgakSyntheticSignal.EPOCH, 1000000));
                try {
                    decoder.accept(new ComplexSampleBlock(new float[] {discontinuity ? 0 : Float.NaN, 0},
                            1, discontinuity ? 2 : 1, GgakSyntheticSignal.EPOCH + 1000, 1000000));
                    throw new AssertionError("Malformed stream accepted");
                } catch (IllegalArgumentException expected) { checks++; }
                check(decoder.state() == ComponentState.FAILED, "Malformed stream fails decoder");
            }
        }
    }

    private static void examples() throws Exception {
        Path module = Path.of("decoder_elektrol_ggak");
        if (!Files.isDirectory(module)) module = Path.of(".");
        for (String name : new String[] {"elektrol-ggak-wav.properties", "elektrol-ggak-pluto.properties",
                "elektrol-ggak-cs16.properties"}) {
            var c = ConfigurationLoader.load(Map.of(), module.resolve("examples").resolve(name), Map.of(), Map.of());
            try (var decoder = new ElektroLGgakDecoder(c.getSection("decoder"))) {
                check(decoder.state() == ComponentState.CONFIGURED, "Runtime-shaped example " + name);
            }
            if (name.equals("elektrol-ggak-cs16.properties")) {
                check(c.getString("source.type").equals("cs16"), "Recording example selects CS16");
                check(c.getDouble("source.output_frequency_offset") == 0, "Centered recording needs no source translation");
                check(!c.getBoolean("decoder.dcblocker"), "Recording example preserves the GRC residual carrier");
                check(!c.getBoolean("source.realtime"), "Recording example processes without realtime pacing");
            } else check(c.getDouble("source.output_frequency_offset") == 250000, "Positive source translation");
        }
        // Exercise the WAV example through actual ServiceLoader/runtime/distribution and EOF.
        Files.createDirectories(module.resolve("target"));
        Path wav = module.resolve("target/test-empty.wav").toAbsolutePath();
        ByteBuffer bytes = ByteBuffer.allocate(44 + 8192).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(new byte[] {'R','I','F','F'}).putInt(bytes.capacity() - 8).put(new byte[] {'W','A','V','E','f','m','t',' '})
                .putInt(16).putShort((short) 3).putShort((short) 2).putInt(1000000).putInt(8000000)
                .putShort((short) 8).putShort((short) 32).put(new byte[] {'d','a','t','a'}).putInt(8192);
        Files.write(wav, bytes.array());
        var c = ConfigurationLoader.load(Map.of(), module.resolve("examples/elektrol-ggak-wav.properties"),
                Map.of(), Map.of("source.file", wav.toString()));
        try (var runtime = JSatDecodeRuntime.create(c)) {
            runtime.start();
            runtime.awaitCompletion();
            check(runtime.failure().isEmpty(), "Actual WAV/rotation/synthetic distribution EOF");
        }
    }
}
