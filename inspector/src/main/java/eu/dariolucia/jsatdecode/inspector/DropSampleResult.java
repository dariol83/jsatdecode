package eu.dariolucia.jsatdecode.inspector;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/* Immutable management-thread result. Counts are complex samples, rate is
   complex samples/second, and durations below are milliseconds unless named. */
record DropSampleResult(int runId, int testDurationSeconds, int dataRate, int bufferSize,
                        double effectiveDurationMs, double theoreticalSamples, long measuredSamples,
                        double averageSamplesPerSecond, double droppedPercent, double estimatedLostSignalMs,
                        Status status, String detail, int evaluationReadSamples) {
    enum Status { COMPLETE, FAILED, CANCELLED }

    static final List<String> COLUMNS = List.of("Run ID", "Test duration (s)", "Data rate (samples/s)",
            "Buffer size (samples)", "Effective duration (ms)", "Theoretical # samples", "Measured # samples",
            "Average samples/s", "% dropped samples", "Estimated lost signal (ms)");

    DropSampleResult {
        Objects.requireNonNull(status);
        Objects.requireNonNull(detail);
        if (evaluationReadSamples < 0) throw new IllegalArgumentException("Negative evaluation read size");
    }

    // Callers without separate evaluation-read metadata still provide the full
    // measured count. This metadata never adjusts metrics or loss thresholds.
    DropSampleResult(int runId, int testDurationSeconds, int dataRate, int bufferSize,
                     double effectiveDurationMs, double theoreticalSamples, long measuredSamples,
                     double averageSamplesPerSecond, double droppedPercent, double estimatedLostSignalMs,
                     Status status, String detail) {
        this(runId, testDurationSeconds, dataRate, bufferSize, effectiveDurationMs, theoreticalSamples,
                measuredSamples, averageSamplesPerSecond, droppedPercent, estimatedLostSignalMs, status, detail, 0);
    }

    // Measured samples already include the evaluation read. Allow only 2 ms of
    // timing variation or 1000 ppm clock mismatch before flagging a deficit.
    double significanceAllowanceSamples() {
        return Math.max(dataRate * .002, theoreticalSamples * .001);
    }

    boolean significantLoss() {
        return status == Status.COMPLETE && theoreticalSamples - measuredSamples > significanceAllowanceSamples();
    }

    boolean attentionRequired() { return status == Status.FAILED || significantLoss(); }

    Object[] row() {
        return new Object[] {runId, testDurationSeconds, dataRate, bufferSize, effectiveDurationMs,
                theoreticalSamples, measuredSamples, averageSamplesPerSecond, droppedPercent, estimatedLostSignalMs};
    }

    static void writeCsv(Path file, List<DropSampleResult> results) throws IOException {
        try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) { writeCsv(writer, results); }
    }

    // Does not close the caller's writer; the UI owns creation/overwrite policy.
    static void writeCsv(Writer writer, List<DropSampleResult> results) throws IOException {
        for (String heading : COLUMNS) { field(writer, heading); writer.write(','); }
        writer.write("Status,Detail\r\n");
        for (DropSampleResult result : results) {
            for (Object value : result.row()) {
                if (!(value instanceof Double number) || Double.isFinite(number)) field(writer, String.valueOf(value));
                writer.write(',');
            }
            field(writer, result.status.name()); writer.write(','); field(writer, result.detail); writer.write("\r\n");
        }
    }

    private static void field(Writer writer, String text) throws IOException {
        boolean quote = text.indexOf(',') >= 0 || text.indexOf('"') >= 0 || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0;
        if (quote) writer.write('"');
        writer.write(text.replace("\"", "\"\""));
        if (quote) writer.write('"');
    }
}
