import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Capture a bounded, ordered Elektro-L reference stream for byte comparison. */
public final class ElektroBaselineReplay {
    private static final int FRAME_BYTES = 220;
    private static final int MAX_FRAMES = 10_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected: recording.cs16 new-output-frames.bin");
        Path recording = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        long inputBytes = Files.size(recording);
        var modified = Files.getLastModifiedTime(recording);
        if (inputBytes == 0 || inputBytes % 4 != 0) throw new IllegalArgumentException("Expected complete CS16 IQ samples");
        String inputHash = hashFile(recording);
        Capture capture = new Capture();
        Map<String, Object> sourceMetrics, decoderMetrics;
        double elapsed;
        try (var source = new Cs16SampleSource(new Configuration(Map.of(
                "type", "cs16", "file", recording.toString(), "sample_rate", 1_000_000,
                "block_samples", 4096, "repeat", false, "realtime", false)));
             var decoder = new ElektroLGgakDecoder(new Configuration(Map.of(
                     "type", "elektrol_ggak", "sample_rate_hz", 1_000_000, "dcblocker", false)));
             var runtime = new JSatDecodeRuntime(source, decoder, List.of(capture), List.of())) {
            long started = System.nanoTime();
            runtime.start();
            runtime.awaitCompletion();
            elapsed = (System.nanoTime() - started) * 1e-9;
            if (runtime.failure().isPresent() || source.failure().isPresent() || decoder.failure().isPresent()) {
                throw new IllegalStateException("Runtime, source or decoder failed");
            }
            sourceMetrics = new TreeMap<>(source.monitoringSnapshot().values());
            decoderMetrics = new TreeMap<>(decoder.monitoringSnapshot().values());
            if (!Boolean.TRUE.equals(sourceMetrics.get("source.eof"))
                    || ((Number) sourceMetrics.get("source.samples")).longValue() != inputBytes / 4) {
                throw new IllegalStateException("Recording did not reach EOF exactly once");
            }
            if (capture.frames == 0 || ((Number) decoderMetrics.get(DecoderStatusKeys.FRAME_GOOD)).longValue() != capture.frames) {
                throw new IllegalStateException("Delivered frames and decoder accounting disagree");
            }
        }
        if (Files.size(recording) != inputBytes || !Files.getLastModifiedTime(recording).equals(modified)) {
            throw new IllegalStateException("Recording changed during replay");
        }
        byte[] outputBytes = capture.bytes.toByteArray();
        Files.write(output, outputBytes, StandardOpenOption.CREATE_NEW);
        String outputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outputBytes));
        long samples = inputBytes / 4;
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("input_bytes=" + inputBytes + " input_sha256=" + inputHash);
        System.out.println("samples=" + samples + " frames=" + capture.frames + " output_sha256=" + outputHash);
        System.out.printf(Locale.ROOT, "signal_seconds=%.6f wall_seconds=%.6f realtime_factor=%.3f%n",
                samples / 1_000_000.0, elapsed, samples / 1_000_000.0 / elapsed);
        System.out.println("source_metrics=" + sourceMetrics);
        System.out.println("decoder_metrics=" + decoderMetrics);
    }

    private static String hashFile(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(file)) {
            byte[] buffer = new byte[1 << 20];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static final class Capture implements FrameDistributor {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long frames;
        private volatile ComponentState state = ComponentState.CONFIGURED;

        public FrameDistributorDescriptor descriptor() {
            return new FrameDistributorDescriptor("baseline-capture", "Baseline capture", "1", "Ordered reference frames");
        }
        public void start() { state = ComponentState.RUNNING; }
        public void distribute(AnnotatedFrame frame) {
            byte[] data = frame.data();
            if (data.length != FRAME_BYTES || frames >= MAX_FRAMES) throw new IllegalStateException("Unexpected frame size or frame limit exceeded");
            bytes.writeBytes(data);
            frames++;
        }
        public DistributionSnapshot monitoringSnapshot() {
            return new DistributionSnapshot(state == ComponentState.RUNNING, true, frames, frames, 0, 0, 0, 0, UtcTime.nowNanos());
        }
        public ComponentState state() { return state; }
        public void stop() { if (state != ComponentState.CLOSED) state = ComponentState.STOPPED; }
        public void close() { state = ComponentState.CLOSED; }
    }
}
