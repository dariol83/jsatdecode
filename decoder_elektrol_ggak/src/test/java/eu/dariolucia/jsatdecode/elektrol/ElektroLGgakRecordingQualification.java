package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit, bounded-memory acceptance replay of a private CS16 recording; absent from normal tests. */
public final class ElektroLGgakRecordingQualification {
    private static final long MINIMUM_GOOD = 272;
    private static final long MAXIMUM_BAD = 13;
    private static final double MINIMUM_SPEED = 2.;

    private ElektroLGgakRecordingQualification() {}

    /**
     * Replays the complete recording through the production CS16 source and framework runtime.
     * Timing includes source startup, file reads, CS16 conversion, immutable sample blocks, decoder
     * work and monitoring taps, output checksum/annotation validation and hashing, EOF and stop.
     * Runtime construction, input SHA-256 calculation and report writing are outside this interval.
     * Input and output are hashed incrementally; neither recording nor recovered frames are retained.
     * The default configuration is the 1 Msps GRC receiver, without an optional DC blocker.
     *
     * @param args recording.cs16 [report.json] [--sample-rate=N] [--block-samples=N]
     *             [--dcblocker=true|false]
     * @throws Exception if the replay fails, accounting/metadata is inconsistent, or acceptance fails
     */
    public static void main(String[] args) throws Exception {
        if (args.length == 0)
            throw new IllegalArgumentException("Expected recording.cs16 [report.json] [--sample-rate=N]"
                    + " [--block-samples=N] [--dcblocker=true|false]");
        Path recording = Path.of(args[0]).toAbsolutePath().normalize();
        Path report = Path.of("target", "ggak-recording-qualification.json").toAbsolutePath().normalize();
        int rate = 1_000_000, blockSamples = 4096;
        boolean dcblocker = false, hasReport = false;
        for (int n = 1; n < args.length; n++) {
            String argument = args[n];
            if (argument.startsWith("--sample-rate="))
                rate = Integer.parseInt(argument.substring("--sample-rate=".length()));
            else if (argument.startsWith("--block-samples="))
                blockSamples = Integer.parseInt(argument.substring("--block-samples=".length()));
            else if (argument.equals("--dcblocker=true")) dcblocker = true;
            else if (argument.equals("--dcblocker=false")) dcblocker = false;
            else if (!argument.startsWith("--") && !hasReport) {
                report = Path.of(argument).toAbsolutePath().normalize();
                hasReport = true;
            } else throw new IllegalArgumentException("Unexpected argument: " + argument);
        }
        if (recording.equals(report) || (Files.exists(report) && Files.isSameFile(recording, report)))
            throw new IllegalArgumentException("Report must not overwrite the recording");
        long inputBytes = Files.size(recording);
        FileTime modified = Files.getLastModifiedTime(recording);
        if (inputBytes == 0 || inputBytes % 4 != 0)
            throw new IllegalArgumentException("Expected nonempty complete little-endian signed CS16 IQ");
        long inputSamples = inputBytes / 4;
        double signalSeconds = inputSamples / (double) rate;
        Capture capture = new Capture();
        long good, bad, total;
        double wallSeconds;
        try (var source = new Cs16SampleSource(new Configuration(Map.of(
                "type", "cs16", "file", recording.toString(), "sample_rate", rate,
                "block_samples", blockSamples, "repeat", false, "realtime", false)));
             var decoder = new ElektroLGgakDecoder(new Configuration(Map.of(
                     "type", "elektrol_ggak", "sample_rate_hz", rate, "dcblocker", dcblocker)));
             var runtime = new JSatDecodeRuntime(source, decoder, List.of(capture), List.of())) {
            long started = System.nanoTime();
            runtime.start();
            runtime.awaitCompletion();
            wallSeconds = (System.nanoTime() - started) * 1e-9;
            if (runtime.failure().isPresent())
                throw new AssertionError("Runtime failed", runtime.failure().orElseThrow());
            if (source.failure().isPresent())
                throw new AssertionError("CS16 source failed", source.failure().orElseThrow());
            if (decoder.failure().isPresent())
                throw new AssertionError("Decoder failed", decoder.failure().orElseThrow());
            var sourceCounters = source.monitoringSnapshot().values();
            if (!Long.valueOf(inputSamples).equals(sourceCounters.get("source.samples"))
                    || !Boolean.TRUE.equals(sourceCounters.get("source.eof")))
                throw new AssertionError("Source did not deliver the complete recording exactly once");
            var counters = decoder.monitoringSnapshot().values();
            good = ((Number) counters.get(DecoderStatusKeys.FRAME_GOOD)).longValue();
            bad = ((Number) counters.get(DecoderStatusKeys.FRAME_BAD)).longValue();
            total = ((Number) counters.get(DecoderStatusKeys.FRAME_TOTAL)).longValue();
            if (good != capture.frames || total != good + bad)
                throw new AssertionError("Decoder counters disagree with delivered checksum-valid frames");
        }
        String outputHash = HexFormat.of().formatHex(capture.hash.digest());
        String inputHash = hashFile(recording);
        if (Files.size(recording) != inputBytes || !Files.getLastModifiedTime(recording).equals(modified))
            throw new AssertionError("Recording changed during qualification");
        double speed = signalSeconds / wallSeconds;
        boolean accepted = good >= MINIMUM_GOOD && bad <= MAXIMUM_BAD && speed > MINIMUM_SPEED;
        String result = String.format(Locale.ROOT, """
                {
                  "recording": %s,
                  "input_bytes": %d,
                  "input_sha256": "%s",
                  "sample_rate_hz": %d,
                  "block_samples": %d,
                  "dcblocker": %s,
                  "samples": %d,
                  "frames": %d,
                  "rejected_frames": %d,
                  "candidates": %d,
                  "checksum_annotations_valid": true,
                  "output_sha256": "%s",
                  "signal_seconds": %.9f,
                  "wall_seconds": %.9f,
                  "realtime_factor": %.9f,
                  "minimum_valid_frames": %d,
                  "maximum_rejected_frames": %d,
                  "required_realtime_factor_exclusive": %.1f,
                  "accepted": %s,
                  "java_runtime": %s,
                  "os": %s
                }
                """, json(recording.toString()), inputBytes, inputHash, rate, blockSamples, dcblocker,
                inputSamples, good, bad, total, outputHash, signalSeconds, wallSeconds, speed,
                MINIMUM_GOOD, MAXIMUM_BAD, MINIMUM_SPEED, accepted,
                json(System.getProperty("java.runtime.version")),
                json(System.getProperty("os.name") + " " + System.getProperty("os.arch")));
        Files.createDirectories(report.getParent());
        Files.writeString(report, result);
        System.out.printf(Locale.ROOT,
                "frames=%d rejected=%d candidates=%d signal=%.6fs wall=%.6fs speed=%.3fx sha256=%s report=%s%n",
                good, bad, total, signalSeconds, wallSeconds, speed, outputHash, report);
        if (!accepted)
            throw new AssertionError("Recording acceptance requires >=" + MINIMUM_GOOD + " valid frames, <="
                    + MAXIMUM_BAD + " rejected frames and >" + MINIMUM_SPEED + "x realtime; see " + report);
        System.out.println("PASS complete CS16/runtime recording acceptance");
    }

    private static String hashFile(Path path) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[1 << 20];
            int read;
            while ((read = input.read(buffer)) >= 0) if (read > 0) hash.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(hash.digest());
    }

    private static String json(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int n = 0; n < value.length(); n++) {
            char c = value.charAt(n);
            if (c == '"' || c == '\\') escaped.append('\\').append(c);
            else if (c < 32) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            else escaped.append(c);
        }
        return escaped.append('"').toString();
    }

    private static final class Capture implements FrameDistributor {
        private final MessageDigest hash;
        private long frames;
        private volatile ComponentState state = ComponentState.CONFIGURED;

        Capture() throws Exception { hash = MessageDigest.getInstance("SHA-256"); }
        public FrameDistributorDescriptor descriptor() {
            return new FrameDistributorDescriptor("ggak-recording-capture", "GGAK recording hash", "1",
                    "Test-only bounded checksum validator and ordered payload digest");
        }
        public void start() { state = ComponentState.RUNNING; }
        public void distribute(AnnotatedFrame frame) {
            ElektroLGgakQualification.validateAcceptedFrame(frame);
            hash.update(frame.data());
            frames = Math.incrementExact(frames);
        }
        public DistributionSnapshot monitoringSnapshot() {
            return new DistributionSnapshot(state == ComponentState.RUNNING, true,
                    frames, frames, 0, 0, 0, 0, UtcTime.nowNanos());
        }
        public ComponentState state() { return state; }
        public void stop() { if (state != ComponentState.CLOSED) state = ComponentState.STOPPED; }
        public void close() { state = ComponentState.CLOSED; }
    }
}
