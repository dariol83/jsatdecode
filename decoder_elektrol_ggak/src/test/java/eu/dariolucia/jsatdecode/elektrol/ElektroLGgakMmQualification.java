package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.api.config.Configuration;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Isolated GNU Radio M&M finite-input, loop-state and interpolation qualification. */
public final class ElektroLGgakMmQualification {
    private ElektroLGgakMmQualification() {}

    /** Takes the mm_finite directory created by probe_mm_scheduler.py and optional report path. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Expected mm_finite-directory [report.json]");
        Path directory = Path.of(args[0]);
        Path reportFile = args.length == 2 ? Path.of(args[1]) : directory.resolve("java_qualification.json");
        Map<String, float[]> inputs = Map.of("zero", floats(directory.resolve("zero.cf32")),
                "driven", floats(directory.resolve("driven.cf32")));
        List<String> lines = Files.readAllLines(directory.resolve("manifest.csv"));
        int cases = 0, executions = 0, schedulerDependentCases = 0;
        double maxSampleError = 0., maxOmegaError = 0., maxMuError = 0.;
        Configuration configuration = new Configuration(Map.of());
        for (String line : lines.subList(1, lines.size())) {
            String[] fields = line.split(",");
            String waveform = fields[0];
            int samples = Integer.parseInt(fields[1]), expectedSymbols = Integer.parseInt(fields[6]);
            float expectedOmega = Float.parseFloat(fields[7]), expectedMu = Float.parseFloat(fields[8]);
            int minimumSymbols = Integer.parseInt(fields[10]), maximumSymbols = Integer.parseInt(fields[11]);
            if (minimumSymbols != maximumSymbols) schedulerDependentCases++;
            float[] input = inputs.get(waveform);
            float[] expected = floats(directory.resolve("expected-one-" + waveform + "-" + samples + ".cf32"));
            if (expected.length != expectedSymbols * 2) throw new AssertionError("Bad reference finite manifest");
            float[] firstPartitionOutput = null;
            for (int partition : new int[]{1, 7, 137, 4096}) {
                float[] actual = new float[expected.length + 16];
                int[] components = {0};
                ElektroLGgakDsp dsp = new ElektroLGgakDsp(ElektroLGgakSettings.read(configuration), new ElektroLGgakDsp.Output() {
                    @Override public void baseband(float i, float q) {}
                    @Override public void symbol(float i, float q) {
                        if (components[0] + 2 > actual.length) throw new AssertionError("Excess timing output");
                        actual[components[0]++] = i;
                        actual[components[0]++] = q;
                    }
                });
                for (int first = 0; first < samples; first += partition)
                    for (int n = first; n < Math.min(first + partition, samples); n++)
                        dsp.timingSample(input[n * 2], input[n * 2 + 1]);
                if (components[0] != expected.length)
                    throw new AssertionError("M&M count differs: " + waveform + " N=" + samples
                            + " expected=" + expectedSymbols + " actual=" + components[0] / 2);
                if (components[0] / 2 < minimumSymbols || components[0] / 2 > maximumSymbols)
                    throw new AssertionError("M&M count falls outside observed GNU scheduler range");
                float[] output = Arrays.copyOf(actual, components[0]);
                if (firstPartitionOutput != null && !Arrays.equals(firstPartitionOutput, output))
                    throw new AssertionError("M&M depends on partition " + partition);
                firstPartitionOutput = output;
                for (int n = 0; n < expected.length; n++)
                    maxSampleError = Math.max(maxSampleError, Math.abs(output[n] - expected[n]));
                maxOmegaError = Math.max(maxOmegaError, Math.abs(dsp.timingOmega() - expectedOmega));
                maxMuError = Math.max(maxMuError, Math.abs(dsp.timingMu() - expectedMu));
                executions++;
            }
            cases++;
        }
        String report = String.format(Locale.ROOT,
                "{\n  \"finite_cases\":%d,\n  \"java_partitions\":[1,7,137,4096],\n  \"java_executions\":%d,\n  \"gnu_scheduler_dependent_count_cases\":%d,\n  \"reference_scheduler\":\"explicit GNU M&M max_noutput_items=1\",\n  \"max_component_error\":%.12g,\n  \"max_omega_error\":%.12g,\n  \"max_mu_error\":%.12g\n}\n",
                cases, executions, schedulerDependentCases, maxSampleError, maxOmegaError, maxMuError);
        Files.writeString(reportFile, report);
        System.out.print(report);
        if (cases != 304 || maxSampleError > 2e-5 || maxOmegaError > 1e-5 || maxMuError > 1e-5)
            throw new AssertionError("Isolated M&M numerical qualification failed; see " + reportFile);
        System.out.println("PASS isolated M&M finite lengths, exact counts, loop state and partition independence");
    }

    private static float[] floats(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length > 1 << 20 || bytes.length % 4 != 0) throw new IllegalArgumentException("Invalid finite fixture");
        float[] result = new float[bytes.length / 4];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(result);
        return result;
    }
}
