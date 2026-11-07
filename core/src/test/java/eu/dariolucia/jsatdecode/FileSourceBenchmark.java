package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.source.cf32.Cf32SampleSource;
import eu.dariolucia.jsatdecode.source.file.AbstractFileSampleSource;
import eu.dariolucia.jsatdecode.source.wav.WavSampleSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Diagnostic file-source throughput benchmark, without machine-dependent pass thresholds. */
public final class FileSourceBenchmark {
    private FileSourceBenchmark() { }

    /** Generates a bounded recording, warms each format, measures delivery and removes fixtures.
     * @param args unused
     * @throws Exception for fixture or source failures
     */
    public static void main(String[] args) throws Exception {
        Path directory = FileSourceFixtures.temporaryDirectory("benchmark-");
        try {
            int samples = 1_048_576;
            float[] iq = FileSourceFixtures.samples(samples);
            Path cf32 = directory.resolve("benchmark.cf32"), wav = directory.resolve("benchmark.wav");
            Files.write(cf32, FileSourceFixtures.floats(iq));
            Files.write(wav, FileSourceFixtures.wav(iq, 500000));
            for (boolean wave : new boolean[]{false, true}) {
                for (int warmup = 0; warmup < 3; warmup++) run(wave, wave ? wav : cf32, samples, false);
                run(wave, wave ? wav : cf32, samples, true);
            }
        } finally { FileSourceFixtures.clean(directory); }
    }

    private static void run(boolean wave, Path path, int samples, boolean report) throws Exception {
        Configuration configuration = new Configuration(Map.of("file", path.toString(),
                "sample_rate", 500000, "block_samples", 4096));
        AtomicLong checksum = new AtomicLong();
        // Include startup, reads, little-endian conversion, immutable blocks and sink delivery.
        try (AbstractFileSampleSource source = wave ? new WavSampleSource(configuration) : new Cf32SampleSource(configuration)) {
            source.setSampleSink(block -> checksum.addAndGet(
                    Float.floatToRawIntBits(block.i(0)) + (long) Float.floatToRawIntBits(block.q(block.complexSamples() - 1))));
            long start = System.nanoTime();
            source.start();
            if (!source.awaitCompletion(Duration.ofSeconds(30))) throw new AssertionError("Benchmark timed out");
            source.stop();
            long elapsed = System.nanoTime() - start;
            if (source.failure().isPresent()) throw new AssertionError(source.failure().orElseThrow());
            if (!source.monitoringSnapshot().values().get("source.samples").equals((long) samples)) {
                throw new AssertionError("Benchmark sample count changed");
            }
            if (report) {
                System.out.printf(Locale.ROOT, "%s file (%d samples, blocks 4096, 3 warmups): %.2f ms, %.2f Msamples/s, checksum %d%n",
                        wave ? "WAV" : "CF32", samples, elapsed / 1e6, samples * 1000.0 / elapsed, checksum.get());
            }
        }
    }
}