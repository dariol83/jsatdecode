package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.zip.CRC32C;

/**
 * Opt-in paired mock-IIOD/dummy-decoder recording benchmark. Writes only uniquely
 * named files in the supplied folder and deletes each benchmark recording after
 * verification. No hardware, mission DSP, fsync or disk-cache qualification is implied.
 */
public final class Cf32RecordingBenchmark {
    private static final int BUFFER_SAMPLES = 65536;
    private static final com.sun.management.ThreadMXBean THREADS =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    private static int sequence;

    private Cf32RecordingBenchmark() { }

    /**
     * Runs warmup and alternating recording OFF/ON pairs with identical sample
     * bytes, dummy decoder and CRC consumer. Reports acquisition throughput and
     * allocation independently of final recording drain.
     *
     * @param args output folder, optional buffers/run (default 512), pairs (5),
     *             producer pacing samples/s (0 means unpaced)
     * @throws Exception on malformed arguments, sample loss, recording failure or I/O error
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4)
            throw new IllegalArgumentException("folder [buffers=512] [pairs=5] [pace_sps=0]");
        Path folder = Path.of(args[0]).toAbsolutePath().normalize();
        int buffers = args.length > 1 ? Integer.parseInt(args[1]) : 512;
        int pairs = args.length > 2 ? Integer.parseInt(args[2]) : 5;
        double pace = args.length > 3 ? Double.parseDouble(args[3]) : 0;
        if (buffers < 2 || pairs < 1 || !Double.isFinite(pace) || pace < 0)
            throw new IllegalArgumentException("Positive buffers/pairs and finite nonnegative pacing required");
        Files.createDirectories(folder);
        if (THREADS.isThreadAllocatedMemorySupported()) THREADS.setThreadAllocatedMemoryEnabled(true);
        if (THREADS.isCurrentThreadCpuTimeSupported()) THREADS.setThreadCpuTimeEnabled(true);
        System.out.printf(Locale.ROOT, "CF32 benchmark: JDK %s, %d samples/run, %d pairs, pace %.0f sps%n",
                System.getProperty("java.version"), buffers * (long) BUFFER_SAMPLES, pairs, pace);
        for (int i = 0; i < 4; i++) run(folder, Math.min(buffers, 64), (i & 1) != 0, pace);
        double[] off = new double[pairs], on = new double[pairs], overhead = new double[pairs];
        for (int i = 0; i < pairs; i++) {
            Result a, b;
            if ((i & 1) == 0) { a = run(folder, buffers, false, pace); b = run(folder, buffers, true, pace); }
            else { b = run(folder, buffers, true, pace); a = run(folder, buffers, false, pace); }
            if (a.checksum != b.checksum) throw new AssertionError("Recording changed deterministic decoder output");
            off[i] = a.producerSeconds;
            on[i] = b.producerSeconds;
            overhead[i] = 100 * (1 - a.producerSeconds / b.producerSeconds);
            System.out.printf(Locale.ROOT,
                    "Pair %d OFF/ON: %.3f/%.3f Msps, producer CPU %.3f/%.3f s, allocated %.2f/%.2f B/sample, GC %d/%d ms, " +
                    "queue peak %.2f MiB, completed %.3f s, end-to-end write %.1f MB/s, CRC %08x%n",
                    i + 1, a.samples / a.producerSeconds / 1e6, b.samples / b.producerSeconds / 1e6,
                    a.cpuSeconds, b.cpuSeconds, a.allocatedBytes / (double) a.samples, b.allocatedBytes / (double) b.samples,
                    a.gcMillis, b.gcMillis, b.highWater / 1048576.0, b.completionSeconds,
                    b.samples * 8 / b.completionSeconds / 1e6, b.checksum);
        }
        System.out.printf(Locale.ROOT, "Median producer OFF %.3f s, ON %.3f s; median paired throughput loss %.2f%% (target <=2%%).%n",
                median(off), median(on), median(overhead));
        System.out.println("Mock transport/dummy decoder only; repeat long runs on the intended disk and decoder. Filesystem caching and live hardware remain unqualified.");
    }

    private record Result(long samples, double producerSeconds, double completionSeconds, double cpuSeconds,
                          long allocatedBytes, long gcMillis, long highWater, long checksum) { }

    private static Result run(Path folder, int buffers, boolean enabled, double pace) throws Exception {
        byte[] raw = new byte[BUFFER_SAMPLES * 4];
        ByteBuffer shorts = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < BUFFER_SAMPLES; i++) {
            shorts.putShort((short) ((i * 17 & 4095) - 2048));
            shorts.putShort((short) ((i * 31 & 4095) - 2048));
        }
        String xml = Fmcomms2SourceTest.xml("le:S12/16>>0", 0).replace("<scan-element index='0'",
                "<attribute name='sampling_frequency'/><scan-element index='0'");
        long[] timings = new long[4];
        AtomicLong streamStart = new AtomicLong();
        long[] received = {0};
        CRC32C crc = new CRC32C();
        long total = buffers * (long) BUFFER_SAMPLES;
        long gc = gcMillis();
        Path[] file = {null};
        try (var server = new Fmcomms2SourceTest.Server(xml, (s,n,t) -> {
            if (t == 0) streamStart.set(System.nanoTime());
            if (pace > 0) {
                long target = streamStart.get() + (long) ((t + 1) * (double) BUFFER_SAMPLES / pace * 1e9);
                for (long left; (left = target - System.nanoTime()) > 0;) LockSupport.parkNanos(Math.min(left, 1_000_000));
            }
            s.send(n + "\n" + s.mask + "\n");
            s.output.write(raw);
            s.output.flush();
        }); var source = new Fmcomms2SampleSource(new Configuration(Map.of("uri", "ip:127.0.0.1:" + server.port(),
                "sample_rate", 4000000, "fpga_decimation", 8, "buffer_size", BUFFER_SAMPLES, "block_samples", 4096,
                "recording.enabled", enabled, "recording.folder", folder.toString(), "recording.name", "bench-" + System.nanoTime() + "-" + sequence++)));
             var decoder = new DummyDecoder()) {
            server.writes.put("iio:device1 INPUT voltage0 sampling_frequency", new byte[]{'4','0','0','0','0','0','0',0});
            decoder.setFrameSink(frame -> crc.update(frame.data()));
            decoder.start();
            source.setSampleSink(block -> {
                if (received[0] == 0) {
                    timings[0] = streamStart.get();
                    timings[2] = allocated();
                    timings[3] = THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0;
                }
                decoder.accept(block);
                received[0] += block.complexSamples();
                if (received[0] == total) {
                    timings[1] = System.nanoTime();
                    timings[2] = allocated() - timings[2];
                    timings[3] = (THREADS.isCurrentThreadCpuTimeSupported() ? THREADS.getCurrentThreadCpuTime() : 0) - timings[3];
                    source.requestStop();
                }
            });
            source.start();
            try {
                if (!source.awaitCompletion(Duration.ofMinutes(30))) throw new AssertionError("Benchmark deadline exceeded");
            } finally {
                source.stop();
                var status = source.monitoringSnapshot().values();
                String destination = (String) status.get("source.recording.file");
                if (!destination.isEmpty()) file[0] = Path.of(destination);
            }
            long completed = System.nanoTime();
            var status = source.monitoringSnapshot().values();
            if (source.failure().isPresent() || received[0] != total || enabled && !status.get("source.recording.complete").equals(true))
                throw new AssertionError("Incomplete benchmark: " + status);
            if (enabled && Files.size(file[0]) != total * 8) throw new AssertionError("Incorrect recording length");
            return new Result(total, (timings[1] - timings[0]) * 1e-9, (completed - timings[0]) * 1e-9,
                    timings[3] * 1e-9, timings[2], gcMillis() - gc,
                    (Long) status.get("source.recording.queue_high_water_bytes"), crc.getValue());
        } finally {
            if (file[0] != null) Files.deleteIfExists(file[0]);
        }
    }

    private static long allocated() {
        return THREADS.isThreadAllocatedMemorySupported() ? THREADS.getThreadAllocatedBytes(Thread.currentThread().threadId()) : 0;
    }

    private static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum();
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted.length % 2 == 0 ? (sorted[sorted.length / 2 - 1] + sorted[sorted.length / 2]) / 2 : sorted[sorted.length / 2];
    }
}
