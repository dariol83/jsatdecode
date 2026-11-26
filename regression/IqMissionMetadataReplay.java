import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.source.wav.WavSampleSource;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Fingerprints every source IQ value and records ordered block and frame
 * metadata for a packaged mission replay. Absolute UTC anchors differ between
 * runs: block/frame timestamps and the rx_time_ns annotation are expressed
 * relative to the first source block's timestamp. Other metadata is retained.
 */
public final class IqMissionMetadataReplay {
    private IqMissionMetadataReplay() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("hinode") || args[0].equals("elektro"))) {
            throw new IllegalArgumentException("Expected: hinode|elektro input-file new-output-prefix");
        }
        boolean hinode = args[0].equals("hinode");
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path prefix = Path.of(args[2]).toAbsolutePath().normalize();
        long inputSize = Files.size(input);
        var inputModified = Files.getLastModifiedTime(input);
        int rate = hinode ? 500_000 : 1_000_000;
        Configuration sourceSettings = new Configuration(Map.of("file", input.toString(),
                "sample_rate", rate, "block_samples", 4096, "repeat", false, "realtime", false));
        try (Capture capture = new Capture(prefix);
             Decoder decoder = hinode ? new HinodeDecoder(new Configuration(Map.of()))
                     : new ElektroLGgakDecoder(new Configuration(Map.of("sample_rate_hz", rate, "dcblocker", false)));
             ManagedSampleSource source = hinode ? new WavSampleSource(sourceSettings) : new Cs16SampleSource(sourceSettings)) {
            source.setSampleSink(block -> {
                capture.block(block);
                decoder.accept(block);
            });
            decoder.setFrameSink(capture::frame);
            decoder.start();
            long started = System.nanoTime();
            source.start();
            boolean completed = hinode ? ((WavSampleSource) source).awaitCompletion(Duration.ofMinutes(10))
                    : ((Cs16SampleSource) source).awaitCompletion(Duration.ofMinutes(10));
            double seconds = (System.nanoTime() - started) * 1e-9;
            if (!completed || source.failure().isPresent() || decoder.state() != ComponentState.RUNNING) {
                throw new IllegalStateException("Replay failed or timed out: " + source.failure());
            }
            var sourceMetrics = new TreeMap<>(source.monitoringSnapshot().values());
            var decoderMetrics = new TreeMap<>(decoder.monitoringSnapshot().values());
            if (!Boolean.TRUE.equals(sourceMetrics.get("source.eof"))
                    || ((Number) sourceMetrics.get("source.samples")).longValue() != capture.samples
                    || ((Number) sourceMetrics.get("source.blocks")).longValue() != capture.blockCount
                    || capture.frames == 0
                    || ((Number) decoderMetrics.get(DecoderStatusKeys.FRAME_GOOD)).longValue() != capture.frames) {
                throw new IllegalStateException("Source or frame accounting mismatch");
            }
            if (Files.size(input) != inputSize || !Files.getLastModifiedTime(input).equals(inputModified)) {
                throw new IllegalStateException("Input changed during replay");
            }
            capture.close();
            System.out.println("mission=" + args[0] + " samples=" + capture.samples + " blocks=" + capture.blockCount
                    + " frames=" + capture.frames + " seconds=" + seconds);
            System.out.println("iq_sha256=" + capture.hex(capture.iqDigest));
            System.out.println("frame_payload_sha256=" + capture.hex(capture.payloadDigest));
            System.out.println("block_metadata_sha256=" + capture.hex(capture.blockDigest));
            System.out.println("frame_metadata_sha256=" + capture.hex(capture.frameDigest));
            System.out.println("source_metrics=" + sourceMetrics);
            System.out.println("decoder_metrics=" + decoderMetrics);
        }
    }

    private static final class Capture implements AutoCloseable {
        private final MessageDigest iqDigest = digest();
        private final MessageDigest payloadDigest = digest();
        private final MessageDigest blockDigest = digest();
        private final MessageDigest frameDigest = digest();
        private final DataOutputStream blockMetadata;
        private final DataOutputStream frameMetadata;
        private byte[] iq = new byte[4096 * 8];
        private boolean anchored, closed;
        private long anchor, samples, blockCount, frames;

        private Capture(Path prefix) throws IOException {
            Path blockPath = Path.of(prefix + "-blocks.bin");
            Path framePath = Path.of(prefix + "-frame-metadata.bin");
            if (Files.exists(blockPath) || Files.exists(framePath)) throw new IOException("Evidence output already exists");
            blockMetadata = output(blockPath, blockDigest);
            try {
                frameMetadata = output(framePath, frameDigest);
            } catch (IOException | RuntimeException | Error e) {
                blockMetadata.close();
                throw e;
            }
        }

        private static DataOutputStream output(Path path, MessageDigest digest) throws IOException {
            return new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(
                    Files.newOutputStream(path, StandardOpenOption.CREATE_NEW), digest)));
        }

        private void block(ComplexSampleBlock block) {
            if (!anchored) {
                anchor = block.timestampNanos();
                anchored = true;
            }
            int count = block.complexSamples();
            int bytes = Math.multiplyExact(count, 8);
            if (iq.length < bytes) iq = new byte[bytes];
            for (int n = 0; n < count; n++) {
                putInt(iq, n * 8, Float.floatToRawIntBits(block.i(n)));
                putInt(iq, n * 8 + 4, Float.floatToRawIntBits(block.q(n)));
            }
            iqDigest.update(iq, 0, bytes);
            try {
                blockMetadata.writeInt(count);
                blockMetadata.writeLong(block.sampleIndex());
                blockMetadata.writeLong(Math.subtractExact(block.timestampNanos(), anchor));
                blockMetadata.writeLong(Double.doubleToRawLongBits(block.sampleRate()));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            samples += count;
            blockCount++;
        }

        private void frame(AnnotatedFrame frame) {
            if (!anchored) throw new IllegalStateException("Frame arrived before the first source block");
            payloadDigest.update(frame.data());
            try {
                frameMetadata.writeLong(Math.subtractExact(frame.timestampNanos(), anchor));
                Map<String, Object> ordered = new TreeMap<>(frame.annotations());
                frameMetadata.writeInt(ordered.size());
                for (var entry : ordered.entrySet()) {
                    Object value = entry.getValue();
                    if (!(value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long)) {
                        throw new IllegalStateException("Unsupported frame annotation type: " + value);
                    }
                    frameMetadata.writeUTF(entry.getKey());
                    frameMetadata.writeUTF(value.getClass().getName());
                    if (entry.getKey().equals("rx_time_ns")) {
                        if (!(value instanceof Number number)) throw new IllegalStateException("Non-numeric rx_time_ns");
                        frameMetadata.writeUTF(Long.toString(Math.subtractExact(number.longValue(), anchor)));
                    } else {
                        frameMetadata.writeUTF(value.toString());
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            frames++;
        }

        private static void putInt(byte[] target, int offset, int value) {
            target[offset] = (byte) value;
            target[offset + 1] = (byte) (value >>> 8);
            target[offset + 2] = (byte) (value >>> 16);
            target[offset + 3] = (byte) (value >>> 24);
        }

        private static MessageDigest digest() {
            try { return MessageDigest.getInstance("SHA-256"); }
            catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
        }

        private String hex(MessageDigest digest) {
            return HexFormat.of().formatHex(digest.digest());
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try { blockMetadata.close(); }
            finally { frameMetadata.close(); }
        }
    }
}
