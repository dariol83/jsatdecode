import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.decoder.Decoder;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.source.ManagedSampleSource;
import eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoder;
import eu.dariolucia.jsatdecode.hinode.HinodeDecoder;
import eu.dariolucia.jsatdecode.source.cs16.Cs16SampleSource;
import eu.dariolucia.jsatdecode.source.wav.WavSampleSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Warms the source and mission DSP on the first 8,388,608 samples, then runs
 * the unchanged full-recording baseline harness in the same JVM. Run this in
 * separate, alternating baseline/candidate processes for a matched comparison.
 */
public final class IqMissionWarmBenchmark {
    private static final long WARMUP_SAMPLES = 8_388_608;

    private IqMissionWarmBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !(args[0].equals("hinode") || args[0].equals("elektro"))) {
            throw new IllegalArgumentException("Expected: hinode|elektro recording new-output-frames.bin");
        }
        boolean hinode = args[0].equals("hinode");
        Path input = Path.of(args[1]).toAbsolutePath().normalize();
        int rate = hinode ? 500_000 : 1_000_000;
        Configuration settings = new Configuration(Map.of("file", input.toString(), "sample_rate", rate,
                "block_samples", 4096, "length", WARMUP_SAMPLES, "repeat", false, "realtime", false));
        try (Decoder decoder = hinode ? new HinodeDecoder(new Configuration(Map.of()))
                     : new ElektroLGgakDecoder(new Configuration(Map.of("sample_rate_hz", rate, "dcblocker", false)));
             ManagedSampleSource source = hinode ? new WavSampleSource(settings) : new Cs16SampleSource(settings)) {
            decoder.setFrameSink(ignored -> { });
            source.setSampleSink(decoder::accept);
            decoder.start();
            source.start();
            boolean completed = hinode ? ((WavSampleSource) source).awaitCompletion(Duration.ofMinutes(10))
                    : ((Cs16SampleSource) source).awaitCompletion(Duration.ofMinutes(10));
            if (!completed || source.failure().isPresent() || decoder.state() != ComponentState.RUNNING
                    || !Long.valueOf(WARMUP_SAMPLES).equals(source.monitoringSnapshot().values().get("source.samples"))) {
                throw new IllegalStateException("Warmup failed: " + source.failure());
            }
        }
        System.out.println("warmup_samples=" + WARMUP_SAMPLES + " mission=" + args[0]);
        System.out.println("measurement_begin");
        if (hinode) HinodeBaselineReplay.main(new String[]{args[1], args[2]});
        else ElektroBaselineReplay.main(new String[]{args[1], args[2]});
        System.out.println("measurement_end");
    }
}
