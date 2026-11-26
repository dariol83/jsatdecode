import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.wav.WavSampleSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Capture a bounded, ordered Hinode reference stream without modifying the recording. */
public final class HinodeBaselineReplay {
    private static final int FRAME_BYTES = 892;
    private static final int MAX_FRAMES = 10_000;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Expected: recording.wav new-output-frames.bin");
        }
        Path recording = Path.of(args[0]).toAbsolutePath().normalize();
        Path output = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists: " + output);
        long inputBytes = Files.size(recording);
        var inputModified = Files.getLastModifiedTime(recording);
        String inputHash;
        try (var input = Files.newInputStream(recording)) {
            var digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 20];
            int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            inputHash = HexFormat.of().formatHex(digest.digest());
        }

        var frames = new ByteArrayOutputStream();
        Map<String, Object> decoderMetrics;
        Map<String, Object> sourceMetrics;
        double elapsed;
        try (var decoder = new HinodeDecoder(new Configuration(Map.of()));
             var source = new WavSampleSource(new Configuration(Map.of(
                     "file", recording.toString(), "sample_rate", 500_000,
                     "block_samples", 4096, "repeat", false, "realtime", false)))) {
            decoder.setFrameSink(frame -> {
                byte[] data = frame.data();
                if (data.length != FRAME_BYTES || frames.size() >= FRAME_BYTES * MAX_FRAMES) {
                    throw new IllegalStateException("Unexpected frame size or frame limit exceeded");
                }
                frames.writeBytes(data);
            });
            if (Boolean.getBoolean("jsatdecode.replay.leased")) source.setLeasedSampleSink(decoder::acceptLeased);
            else source.setSampleSink(decoder::accept);
            decoder.start();
            long start = System.nanoTime();
            source.start();
            if (!source.awaitCompletion(Duration.ofMinutes(10))) throw new IllegalStateException("Source timed out");
            elapsed = (System.nanoTime() - start) * 1e-9;
            if (source.failure().isPresent()) throw new IllegalStateException("Source failed", source.failure().orElseThrow());
            if (decoder.state() != ComponentState.RUNNING) throw new IllegalStateException("Decoder is " + decoder.state());
            decoderMetrics = new TreeMap<>(decoder.monitoringSnapshot().values());
            sourceMetrics = new TreeMap<>(source.monitoringSnapshot().values());
            if (Boolean.getBoolean("jsatdecode.replay.leased")) {
                var pool = source.leasedStatistics().orElseThrow();
                if (pool.pool().outstandingLeases() != 0) throw new IllegalStateException("Outstanding leases: " + pool);
                System.out.println("leased_diagnostics=" + pool);
            }
            if (!Boolean.TRUE.equals(sourceMetrics.get("source.eof"))
                    || !sourceMetrics.get("source.selected_samples").equals(sourceMetrics.get("source.samples"))) {
                throw new IllegalStateException("Recording did not reach EOF exactly once");
            }
            long frameCount = frames.size() / FRAME_BYTES;
            if (frameCount == 0 || ((Number) decoderMetrics.get(DecoderStatusKeys.FRAME_GOOD)).longValue() != frameCount) {
                throw new IllegalStateException("Delivered frames and decoder accounting disagree");
            }
            decoder.stop();
        }
        if (Files.size(recording) != inputBytes || !Files.getLastModifiedTime(recording).equals(inputModified)) {
            throw new IllegalStateException("Recording changed during replay");
        }
        byte[] outputBytes = frames.toByteArray();
        Files.write(output, outputBytes, StandardOpenOption.CREATE_NEW);
        String outputHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(outputBytes));
        long samples = ((Number) sourceMetrics.get("source.samples")).longValue();
        System.out.println("java=" + System.getProperty("java.runtime.version"));
        System.out.println("input_bytes=" + inputBytes + " input_sha256=" + inputHash);
        System.out.println("samples=" + samples + " frames=" + (outputBytes.length / FRAME_BYTES)
                + " output_sha256=" + outputHash);
        System.out.printf(Locale.ROOT, "signal_seconds=%.6f wall_seconds=%.6f realtime_factor=%.3f%n",
                samples / 500_000.0, elapsed, samples / 500_000.0 / elapsed);
        System.out.println("source_metrics=" + sourceMetrics);
        System.out.println("decoder_metrics=" + decoderMetrics);
    }
}
