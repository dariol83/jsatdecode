import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleSource;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.source.wav.WavSampleSource;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Packaged production runtime replay for both mission decoders. */
public final class IqMissionRuntimeReplay {
    private IqMissionRuntimeReplay() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("hinode") || args[0].equals("elektro")))
            throw new IllegalArgumentException("Expected: hinode|elektro input-file new-frames-file");
        boolean hinode = args[0].equals("hinode");
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        Path output = Path.of(args[2]).toAbsolutePath().normalize();
        if (Files.exists(output)) throw new IllegalArgumentException("Output already exists");
        long size = Files.size(input);
        var modified = Files.getLastModifiedTime(input);
        int rate = hinode ? 500_000 : 1_000_000;
        Configuration settings = new Configuration(Map.of("file", input.toString(), "sample_rate", rate,
                "block_samples", 4096, "repeat", false, "realtime", false));
        Capture capture = new Capture(hinode ? 892 : 220);
        try (ManagedSampleSource source = hinode ? new WavSampleSource(settings) : new Cs16SampleSource(settings);
             Decoder decoder = hinode ? new HinodeDecoder(new Configuration(Map.of()))
                     : new ElektroLGgakDecoder(new Configuration(Map.of("sample_rate_hz", rate, "dcblocker", false)));
             JSatDecodeRuntime runtime = new JSatDecodeRuntime(source, decoder, List.of(capture), List.of())) {
            long started = System.nanoTime();
            runtime.start();
            runtime.awaitCompletion();
            double elapsed = (System.nanoTime() - started) * 1e-9;
            if (runtime.failure().isPresent() || source.failure().isPresent())
                throw new IllegalStateException("Runtime replay failed: " + runtime.failure());
            var sourceMetrics = new TreeMap<>(source.monitoringSnapshot().values());
            var decoderMetrics = new TreeMap<>(decoder.monitoringSnapshot().values());
            var stats = ((LeasedSampleSource) source).leasedStatistics().orElseThrow();
            if (!Boolean.TRUE.equals(sourceMetrics.get("source.eof")) || stats.pool().outstandingLeases() != 0
                    || stats.deliveredBlocks() != ((Number) sourceMetrics.get("source.blocks")).longValue()
                    || capture.frames == 0 || capture.frames != ((Number) decoderMetrics.get(DecoderStatusKeys.FRAME_GOOD)).longValue())
                throw new IllegalStateException("Runtime replay accounting mismatch");
            System.out.println("mission=" + args[0] + " seconds=" + elapsed + " frames=" + capture.frames);
            System.out.println("leased_diagnostics=" + stats);
            System.out.println("source_metrics=" + sourceMetrics);
            System.out.println("decoder_metrics=" + decoderMetrics);
        }
        if (Files.size(input) != size || !Files.getLastModifiedTime(input).equals(modified))
            throw new IllegalStateException("Input changed during replay");
        byte[] frames = capture.bytes.toByteArray();
        Files.write(output, frames, StandardOpenOption.CREATE_NEW);
        System.out.println("frame_payload_sha256=" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frames)));
    }

    private static final class Capture implements FrameDistributor {
        private final int frameBytes;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile ComponentState state = ComponentState.CONFIGURED;
        private long frames;
        Capture(int frameBytes) { this.frameBytes = frameBytes; }
        public FrameDistributorDescriptor descriptor() { return new FrameDistributorDescriptor("runtime-capture", "Runtime capture", "1", "Ordered reference frames"); }
        public void start() { state = ComponentState.RUNNING; }
        public void distribute(AnnotatedFrame frame) {
            byte[] payload = frame.data();
            if (payload.length != frameBytes || frames >= 10_000) throw new IllegalStateException("Unexpected frame or capture limit");
            bytes.writeBytes(payload);
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
