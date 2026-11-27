import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Adds sampled heap and source-thread allocation observations to the warmed replay. */
public final class IqMissionWarmHeapBenchmark {
    private IqMissionWarmHeapBenchmark() { }

    public static void main(String[] args) throws Exception {
        ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!threads.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation accounting unavailable");
        threads.setThreadAllocatedMemoryEnabled(true);
        AtomicBoolean done = new AtomicBoolean();
        AtomicLong peak = new AtomicLong();
        Map<Long, Long> sourceAllocations = new TreeMap<>();
        Thread sampler = new Thread(() -> {
            while (!done.get()) {
                peak.accumulateAndGet(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(), Math::max);
                long[] ids = threads.getAllThreadIds();
                ThreadInfo[] info = threads.getThreadInfo(ids);
                long[] bytes = threads.getThreadAllocatedBytes(ids);
                for (int i = 0; i < ids.length; i++) {
                    if (info[i] != null && info[i].getThreadName().startsWith("jsatdecode-source-") && bytes[i] >= 0)
                        sourceAllocations.put(ids[i], bytes[i]);
                }
                try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            }
        }, "iq-benchmark-sampler");
        sampler.setDaemon(true);
        sampler.start();
        try {
            IqMissionWarmBenchmark.main(args);
        } finally {
            done.set(true);
            sampler.join();
            System.out.println("sampled_peak_heap_bytes=" + peak.get() + " sampling_period_ms=10");
            System.out.println("sampled_source_thread_allocated_bytes=" + sourceAllocations);
        }
    }
}
