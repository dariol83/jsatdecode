package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

/** Captures identical simulated IIOD input on baseline and leased source paths. */
public final class FmcommsRecordingEvidence {
    private FmcommsRecordingEvidence() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[1].equals("legacy") || args[1].equals("leased")))
            throw new IllegalArgumentException("Expected: new-output-directory legacy|leased");
        Path folder = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(folder);
        try (var server = new Fmcomms2SourceTest.Server(Fmcomms2SourceTest.xml("le:S12/16>>0", 0), (s, n, turn) -> {
            byte[] raw = turn == 0 ? new byte[]{1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8, 0}
                    : new byte[]{-1, -1, 0, -8};
            s.send(raw.length + "\n" + s.mask + "\n");
            s.fragment(raw);
            if (raw.length < n) s.send("0\n");
        }); var source = new Fmcomms2SampleSource(new Configuration(Map.of(
                "uri", "ip:127.0.0.1:" + server.port(), "buffer_size", 4, "block_samples", 2,
                "gain1", "manual", "manual_gain1", 42.5, "recording.enabled", true,
                "recording.folder", folder.toString(), "recording.name", "evidence")))) {
            if (args[1].equals("legacy")) {
                source.setSampleSink(block -> {
                    if (block.sampleIndex() + block.complexSamples() == 5) source.requestStop();
                });
            } else {
                // Reflection keeps this harness runnable with the Step 1 API.
                Class<?> sinkType = Class.forName("eu.dariolucia.jsatdecode.api.source.LeasedSampleSink");
                Object sink = Proxy.newProxyInstance(sinkType.getClassLoader(), new Class<?>[]{sinkType}, (proxy, method, callArgs) -> {
                    if (method.getName().equals("accept")) {
                        Object block = callArgs[0];
                        long index = (Long) block.getClass().getMethod("sampleIndex").invoke(block);
                        int count = (Integer) block.getClass().getMethod("complexSamples").invoke(block);
                        if (index + count == 5) source.requestStop();
                        return null;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
                source.getClass().getMethod("setLeasedSampleSink", sinkType).invoke(source, sink);
            }
            source.start();
            if (!source.awaitCompletion(Duration.ofSeconds(10))) throw new IllegalStateException("Source timed out");
            source.stop();
            if (source.failure().isPresent()) throw new IllegalStateException("Source failed", source.failure().orElseThrow());
            var metrics = source.monitoringSnapshot().values();
            if (!Boolean.TRUE.equals(metrics.get("source.recording.complete")) || !Long.valueOf(5).equals(metrics.get("source.samples")))
                throw new IllegalStateException("Source recording did not drain");
            Path file = Path.of((String) metrics.get("source.recording.file"));
            byte[] data = Files.readAllBytes(file);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            System.out.println("format=CF32 samples=5 bytes=" + data.length + " sha256=" + hash);
            if (args[1].equals("leased")) {
                var optional = (java.util.Optional<?>) source.getClass().getMethod("leasedStatistics").invoke(source);
                Object stats = optional.orElseThrow();
                Object pool = stats.getClass().getMethod("pool").invoke(stats);
                if ((Long) pool.getClass().getMethod("outstandingLeases").invoke(pool) != 0L)
                    throw new IllegalStateException("Source recording retained pooled samples");
                System.out.println("leased_diagnostics=" + stats);
            }
        }
    }
}
