import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Groups sampled allocations on the file-source processing threads by allocation site. */
public final class IqJfrHotspots {
    private IqJfrHotspots() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected JFR recordings");
        for (String arg : args) {
            Map<String, long[]> sites = new HashMap<>();
            long samples = 0, weight = 0;
            try (RecordingFile file = new RecordingFile(Path.of(arg))) {
                while (file.hasMoreEvents()) {
                    RecordedEvent event = file.readEvent();
                    if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) continue;
                    RecordedThread thread = event.getThread();
                    String name = thread == null ? null : thread.getJavaName();
                    if (name == null || !name.startsWith("jsatdecode-source-")) continue;
                    RecordedClass type = event.getClass("objectClass");
                    RecordedStackTrace trace = event.getStackTrace();
                    String site = "<no stack>";
                    if (trace != null && !trace.getFrames().isEmpty()) {
                        RecordedFrame frame = trace.getFrames().get(0);
                        site = frame.getMethod().getType().getName() + "." + frame.getMethod().getName()
                                + ":" + frame.getLineNumber();
                    }
                    String key = (type == null ? "<unknown>" : type.getName()) + " " + site;
                    long bytes = event.getLong("weight");
                    long[] counts = sites.computeIfAbsent(key, ignored -> new long[2]);
                    counts[0]++;
                    counts[1] += bytes;
                    samples++;
                    weight += bytes;
                }
            }
            System.out.printf(Locale.ROOT, "%s source_thread_samples=%d sampled_weight_bytes=%d%n", arg, samples, weight);
            List<Map.Entry<String, long[]>> sorted = new ArrayList<>(sites.entrySet());
            sorted.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[1]).reversed());
            for (Map.Entry<String, long[]> entry : sorted) {
                System.out.printf(Locale.ROOT, "  count=%d weight=%d %s%n", entry.getValue()[0], entry.getValue()[1], entry.getKey());
            }
        }
    }
}
