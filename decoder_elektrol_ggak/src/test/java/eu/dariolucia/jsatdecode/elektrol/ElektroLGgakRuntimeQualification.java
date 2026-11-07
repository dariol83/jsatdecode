package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
import eu.dariolucia.jsatdecode.core.registry.FactoryRegistry;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.ui.swing.SwingMonitoringUi;
import eu.dariolucia.jsatdecode.util.UtcTime;
import java.awt.GraphicsEnvironment;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit WAV/runtime/ZMQ and desktop observation acceptance using independent GNU Radio data. */
public final class ElektroLGgakRuntimeQualification {
    private ElektroLGgakRuntimeQualification() {}

    /**
     * Replays centered reference IQ as float stereo WAV twice without UI and once with real Swing.
     * ZMQ frame/status distribution is active in every run. No subscriber delivery claim is made.
     *
     * @param args reference directory, input sample rate, optional --headless-only
     * @throws Exception if frames differ, speed is <=2x, UI is unavailable, or runtime fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--headless-only")))
            throw new IllegalArgumentException("Expected reference-directory sample-rate [--headless-only]");
        boolean headless = args.length == 3;
        if (!headless && GraphicsEnvironment.isHeadless())
            throw new IllegalStateException("Desktop unavailable: use --headless-only to qualify only UI OFF");
        Path folder = Path.of(args[0]);
        int rate = Integer.parseInt(args[1]);
        Path input = folder.resolve("input.cf32"), wav = folder.resolve("runtime-input.wav");
        byte[] expected = Files.readAllBytes(folder.resolve("frames.bin"));
        if (expected.length == 0 || expected.length % 220 != 0 || expected.length > 220_000_000)
            throw new IllegalArgumentException("Expected bounded nonempty reference GGAK frames");
        if (!Arrays.equals(expected, Files.readAllBytes(folder.resolve("expected_frames.bin"))))
            throw new AssertionError("GNU Radio accepted frames differ from independently checksum-valid truth");
        long expectedRejected = ElektroLGgakQualification.expectedRejected(folder);
        long iqBytes = Files.size(input);
        if (iqBytes % 8 != 0 || iqBytes > Integer.MAX_VALUE - 44)
            throw new IllegalArgumentException("Qualification requires aligned cf32 smaller than 2GB");
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[] {'R','I','F','F'}).putInt((int) iqBytes + 36)
                .put(new byte[] {'W','A','V','E','f','m','t',' '}).putInt(16)
                .putShort((short) 3).putShort((short) 2).putInt(rate).putInt(Math.multiplyExact(rate, 8))
                .putShort((short) 8).putShort((short) 32).put(new byte[] {'d','a','t','a'}).putInt((int) iqBytes).flip();
        try (FileChannel out = FileChannel.open(wav, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             FileChannel in = FileChannel.open(input, StandardOpenOption.READ)) {
            while (header.hasRemaining()) out.write(header);
            long position = 0;
            while (position < iqBytes) position += in.transferTo(position, iqBytes - position, out);
        }
        var decoderFactory = FactoryRegistry.discover(eu.dariolucia.jsatdecode.api.decoder.DecoderFactory.class,
                f -> f.descriptor().id()).require("elektrol_ggak");
        var sourceFactory = FactoryRegistry.discover(SampleSourceFactory.class, f -> f.descriptor().id()).require("wav");
        var frameFactory = FactoryRegistry.discover(FrameDistributorFactory.class, f -> f.descriptor().id()).require("zmq");
        var monitorFactory = FactoryRegistry.discover(MonitoringDistributorFactory.class, f -> f.descriptor().id()).require("zmq");
        StringBuilder report = new StringBuilder("run,ui,frames,rejected_frames,candidates,checksum_annotations_valid,signal_seconds,wall_seconds,realtime_factor,sha256\n");
        for (int run = 0; run < (headless ? 2 : 3); run++) {
            var source = sourceFactory.create(new Configuration(Map.of(
                    "type", "wav", "file", wav.toAbsolutePath().toString(), "realtime", false,
                    "repeat", false, "sample_rate", rate, "block_samples", 4096)));
            var decoder = decoderFactory.create(new Configuration(Map.of(
                    "type", "elektrol_ggak", "sample_rate_hz", rate, "dcblocker", false)));
            var outputConfig = new Configuration(Map.of("type", "zmq", "decoder", "elektrol_ggak",
                    "receiver_id", "qualification", "endpoint", "tcp://127.0.0.1:*", "linger_ms", 0));
            var frames = frameFactory.create(outputConfig);
            var monitoring = monitorFactory.create(outputConfig);
            var capture = new Capture(expected.length);
            try (var runtime = new JSatDecodeRuntime(source, decoder, List.of(frames, capture), List.of(monitoring));
                 var ui = run == 2 ? SwingMonitoringUi.open(new Configuration(Map.of()), runtime) : (AutoCloseable) () -> {}) {
                long start = System.nanoTime();
                runtime.start();
                runtime.awaitCompletion();
                double wall = (System.nanoTime() - start) * 1e-9;
                double seconds = iqBytes / 8.0 / rate, speed = seconds / wall;
                byte[] actual = capture.bytes();
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(actual));
                var counters = decoder.monitoringSnapshot().values();
                long good = (Long) counters.get(DecoderStatusKeys.FRAME_GOOD);
                long bad = (Long) counters.get(DecoderStatusKeys.FRAME_BAD);
                long total = (Long) counters.get(DecoderStatusKeys.FRAME_TOTAL);
                String line = String.format(Locale.ROOT, "%d,%s,%d,%d,%d,true,%.9f,%.9f,%.6f,%s%n",
                        run, run == 2, actual.length / 220, bad, total, seconds, wall, speed, hash);
                report.append(line);
                System.out.print(line);
                Files.writeString(folder.resolve("runtime_qualification.csv"), report);
                if (!Arrays.equals(expected, actual))
                    throw new AssertionError("Runtime reference mismatch at byte " + Arrays.mismatch(expected, actual));
                if (runtime.failure().isPresent()) throw new AssertionError("Runtime failed", runtime.failure().orElseThrow());
                if (speed <= 2) throw new AssertionError("Must exceed 2x realtime");
                if (frames.monitoringSnapshot().messagesDropped() != 0 || frames.monitoringSnapshot().errors() != 0)
                    throw new AssertionError("ZMQ local distribution errors/drop");
                if (good != actual.length / 220L || bad != expectedRejected || total != good + expectedRejected)
                    throw new AssertionError("Checksum candidate counters differ from independent transmitted truth");
            }
        }
        System.out.println(headless ? "PASS WAV/ZMQ UI OFF; desktop unqualified" : "PASS WAV/ZMQ UI OFF/OFF/ON");
    }

    private static final class Capture implements FrameDistributor {
        private final byte[] data;
        private int position;
        private ComponentState state = ComponentState.CONFIGURED;
        Capture(int maximumBytes) { data = new byte[maximumBytes]; }
        public FrameDistributorDescriptor descriptor() {
            return new FrameDistributorDescriptor("ggak-capture", "GGAK bounded capture", "1", "Test-only frame byte collector");
        }
        public void start() { state = ComponentState.RUNNING; }
        public void distribute(AnnotatedFrame frame) {
            ElektroLGgakQualification.validateAcceptedFrame(frame);
            byte[] payload = frame.data();
            if (payload.length != 220 || payload.length > data.length - position)
                throw new AssertionError("Unexpected frame size or capture bound exceeded");
            System.arraycopy(payload, 0, data, position, payload.length);
            position += payload.length;
        }
        byte[] bytes() { return Arrays.copyOf(data, position); }
        public DistributionSnapshot monitoringSnapshot() {
            return new DistributionSnapshot(state == ComponentState.RUNNING, true,
                    position / 220, position / 220, 0, 0, 0, 0, UtcTime.nowNanos());
        }
        public ComponentState state() { return state; }
        public void stop() { if (state != ComponentState.CLOSED) state = ComponentState.STOPPED; }
        public void close() { state = ComponentState.CLOSED; }
    }
}
