import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.io.BufferedOutputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Synthetic, deterministic CS16 source-to-runtime-to-mission-decoder replay. */
public final class IqMemoryReplay {
    private static final int SAMPLES = 2_097_152;
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("generate|replay file.cs16");
        Path file = Path.of(args[1]);
        if (args[0].equals("generate")) {
            Random random = new Random(0x49_51_2026L);
            try (var out = new BufferedOutputStream(Files.newOutputStream(file, StandardOpenOption.CREATE_NEW))) {
                for (int i = 0; i < SAMPLES * 2; i++) {
                    int value = random.nextInt(65536) - 32768;
                    out.write(value & 255);
                    out.write((value >>> 8) & 255);
                }
            }
            System.out.println("samples=" + SAMPLES + " input_sha256=" + hash(file));
            return;
        }
        if (!args[0].equals("replay") || Files.size(file) != SAMPLES * 4L) throw new IllegalArgumentException();
        AtomicBoolean finished = new AtomicBoolean();
        AtomicLong peak = new AtomicLong();
        Thread heapSampler = new Thread(() -> {
            while (!finished.get()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        }, "heap-sampler");
        heapSampler.setDaemon(true);
        heapSampler.start();
        System.out.println("java=" + System.getProperty("java.runtime.version") + " input_sha256=" + hash(file));
        try {
            for (String mission : List.of("hinode", "elektro")) {
                for (int run = -2; run < 3; run++) replay(file, mission, run);
            }
        } finally {
            finished.set(true);
            heapSampler.join();
            System.out.println("sampled_peak_heap_bytes=" + peak.get() + " sampling_period_ms=10");
        }
    }
    private static void replay(Path file, String mission, int run) throws Exception {
        int rate = mission.equals("hinode") ? 500_000 : 1_000_000;
        var capture = new Capture();
        try (var source = new Cs16SampleSource(new Configuration(Map.of("file", file.toString(),
                "sample_rate", rate, "block_samples", 4096, "repeat", false, "realtime", false)));
             Decoder decoder = mission.equals("hinode") ? new HinodeDecoder(new Configuration(Map.of()))
                     : new ElektroLGgakDecoder(new Configuration(Map.of("sample_rate_hz", rate, "dcblocker", false)));
             var runtime = new JSatDecodeRuntime(source, decoder, List.of(capture), List.of())) {
            long start = System.nanoTime();
            runtime.start();
            runtime.awaitCompletion();
            double seconds = (System.nanoTime() - start) * 1e-9;
            if (runtime.failure().isPresent() || source.failure().isPresent())
                throw new IllegalStateException("Replay failure");
            var metrics = source.monitoringSnapshot().values();
            if (!Boolean.TRUE.equals(metrics.get("source.eof")) || !Long.valueOf(SAMPLES).equals(metrics.get("source.samples")))
                throw new IllegalStateException("Incomplete replay: " + metrics);
            System.out.printf(Locale.ROOT, "%s run=%d samples=%s blocks=%s frames=%d frame_sha256=%s seconds=%.6f Msamples_s=%.6f%n",
                    mission, run, metrics.get("source.samples"), metrics.get("source.blocks"), capture.frames,
                    HexFormat.of().formatHex(capture.digest.digest()), seconds, SAMPLES / seconds / 1e6);
        }
    }
    private static String hash(Path file) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] bytes = new byte[65536]; int n;
            while ((n = in.read(bytes)) != -1) digest.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static final class Capture implements FrameDistributor {
        private final MessageDigest digest;
        private long frames;
        private ComponentState state = ComponentState.CONFIGURED;
        private Capture() throws Exception { digest = MessageDigest.getInstance("SHA-256"); }
        public FrameDistributorDescriptor descriptor() { return new FrameDistributorDescriptor("synthetic", "Synthetic", "1", "Synthetic capture"); }
        public void start() { state = ComponentState.RUNNING; }
        public void distribute(AnnotatedFrame frame) { digest.update(frame.data()); frames++; }
        public DistributionSnapshot monitoringSnapshot() { return new DistributionSnapshot(state == ComponentState.RUNNING, true, frames, frames, 0, 0, 0, 0, UtcTime.nowNanos()); }
        public ComponentState state() { return state; }
        public void stop() { if (state != ComponentState.CLOSED) state = ComponentState.STOPPED; }
        public void close() { state = ComponentState.CLOSED; }
    }
}
