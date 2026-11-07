package eu.dariolucia.jsatdecode.decoder.base;

import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.LOCKED;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.SEARCHING;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.UNKNOWN;
import static eu.dariolucia.jsatdecode.decoder.base.DecoderLockStates.UNLOCKED;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.decoder.DecoderDescriptor;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.taps.TapProvider;
import eu.dariolucia.jsatdecode.decoder.base.config.DecoderConfiguration;
import eu.dariolucia.jsatdecode.decoder.base.dsp.CarrierConfidence;
import eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.CarrierStatus;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderMetricSchema;
import eu.dariolucia.jsatdecode.decoder.base.monitoring.DecoderSnapshots;

import ru.r2cloud.jradio.Context;
import ru.r2cloud.jradio.FloatInput;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained numerical, immutable-schema, lifecycle and publication contract checks. */
public final class DecoderBaseTest {
    private static int checks;

    private DecoderBaseTest() {}

    /**
     * Runs without recordings, hardware, UI or third-party test frameworks.
     *
     * @param args unused
     * @throws Exception if a numerical or concurrency check fails
     */
    public static void main(String[] args) throws Exception {
        dcBlocker();
        carrierConfidence();
        configuration();
        schema();
        snapshots();
        lifecycle();
        carrierPublication();
        System.out.println("Decoder base: " + checks + " checks passed");
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(Class<? extends Throwable> type, Runnable action) {
        try {
            action.run();
        } catch (Throwable error) {
            check(type.isInstance(error), "Unexpected rejection: " + error);
            return;
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static void dcBlocker() throws Exception {
        ComplexDcBlocker bypass = new ComplexDcBlocker(false, 1024);
        for (int bits : new int[] {0, 0x80000000, 0x3fa00000, 0x7f800000, 0xff800000, 0x7fc01234}) {
            float i = Float.intBitsToFloat(bits), q = Float.intBitsToFloat(bits ^ 0x80000000);
            bypass.sample(i, q);
            check(Float.floatToRawIntBits(bypass.i) == bits, "Raw-bit I bypass");
            check(Float.floatToRawIntBits(bypass.q) == (bits ^ 0x80000000), "Raw-bit Q bypass");
        }
        for (int length : new int[] {2, 1024, 16384, 65536}) {
            int count = length * 5 + 37;
            float[] inputI = new float[count], inputQ = new float[count];
            Random random = new Random(741);
            for (int n = 0; n < count; n++) {
                inputI[n] = random.nextFloat() - .25f;
                inputQ[n] = random.nextFloat() - .75f;
            }
            ArrayInput real = new ArrayInput(inputI), imaginary = new ArrayInput(inputQ);
            var realOracle = new ru.r2cloud.jradio.blocks.DcBlocker(real, length, true);
            var imaginaryOracle = new ru.r2cloud.jradio.blocks.DcBlocker(imaginary, length, true);
            ComplexDcBlocker actual = new ComplexDcBlocker(true, length);
            check(
                    actual.length() == length && actual.i == 0 && actual.q == 0,
                    "Initial state/length");
            for (int n = 0; n < count; n++) {
                actual.sample(inputI[n], inputQ[n]);
                check(
                        Float.floatToRawIntBits(actual.i)
                                == Float.floatToRawIntBits(realOracle.readFloat()),
                        "I equals independent pull-filter oracle at L=" + length + ", n=" + n);
                check(
                        Float.floatToRawIntBits(actual.q)
                                == Float.floatToRawIntBits(imaginaryOracle.readFloat()),
                        "Q equals independent pull-filter oracle at L=" + length + ", n=" + n);
            }
            check(
                    real.position == count && imaginary.position == count,
                    "One input read per output; no tail");
        }
    }

    private static void carrierConfidence() {
        CarrierConfidence mission1 = new CarrierConfidence(250000, 80, 1e-20);
        CarrierConfidence mission2 = new CarrierConfidence(250000, 30, 1e-16);
        check(mission1.coherence() == 0 && mission1.frequency() == 0, "Zero carrier measurements at startup");
        for (int n = 0; n < 50000; n++) {
            mission1.sample(1e-9, 0, .002);
            mission2.sample(1e-9, 0, .002);
        }
        check(mission1.coherence() > .99, "Weak coherent carrier above Mission1 power floor");
        check(mission2.coherence() == 0, "Same carrier remains below Mission2 power floor");
        check(Math.abs(mission1.frequency() - .002) < 1e-8, "PLL frequency averaging retains its units");
        for (int n = 0; n < 200000; n++) mission1.sample(0, 0, 0);
        check(mission1.coherence() == 0, "Silence clears confidence below the configured floor");
    }

    private static void configuration() {
        Configuration empty = new Configuration(Map.of());
        check(DecoderConfiguration.bool(empty, "dcblocker", true), "Enabled default");
        check(!DecoderConfiguration.bool(empty, "dcblocker", false), "Bypass default");
        check(
                DecoderConfiguration.bool(
                        new Configuration(Map.of("dcblocker", " TrUe ")), "dcblocker", false),
                "Runtime-shaped strict boolean");
        rejects(
                ConfigurationException.class,
                () ->
                        DecoderConfiguration.bool(
                                new Configuration(Map.of("dcblocker", "yes")), "dcblocker", false));
        check(DecoderConfiguration.integer(empty, "capacity", 3, 1, 4) == 3, "Integer default");
        check(DecoderConfiguration.number(empty, "rate", .25, 0, 1) == .25, "Double default");
        for (int value : new int[] {1, 4}) {
            check(
                    DecoderConfiguration.integer(
                                    new Configuration(Map.of("n", value)), "n", 2, 1, 4)
                            == value,
                    "Inclusive integer bound");
        }
        rejects(
                ConfigurationException.class,
                () ->
                        DecoderConfiguration.integer(
                                new Configuration(Map.of("n", 5)), "n", 2, 1, 4));
        rejects(
                ConfigurationException.class,
                () ->
                        DecoderConfiguration.number(
                                new Configuration(Map.of("n", "NaN")), "n", 0, -1, 1));
        rejects(
                ConfigurationException.class,
                () ->
                        DecoderConfiguration.number(
                                new Configuration(Map.of("n", 2)), "n", 0, -1, 1));
        DecoderConfiguration.knownKeys(
                new Configuration(Map.of("type", "mission")), Set.of("type"), "Unknown: ");
        try {
            DecoderConfiguration.knownKeys(
                    new Configuration(Map.of("typo", 1)), Set.of("type"), "Unknown: ");
            throw new AssertionError("Unknown setting accepted");
        } catch (ConfigurationException expected) {
            check(expected.getMessage().equals("Unknown: typo"), "Existing unknown-key diagnostic");
        }
    }

    private static void schema() {
        check(DecoderMetricSchema.keys().size() == 10, "Exactly ten common metrics");
        for (String key : DecoderMetricSchema.keys()) {
            MonitoringDescriptor descriptor =
                    DecoderMetricSchema.descriptor(key, "Mission meaning");
            check(
                    descriptor.key().equals(key)
                            && descriptor.description().equals("Mission meaning"),
                    "Retains key and mission description");
        }
        for (String key :
                List.of(
                        DecoderStatusKeys.CARRIER_LOCK,
                        DecoderStatusKeys.SYMBOL_LOCK,
                        DecoderStatusKeys.FRAME_LOCK)) {
            var descriptor = DecoderMetricSchema.descriptor(key, "Lock detector");
            check(
                    descriptor.type() == MonitoringType.STRING && descriptor.unit().isEmpty(),
                    "String lock schema");
        }
        for (String key :
                List.of(
                        DecoderStatusKeys.FRAME_GOOD,
                        DecoderStatusKeys.FRAME_BAD,
                        DecoderStatusKeys.FRAME_TOTAL)) {
            var descriptor = DecoderMetricSchema.descriptor(key, "Candidate boundary");
            check(
                    descriptor.type() == MonitoringType.LONG && descriptor.unit().equals("frames"),
                    "Frame schema");
        }
        Map<String, String> units =
                Map.of(
                        DecoderStatusKeys.SIGNAL_SNR_DB,
                        "dB",
                        DecoderStatusKeys.CARRIER_OFFSET_HZ,
                        "Hz",
                        DecoderStatusKeys.SYMBOL_RATE_BAUD,
                        "baud",
                        DecoderStatusKeys.FRAME_RATE_HZ,
                        "Hz");
        units.forEach(
                (key, unit) -> {
                    var descriptor = DecoderMetricSchema.descriptor(key, "Measurement");
                    check(
                            descriptor.type() == MonitoringType.DOUBLE
                                    && descriptor.unit().equals(unit),
                            "Measurement schema");
                });
        rejects(
                IllegalArgumentException.class,
                () -> DecoderMetricSchema.descriptor("unsupported", "Meaning"));
        rejects(UnsupportedOperationException.class, () -> DecoderMetricSchema.keys().clear());
        check(
                List.of(SEARCHING, LOCKED, UNLOCKED, UNKNOWN)
                        .equals(List.of("SEARCHING", "LOCKED", "UNLOCKED", "UNKNOWN")),
                "Existing string vocabulary");
    }

    private static void snapshots() {
        var snapshot =
                DecoderSnapshots.create(
                        123,
                        Double.NaN,
                        SEARCHING,
                        Double.NaN,
                        UNKNOWN,
                        8192,
                        UNLOCKED,
                        7,
                        3,
                        DecoderSnapshots.frameRate(7, 500000, 1000000));
        check(snapshot.timestampNanos() == 123, "Caller UTC retained");
        check(
                snapshot.values().keySet().equals(DecoderMetricSchema.keys()),
                "Mandatory snapshot schema");
        check(snapshot.values().get(DecoderStatusKeys.FRAME_GOOD).equals(7L), "Long good count");
        check(snapshot.values().get(DecoderStatusKeys.FRAME_BAD).equals(3L), "Long bad count");
        check(
                snapshot.values().get(DecoderStatusKeys.FRAME_TOTAL).equals(10L),
                "Captured counter identity");
        check(
                snapshot.values().get(DecoderStatusKeys.FRAME_RATE_HZ).equals(3.5),
                "Signal-time frame rate");
        check(
                Double.isNaN((Double) snapshot.values().get(DecoderStatusKeys.SIGNAL_SNR_DB)),
                "NaN preserved");
        check(DecoderSnapshots.frameRate(0, 500000, 0) == 0, "Rate before signal time");
        rejects(UnsupportedOperationException.class, () -> snapshot.values().clear());
        rejects(
                ArithmeticException.class,
                () ->
                        DecoderSnapshots.create(
                                0, 0, LOCKED, 0, LOCKED, 1, LOCKED, Long.MAX_VALUE, 1, 0));
        var extended =
                DecoderSnapshots.withDiagnostics(
                        0,
                        1,
                        LOCKED,
                        7,
                        LOCKED,
                        1,
                        LOCKED,
                        2,
                        3,
                        4,
                        Map.entry("decoder.mission.extra", 6L));
        check(
                extended.values().size() == 11
                        && extended.values().get("decoder.mission.extra").equals(6L),
                "Additional diagnostics retained");
        rejects(
                IllegalArgumentException.class,
                () ->
                        DecoderSnapshots.withDiagnostics(
                                0,
                                0,
                                LOCKED,
                                0,
                                LOCKED,
                                1,
                                LOCKED,
                                0,
                                0,
                                0,
                                Map.entry(DecoderStatusKeys.FRAME_GOOD, 4L)));
    }

    private static void lifecycle() {
        var descriptors =
                new ArrayList<>(
                        List.of(
                                DecoderMetricSchema.descriptor(
                                        DecoderStatusKeys.SIGNAL_SNR_DB, "Test measurement")));
        Stub decoder = new Stub(descriptors);
        descriptors.clear();
        check(decoder.monitoringDescriptors().size() == 1, "Schema copied at setup");
        check(decoder.state() == ComponentState.CONFIGURED, "Configured initial state");
        rejects(IllegalStateException.class, decoder::start);
        rejects(NullPointerException.class, () -> decoder.setFrameSink(null));
        decoder.setFrameSink(frame -> {});
        decoder.start();
        check(decoder.isRunning(), "One-shot start");
        rejects(IllegalStateException.class, decoder::start);
        try {
            decoder.setFrameSink(null);
            throw new AssertionError("Sink rewired while running");
        } catch (IllegalStateException expected) {
            check(
                    expected.getMessage().equals("Mission sink diagnostic"),
                    "State check precedes null check");
        }
        decoder.stop();
        rejects(IllegalStateException.class, decoder::start);
        decoder.close();
        check(decoder.state() == ComponentState.CLOSED, "Mission stop/close remain in subclass");
    }

    private static void carrierPublication() throws Exception {
        AtomicReference<CarrierStatus> published =
                new AtomicReference<>(new CarrierStatus(UNLOCKED, Double.NaN));
        Thread writer =
                new Thread(
                        () -> {
                            for (int n = 0; n < 100000; n++) {
                                published.set(
                                        (n & 1) == 0
                                                ? new CarrierStatus(LOCKED, 123.25)
                                                : new CarrierStatus(UNLOCKED, Double.NaN));
                            }
                        });
        writer.start();
        do {
            CarrierStatus captured = published.get();
            check(
                    captured.lock().equals(LOCKED)
                            ? captured.offsetHz() == 123.25
                            : Double.isNaN(captured.offsetHz()),
                    "Coherent carrier publication");
        } while (writer.isAlive());
        writer.join();
    }

    private static final class ArrayInput implements FloatInput {
        private final float[] samples;
        private final Context context = new Context();
        int position;

        ArrayInput(float[] samples) {
            this.samples = samples;
            context.setChannels(1);
        }

        @Override
        public float readFloat() throws IOException {
            if (position == samples.length) throw new EOFException();
            return samples[position++];
        }

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public void close() {}
    }

    private static final class Stub extends AbstractDecoder {
        Stub(List<MonitoringDescriptor> descriptors) {
            super(
                    new DecoderDescriptor("test", "Test", "1", "Lifecycle test"),
                    descriptors,
                    "Mission sink diagnostic",
                    "Mission start diagnostic");
        }

        @Override
        public void accept(ComplexSampleBlock block) {}

        @Override
        public void stop() {
            if (state != ComponentState.CLOSED) state = ComponentState.STOPPED;
        }

        @Override
        public void close() {
            stop();
            state = ComponentState.CLOSED;
        }

        @Override
        public TapProvider taps() {
            throw new UnsupportedOperationException();
        }

        @Override
        public MonitoringSnapshot monitoringSnapshot() {
            throw new UnsupportedOperationException();
        }
    }
}
