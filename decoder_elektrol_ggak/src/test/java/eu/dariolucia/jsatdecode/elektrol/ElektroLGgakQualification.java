package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import ru.r2cloud.jradio.blocks.Firdes;
import ru.r2cloud.jradio.blocks.Window;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Explicit independent GNU Radio qualification; never requires fixtures for normal builds. */
public final class ElektroLGgakQualification {
    private static final int PAYLOAD_BYTES = 220;
    private static final long MAX_INPUT_BYTES = 512L * 1024 * 1024;
    private static final String[] STAGES = {"channel", "timing", "agc", "pll", "pm", "rrc", "costas", "symbols"};
    private static final long EPOCH = 1_790_000_000_000_000_000L;

    private ElektroLGgakQualification() {}

    /**
     * Takes a rate directory made by qualify_reference.py, its sample rate, and optional JSON report.
     * IQ reading, trace comparison and file writes are excluded from wrapper replay measurements.
     * Each speed result includes input block construction, the full decoder, monitoring taps, frame
     * delivery and stop. All reference payloads must match in count, order and bytes at each partition.
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("Expected reference-rate-directory sample-rate-hz [report.json]");
        Path directory = Path.of(args[0]);
        int rate = Integer.parseInt(args[1]);
        Path reportFile = args.length == 3 ? Path.of(args[2]) : directory.resolve("java_qualification.json");
        float[] input = readFloats(directory.resolve("input.cf32"), MAX_INPUT_BYTES);
        if ((input.length & 1) != 0) throw new IllegalArgumentException("Odd cf32 input component count");
        byte[] expected = Files.readAllBytes(directory.resolve("frames.bin"));
        byte[] transmitted = Files.readAllBytes(directory.resolve("expected_frames.bin"));
        if (expected.length == 0 || expected.length % PAYLOAD_BYTES != 0 || !Arrays.equals(expected, transmitted))
            throw new AssertionError("GNU Radio must first recover every checksum-valid transmitted payload exactly");
        long expectedRejected = expectedRejected(directory);
        Configuration configuration = new Configuration(Map.of("sample_rate_hz", Integer.toString(rate), "dcblocker", "false"));
        StringBuilder report = new StringBuilder("{\n  \"sample_rate_hz\": ").append(rate)
                .append(",\n  \"samples\": ").append(input.length / 2)
                .append(",\n  \"reference_frames\": ").append(expected.length / PAYLOAD_BYTES)
                .append(",\n  \"reference_rejected_frames\": ").append(expectedRejected)
                .append(",\n  \"reference_candidates\": ").append(expected.length / PAYLOAD_BYTES + expectedRejected)
                .append(",\n  \"reference_sha256\": \"").append(hash(expected)).append("\",\n");
        report.append("  \"tap_comparison\": ").append(compareTaps(directory, rate)).append(",\n");
        report.append("  \"stage_comparison\": ").append(compareStages(directory, input, configuration)).append(",\n");
        List<Replay> replays = new ArrayList<>();
        // Replays follow the numerical stage pass and exercise two source partitions.
        for (int partition : new int[]{4096, 137, 4096}) {
            Replay replay = replay(input, rate, partition, configuration, expectedRejected);
            replays.add(replay);
            System.out.printf(Locale.ROOT, "rate=%d block=%d frames=%d rejected=%d candidates=%d signal=%.6f wall=%.6f speed=%.3fx sha256=%s%n",
                    rate, partition, replay.actual.length / PAYLOAD_BYTES, replay.rejected, replay.candidates, replay.signalSeconds,
                    replay.wallSeconds, replay.speed(), hash(replay.actual));
            Files.write(directory.resolve("java-frames-" + partition + ".bin"), replay.actual);
        }
        report.append("  \"replays\": [\n");
        for (int n = 0; n < replays.size(); n++) {
            Replay replay = replays.get(n);
            if (n != 0) report.append(",\n");
            report.append(String.format(Locale.ROOT,
                    "    {\"block_samples\":%d,\"frames\":%d,\"rejected_frames\":%d,\"candidates\":%d,\"checksum_annotations_valid\":true,\"wall_seconds\":%.9f,\"signal_seconds\":%.9f,\"realtime_factor\":%.9f,\"sha256\":\"%s\",\"matches_reference\":%s}",
                    replay.partition, replay.actual.length / PAYLOAD_BYTES, replay.rejected, replay.candidates, replay.wallSeconds,
                    replay.signalSeconds, replay.speed(), hash(replay.actual), Arrays.equals(expected, replay.actual)));
        }
        report.append("\n  ]\n}\n");
        if (reportFile.toAbsolutePath().getParent() != null) Files.createDirectories(reportFile.toAbsolutePath().getParent());
        Files.writeString(reportFile, report);
        for (Replay replay : replays) {
            if (!Arrays.equals(expected, replay.actual))
                throw new AssertionError("Reference frames differ at byte " + Arrays.mismatch(expected, replay.actual)
                        + " with partition " + replay.partition + "; see " + reportFile);
            if (replay.speed() <= 2.)
                throw new AssertionError("Full decoder replay must exceed 2x real time; see " + reportFile);
        }
        System.out.println("PASS exact GNU Radio frames, partition independence and >2x full decoder replay; report=" + reportFile);
    }

    private static Replay replay(float[] input, int rate, int partition, Configuration configuration,
                                 long expectedRejected) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ElektroLGgakDecoder decoder = new ElektroLGgakDecoder(configuration)) {
            decoder.setFrameSink(frame -> {
                validateAcceptedFrame(frame);
                byte[] data = frame.data();
                if (data.length != PAYLOAD_BYTES || output.size() > PAYLOAD_BYTES * 100000)
                    throw new AssertionError("Unexpected or excessive output payloads");
                output.writeBytes(data);
            });
            float[] block = new float[partition * 2];
            decoder.start();
            long started = System.nanoTime();
            for (int first = 0; first < input.length / 2; first += partition) {
                int count = Math.min(partition, input.length / 2 - first);
                System.arraycopy(input, first * 2, block, 0, count * 2);
                decoder.accept(new ComplexSampleBlock(block, count, first,
                        EPOCH + Math.round(first * 1e9 / rate), rate));
            }
            decoder.stop();
            double elapsed = (System.nanoTime() - started) * 1e-9;
            if (decoder.failure().isPresent()) throw new AssertionError("Decoder failure", decoder.failure().orElseThrow());
            var counters = decoder.monitoringSnapshot().values();
            long good = (Long) counters.get(DecoderStatusKeys.FRAME_GOOD);
            long bad = (Long) counters.get(DecoderStatusKeys.FRAME_BAD);
            long total = (Long) counters.get(DecoderStatusKeys.FRAME_TOTAL);
            if (good != output.size() / PAYLOAD_BYTES || bad != expectedRejected || total != good + expectedRejected)
                throw new AssertionError("Checksum candidate accounting differs: good=" + good + " bad=" + bad + " total=" + total);
            return new Replay(partition, output.toByteArray(), elapsed, input.length / (2. * rate), bad, total);
        }
    }

    /** Independently validates transmitted checksum truth and the ordered accepted subset. */
    static long expectedRejected(Path directory) throws IOException {
        Path candidatesFile = directory.resolve("transmitted_frames.bin");
        Path acceptedFile = directory.resolve("expected_frames.bin");
        if (Files.size(candidatesFile) > 220_000_000 || Files.size(acceptedFile) > 220_000_000)
            throw new IllegalArgumentException("Checksum qualification truth exceeds its bound");
        byte[] candidates = Files.readAllBytes(candidatesFile), accepted = Files.readAllBytes(acceptedFile);
        if (candidates.length == 0 || candidates.length % PAYLOAD_BYTES != 0 || accepted.length % PAYLOAD_BYTES != 0)
            throw new IllegalArgumentException("Invalid checksum qualification truth sizes");
        long rejected = 0;
        int nextAccepted = 0;
        for (int first = 0; first < candidates.length; first += PAYLOAD_BYTES) {
            int expected = checksum(candidates, first);
            int received = ((candidates[first + 218] & 255) << 8) | (candidates[first + 219] & 255);
            if (expected != received) {
                rejected++;
                continue;
            }
            if (nextAccepted + PAYLOAD_BYTES > accepted.length
                    || !Arrays.equals(candidates, first, first + PAYLOAD_BYTES,
                    accepted, nextAccepted, nextAccepted + PAYLOAD_BYTES))
                throw new AssertionError("Checksum-valid transmitted subset differs from expected frames");
            nextAccepted += PAYLOAD_BYTES;
        }
        if (nextAccepted != accepted.length)
            throw new AssertionError("Expected frames include candidates rejected by independent checksum truth");
        return rejected;
    }

    /** Checks output integrity metadata independently of production checksum code. */
    static void validateAcceptedFrame(AnnotatedFrame frame) {
        byte[] payload = frame.data();
        if (payload.length != PAYLOAD_BYTES) throw new AssertionError("Unexpected GGAK payload size");
        int received = ((payload[218] & 255) << 8) | (payload[219] & 255);
        if (checksum(payload, 0) != received)
            throw new AssertionError("Decoder emitted a checksum-invalid GGAK payload");
        if (!Boolean.TRUE.equals(frame.annotations().get("checksum_valid"))
                || !Integer.valueOf(received).equals(frame.annotations().get("checksum"))
                || !"UNKNOWN".equals(frame.annotations().get("quality")))
            throw new AssertionError("Accepted GGAK checksum annotations differ from independent truth");
    }

    private static int checksum(byte[] bytes, int first) {
        int sum = 0x0202;
        for (int n = 0; n < 218; n++) sum += bytes[first + n] & 255;
        return sum & 0xffff;
    }

    private static String compareTaps(Path directory, int rate) throws IOException {
        Map<String, float[]> actual = new LinkedHashMap<>();
        actual.put("channel", Firdes.lowPass(1., rate, 40000., 15000., Window.WIN_HAMMING, 6.76));
        actual.put("timing", Firdes.lowPass(1., 250000., 20000., 4000., Window.WIN_HAMMING, 6.76));
        actual.put("rrc", Firdes.rootRaisedCosine(1., 50000., 5000., .5, 31));
        StringBuilder result = new StringBuilder("{");
        boolean separator = false;
        for (Map.Entry<String, float[]> entry : actual.entrySet()) {
            float[] expected = readFloats(directory.resolve("taps_" + entry.getKey() + ".f32"), 1 << 20);
            if (expected.length != entry.getValue().length) throw new AssertionError("Tap count differs for " + entry.getKey());
            double maxError = 0.;
            for (int n = 0; n < expected.length; n++) maxError = Math.max(maxError, Math.abs(expected[n] - entry.getValue()[n]));
            if (maxError > 1e-6) throw new AssertionError("Tap design differs for " + entry.getKey() + ": " + maxError);
            if (separator) result.append(',');
            separator = true;
            result.append(String.format(Locale.ROOT, "\"%s\":{\"count\":%d,\"max_absolute_error\":%.12g}", entry.getKey(), expected.length, maxError));
        }
        return result.append('}').toString();
    }

    private static String compareStages(Path directory, float[] input, Configuration configuration) throws IOException {
        Map<String, Stage> captures = new LinkedHashMap<>();
        for (String name : STAGES) {
            float[] expected = readFloats(directory.resolve(name + ".cf32"), MAX_INPUT_BYTES);
            captures.put(name, new Stage(expected));
        }
        ElektroLGgakDsp dsp = new ElektroLGgakDsp(ElektroLGgakSettings.read(configuration), new ElektroLGgakDsp.Output() {
            @Override public void baseband(float i, float q) {}
            @Override public void symbol(float i, float q) {}
        }, (name, i, q) -> captures.get(name).add(i, q));
        for (int n = 0; n < input.length; n += 2) dsp.sample(input[n], input[n + 1]);
        StringBuilder result = new StringBuilder("{\n");
        boolean separator = false;
        for (Map.Entry<String, Stage> entry : captures.entrySet()) {
            Stage stage = entry.getValue();
            writeFloats(directory.resolve("java-" + entry.getKey() + ".cf32"), stage.actual, stage.components);
            if (separator) result.append(",\n");
            separator = true;
            String statistics = stage.statistics(entry.getKey());
            result.append("    \"").append(entry.getKey()).append("\":").append(statistics);
            System.out.println("stage=" + entry.getKey() + " " + statistics);
        }
        return result.append("\n  }").toString();
    }

    private static float[] readFloats(Path file, long bound) throws IOException {
        long bytes = Files.size(file);
        if (bytes == 0 || bytes > bound || bytes % 4 != 0) throw new IllegalArgumentException("Invalid/bounded float fixture " + file);
        float[] result = new float[Math.toIntExact(bytes / 4)];
        try (FileChannel channel = FileChannel.open(file)) {
            ByteBuffer buffer = ByteBuffer.allocate(1 << 18).order(ByteOrder.LITTLE_ENDIAN);
            int position = 0;
            while (channel.read(buffer) > 0) {
                buffer.flip();
                while (buffer.remaining() >= 4) result[position++] = buffer.getFloat();
                buffer.compact();
            }
            if (position != result.length || buffer.position() != 0) throw new IOException("Incomplete float fixture " + file);
        }
        return result;
    }

    private static void writeFloats(Path file, float[] values, int count) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.allocate(1 << 18).order(ByteOrder.LITTLE_ENDIAN);
            for (int n = 0; n < count; n++) {
                if (buffer.remaining() < 4) { buffer.flip(); while (buffer.hasRemaining()) channel.write(buffer); buffer.clear(); }
                buffer.putFloat(values[n]);
            }
            buffer.flip();
            while (buffer.hasRemaining()) channel.write(buffer);
        }
    }

    private static String hash(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    private record Replay(int partition, byte[] actual, double wallSeconds, double signalSeconds,
                          long rejected, long candidates) {
        double speed() { return signalSeconds / wallSeconds; }
    }

    private static final class Stage {
        final float[] expected, actual;
        int components;
        Stage(float[] expected) { this.expected = expected; actual = new float[expected.length + 1024]; }
        void add(float i, float q) {
            if (!Float.isFinite(i) || !Float.isFinite(q) || components >= actual.length - 1)
                throw new AssertionError("Nonfinite or excessive DSP stage output");
            actual[components++] = i;
            actual[components++] = q;
        }
        String statistics(String stageName) {
            int count = Math.min(components, expected.length);
            double squaredError = 0., squaredReference = 0., maxError = 0.;
            long signErrors = 0;
            for (int n = 0; n < count; n++) {
                double delta = actual[n] - expected[n];
                squaredError += delta * delta;
                squaredReference += expected[n] * expected[n];
                maxError = Math.max(maxError, Math.abs(delta));
                if ((n & 1) == 0 && (actual[n] >= 0) != (expected[n] >= 0)) signErrors++;
            }
            // Report the best integer alignment and unit complex phase separately. Neither
            // alignment nor phase correction is applied to decoder samples or frame results.
            int bestLag = 0;
            double bestRelative = Double.POSITIVE_INFINITY, bestPhase = 0.;
            int complexCount = count / 2;
            for (int lag = -32; lag <= 32; lag++) {
                double realDot = 0., imagDot = 0., referencePower = 0., actualPower = 0.;
                for (int k = 4096; k < complexCount - 32; k += 4) {
                    int a = (k + lag) * 2, r = k * 2;
                    double ar = actual[a], ai = actual[a + 1], rr = expected[r], ri = expected[r + 1];
                    realDot += rr * ar + ri * ai;
                    imagDot += ri * ar - rr * ai;
                    referencePower += rr * rr + ri * ri;
                    actualPower += ar * ar + ai * ai;
                }
                double relative = Math.sqrt(Math.max(0., referencePower + actualPower - 2 * Math.hypot(realDot, imagDot)) / referencePower);
                if (relative < bestRelative) { bestRelative = relative; bestLag = lag; bestPhase = Math.atan2(imagDot, realDot); }
            }
            double relativeError = Math.sqrt(squaredError / squaredReference);
            boolean symbolStage = stageName.equals("symbols");
            int extraSamples = (components - expected.length) / 2;
            // Compare the original sample coordinates and phase. Library float math and
            // 1/128-step MM interpolation selection need bounded numerical tolerances.
            // The adapted timing controller also preserves GNU Radio's forecast lookahead;
            // stage sample counts must match, including terminal symbol availability.
            if (extraSamples != 0)
                throw new AssertionError("Unexpected stage output count for " + stageName + ": delta=" + extraSamples);
            if (!Double.isFinite(relativeError) || relativeError > (symbolStage ? .001 : .0001))
                throw new AssertionError("Zero-lag numerical agreement failed for " + stageName + ": " + relativeError);
            if (symbolStage && signErrors != 0)
                throw new AssertionError("Recovered symbol sign differs from GNU Radio: " + signErrors);
            return String.format(Locale.ROOT,
                    "{\"reference_samples\":%d,\"java_samples\":%d,\"compared_samples\":%d,\"max_component_error\":%.12g,\"rms_relative_error_zero_lag\":%.12g,\"real_sign_disagreements_zero_lag\":%d,\"best_integer_lag\":%d,\"phase_correction_radians\":%.12g,\"rms_relative_error_aligned\":%.12g}",
                    expected.length / 2, components / 2, count / 2, maxError,
                    relativeError, signErrors, bestLag, bestPhase, bestRelative);
        }
    }
}
