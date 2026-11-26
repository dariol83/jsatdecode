package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.IqBufferLease;
import eu.dariolucia.jsatdecode.api.source.IqBufferPool;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/** Writes deterministic Inspector recording fixtures on baseline or leased paths. */
public final class IqRecorderEvidence {
    private IqRecorderEvidence() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[1].equals("legacy") || args[1].equals("leased")))
            throw new IllegalArgumentException("Expected: new-output-directory legacy|leased");
        Path folder = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectory(folder);
        float[] samples = {0, -0f, 0.5f, -0.5f, 1, -1, 2, -2, Float.POSITIVE_INFINITY,
                Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7fc01234),
                Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE,
                1 / 65536f, -1 / 65536f, 3 / 65536f, -3 / 65536f, Math.nextDown(1f), Math.nextUp(-1f)};
        for (RecordingFormat format : RecordingFormat.values()) {
            IqRecorder recorder = new IqRecorder(folder, 137_100_000, 500_000, 42,
                    Instant.parse("2026-11-03T11:22:33.123456789Z"), format, 4, 4096, 16,
                    path -> FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
            IqBufferPool pool = null;
            try {
                if (args[1].equals("legacy")) {
                    recorder.offer(new ComplexSampleBlock(samples, samples.length / 2, 0, 0, 500_000));
                } else {
                    // Reflection permits the same class file to run against the
                    // Step 1 baseline, which predates LeasedSampleBlock.
                    pool = new IqBufferPool(4096);
                    try (IqBufferLease lease = pool.tryAcquire(samples.length / 2).orElseThrow()) {
                        lease.putInterleaved(samples, 0, 0, samples.length / 2);
                        lease.publish(0, samples.length / 2);
                        Class<?> type = Class.forName("eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock");
                        try (AutoCloseable block = (AutoCloseable) type.getMethod("from", IqBufferLease.class,
                                long.class, long.class, double.class).invoke(null, lease, 0L, 0L, 500_000.0)) {
                            IqRecorder.class.getDeclaredMethod("offer", type).invoke(recorder, block);
                        }
                    }
                }
            } finally {
                if (pool != null) pool.close();
                recorder.close();
            }
            if (!recorder.complete() || recorder.failure() != null
                    || pool != null && pool.statistics().outstandingLeases() != 0)
                throw new IllegalStateException("Incomplete recording or outstanding lease");
            byte[] data = Files.readAllBytes(recorder.file());
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
            System.out.println("format=" + format + " bytes=" + data.length + " sha256=" + hash
                    + " outstanding_leases=" + (pool == null ? 0 : pool.statistics().outstandingLeases()));
        }
    }
}
