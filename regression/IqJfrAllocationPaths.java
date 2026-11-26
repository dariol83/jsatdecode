import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.nio.file.Path;
import java.util.Locale;

/** Reports sampled float-array allocation weights at the audited source-copy sites. */
public final class IqJfrAllocationPaths {
    private IqJfrAllocationPaths() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) throw new IllegalArgumentException("Expected one or more JFR files");
        for (String path : args) {
            long floats = 0, floatBytes = 0, sourceCopies = 0, sourceCopyBytes = 0, pools = 0, poolBytes = 0;
            try (RecordingFile recording = new RecordingFile(Path.of(path))) {
                while (recording.hasMoreEvents()) {
                    RecordedEvent event = recording.readEvent();
                    if (!event.getEventType().getName().equals("jdk.ObjectAllocationSample")) continue;
                    RecordedClass type = event.getClass("objectClass");
                    if (type == null || !(type.getName().equals("[F") || type.getName().equals("float[]"))) continue;
                    long weight = event.getLong("weight");
                    floats++;
                    floatBytes += weight;
                    boolean block = false, source = false, pool = false;
                    RecordedStackTrace trace = event.getStackTrace();
                    if (trace != null) for (RecordedFrame frame : trace.getFrames()) {
                        String owner = frame.getMethod().getType().getName();
                        String method = frame.getMethod().getName();
                        if (owner.endsWith(".ComplexSampleBlock") && method.equals("<init>")) block = true;
                        if (owner.endsWith(".Cs16SampleSource") || owner.endsWith(".AbstractFileSampleSource")) source = true;
                        if (owner.endsWith(".IqBufferPool$Buffer") && method.equals("<init>")) pool = true;
                    }
                    if (block && source) { sourceCopies++; sourceCopyBytes += weight; }
                    if (pool) { pools++; poolBytes += weight; }
                }
            }
            System.out.printf(Locale.ROOT, "%s float_samples=%d float_weight_bytes=%d source_copy_samples=%d source_copy_weight_bytes=%d pool_allocation_samples=%d pool_weight_bytes=%d%n",
                    path, floats, floatBytes, sourceCopies, sourceCopyBytes, pools, poolBytes);
        }
    }
}
