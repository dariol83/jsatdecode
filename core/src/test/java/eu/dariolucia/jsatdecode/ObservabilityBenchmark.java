package eu.dariolucia.jsatdecode;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
import eu.dariolucia.jsatdecode.core.monitoring.MetricsRegistry;
import java.util.Locale;

/**
 * Small reproducible smoke benchmark for scalar/bulk tap writes, primitive metric
 * updates and snapshot polling. No machine-dependent timing assertions.
 * This is not a decoder throughput, allocation profiler or live soak qualification.
 */
public final class ObservabilityBenchmark {
    private static volatile long consumed;
    private ObservabilityBenchmark() {}

    /** Runs warmup passes followed by measured fixed-workload passes.
     * @param args unused
     */
    public static void main(String[] args) {
        for (int warmup = 0; warmup < 3; warmup++) run(false);
        run(true);
        System.out.println("Benchmark checksum: " + consumed);
    }

    private static void run(boolean report) {
        // Allocate the fixed working set before timing producer operations.
        int samples = 1_000_000;
        ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(4096, 500000);
        MetricsRegistry registry = new MetricsRegistry(2);
        var count = registry.registerLong(
                new MonitoringDescriptor("source.samples", MonitoringType.LONG, "", "Sample count"), 0);
        var snr = registry.registerDouble(
                new MonitoringDescriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE, "dB", "SNR"), Double.NaN);
        var store = registry.build();
        float[] block = new float[8192];
        for (int n = 0; n < 4096; n++) {
            block[n * 2] = n;
            block[n * 2 + 1] = -n;
        }

        long start = System.nanoTime();
        // Measure scalar writes, large blocks and primitive updates separately.
        for (int n = 0; n < samples; n++) ring.write(n, -n);
        long scalarNanos = System.nanoTime() - start;
        consumed = ring.samplesWritten();

        start = System.nanoTime();
        for (int n = 0; n < 1024; n++) ring.write(block, 4096);
        long blockNanos = System.nanoTime() - start;
        consumed += ring.samplesWritten();

        start = System.nanoTime();
        for (int n = 0; n < samples; n++) {
            count.addAndGet(1);
            snr.set(n * 0.25);
        }
        long metricNanos = System.nanoTime() - start;
        consumed += count.get() + (long) snr.get();

        // Observer costs deliberately include immutable snapshot construction.
        start = System.nanoTime();
        for (int n = 0; n < 1000; n++) {
            consumed += ring.snapshot(4096).complexSamples();
            consumed += store.monitoringSnapshot().values().size();
        }
        long snapshotNanos = System.nanoTime() - start;
        if (report) {
            System.out.printf(Locale.ROOT,
                    "Observability benchmark (capacity 4096, 3 warmup passes):%n"
                    + "scalar: %.2f ns/sample; block(4096): %.2f ns/sample%n"
                    + "counter+SNR: %.2f ns/update pair; tap+metrics snapshot: %.2f us/poll%n",
                    scalarNanos / (double) samples, blockNanos / (1024.0 * 4096),
                    metricNanos / (double) samples, snapshotNanos / 1_000_000.0);
        }
    }
}
