import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/**
 * Summarizes sampled allocation weights, GC pauses, and heap sizes observed at
 * GC checkpoints in one or more JFR recordings. Allocation weights are
 * statistical estimates, and a GC checkpoint maximum is not a true heap peak.
 */
public final class IqJfrSummary {
    private IqJfrSummary() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected one or more JFR files");
        for (String arg : args) summarize(Path.of(arg));
    }

    private static void summarize(Path file) throws Exception {
        long allocationSamples = 0, sampledBytes = 0, gcCount = 0, pauseNanos = 0, maxHeapAtGc = 0;
        Instant first = null, last = null;
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                Instant start = event.getStartTime(), end = event.getEndTime();
                if (first == null || start.isBefore(first)) first = start;
                if (last == null || end.isAfter(last)) last = end;
                switch (event.getEventType().getName()) {
                    case "jdk.ObjectAllocationSample" -> {
                        allocationSamples++;
                        sampledBytes = Math.addExact(sampledBytes, event.getLong("weight"));
                    }
                    case "jdk.GarbageCollection" -> {
                        gcCount++;
                        pauseNanos = Math.addExact(pauseNanos, event.getDuration("sumOfPauses").toNanos());
                    }
                    case "jdk.GCHeapSummary" -> maxHeapAtGc = Math.max(maxHeapAtGc, event.getLong("heapUsed"));
                    default -> { }
                }
            }
        }
        if (first == null || last == null) throw new IllegalStateException("No recorded events: " + file);
        double seconds = Duration.between(first, last).toNanos() / 1e9;
        if (!(seconds > 0)) throw new IllegalStateException("Invalid recording duration: " + file);
        System.out.printf(Locale.ROOT,
                "%s event_span_s=%.6f allocation_samples=%d sampled_weight_bytes=%d estimated_bytes_per_s=%.0f gc_events=%d gc_pause_ms=%.6f max_heap_at_gc_bytes=%d%n",
                file, seconds, allocationSamples, sampledBytes, sampledBytes / seconds, gcCount, pauseNanos / 1e6, maxHeapAtGc);
    }
}
