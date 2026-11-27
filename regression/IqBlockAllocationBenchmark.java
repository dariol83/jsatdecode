import com.sun.management.ThreadMXBean;
import eu.dariolucia.jsatdecode.api.source.IqBufferLease;
import eu.dariolucia.jsatdecode.api.source.IqBufferPool;
import eu.dariolucia.jsatdecode.api.source.IqSampleView;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;

import java.lang.management.ManagementFactory;
import java.util.Locale;
import java.util.function.Consumer;

/** Measures allocations for synchronous source publication and borrowed delivery. */
public final class IqBlockAllocationBenchmark {
    private static volatile LeasedSampleBlock published;
    private static volatile float checksum;
    private static final Consumer<IqSampleView> READER = view -> checksum = view.i(0) + view.q(0);
    private static final int WARMUP = 200_000, ITERATIONS = 500_000;

    private IqBlockAllocationBenchmark() { }

    public static void main(String[] args) {
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation accounting unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long thread = Thread.currentThread().threadId();
        try (IqBufferPool pool = new IqBufferPool(32_768)) {
            run(pool, WARMUP);
            for (int trial = 1; trial <= 3; trial++) {
                long before = bean.getThreadAllocatedBytes(thread);
                long start = System.nanoTime();
                run(pool, ITERATIONS);
                long elapsed = System.nanoTime() - start;
                long bytes = bean.getThreadAllocatedBytes(thread) - before;
                System.out.printf(Locale.ROOT, "trial=%d blocks=%d allocated_bytes=%d bytes_per_block=%.3f blocks_per_s=%.1f%n",
                        trial, ITERATIONS, bytes, (double) bytes / ITERATIONS, ITERATIONS * 1e9 / elapsed);
            }
            System.out.println("pool=" + pool.statistics());
            if (pool.statistics().outstandingLeases() != 0 || checksum != 0)
                throw new IllegalStateException("Incorrect pool lifetime or sample");
        }
    }

    private static void run(IqBufferPool pool, int blocks) {
        for (int n = 0; n < blocks; n++) {
            try (IqBufferLease lease = pool.tryAcquire(4096).orElseThrow()) {
                lease.put(0, 1, -1);
                lease.publish(0, 1);
                try (LeasedSampleBlock block = LeasedSampleBlock.from(lease, n, n, 500_000)) {
                    published = block; // Keep the public block observable across the call boundary.
                    block.withView(READER);
                }
            }
        }
    }
}
