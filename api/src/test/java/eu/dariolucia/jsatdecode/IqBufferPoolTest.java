package eu.dariolucia.jsatdecode;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.IqBufferLease;
import eu.dariolucia.jsatdecode.api.source.IqBufferPool;
import eu.dariolucia.jsatdecode.api.source.IqReadAccess;
import eu.dariolucia.jsatdecode.api.source.IqWriteAccess;
import eu.dariolucia.jsatdecode.api.source.IqSampleView;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Dependency-free lifecycle, capacity and concurrency checks for the opt-in pool. */
public final class IqBufferPoolTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("oom-child")) {
            allocationFailureChild();
            return;
        }
        ownershipAndAdapter();
        scopedAccess();
        sharedBlockOwnership();
        sharedBlockConcurrentChildren();
        nonblockingBlockRetention();
        sizeClassesAndBudget();
        invalidOperations();
        allocationFailure();
        cancellationAndShutdown();
        publicationRace();
        retainCloseRace();
        for (long seed : new long[]{0x1a2b3cL, 0x54a71L, 0x7f00cafeL}) {
            concurrentRetain(seed);
            concurrentAcquisition(seed);
        }
        System.out.println("IQ buffer pool: " + checks + " checks passed (three fixed concurrency seeds)");
    }

    private static void nonblockingBlockRetention() throws Exception {
        IqBufferPool pool = new IqBufferPool(8);
        IqBufferLease lease = acquire(pool, 1);
        lease.publish(0, 1);
        LeasedSampleBlock root = LeasedSampleBlock.from(lease, 0, 0, 1);
        lease.close();
        var field = IqBufferPool.class.getDeclaredField("lock");
        field.setAccessible(true);
        Object poolLock = field.get(pool);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            synchronized (poolLock) {
                Future<?> retainAndReject = executor.submit(() -> {
                    try (LeasedSampleBlock rejected = root.retain()) {
                        require(rejected.complexSamples() == 1, "Retained metadata changed");
                    }
                });
                retainAndReject.get(2, TimeUnit.SECONDS);
            }
        } finally {
            root.close();
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Nonblocking retain worker did not stop");
        }
        assertDrained(pool);
    }

    private static void scopedAccess() throws Exception {
        IqBufferPool pool = new IqBufferPool(32);
        IqBufferLease writer = acquire(pool, 4);
        IqWriteAccess[] escapedWriter = new IqWriteAccess[1];
        writer.withWriteAccess(access -> {
            escapedWriter[0] = access;
            check(access.capacity() == 4);
            access.put(1, Float.intBitsToFloat(0x7fc01234), -0.0f);
            expect(IllegalStateException.class, () -> writer.publish(0, 1));
            expect(IllegalStateException.class, writer::close);
            expect(IllegalStateException.class, () -> writer.withWriteAccess(ignored -> { }));
        });
        expect(IllegalStateException.class, () -> escapedWriter[0].put(1, 3, 4));
        expect(IllegalArgumentException.class, () -> writer.withWriteAccess(access -> { throw new IllegalArgumentException(); }));
        writer.publish(1, 1);
        IqReadAccess[] escapedReader = new IqReadAccess[1];
        writer.withReadAccess(access -> {
            escapedReader[0] = access;
            check(access.complexSamples() == 1 && Float.floatToRawIntBits(access.i(0)) == 0x7fc01234);
            check(Float.floatToRawIntBits(access.q(0)) == 0x80000000);
            writer.close();
            check(pool.tryAcquire(4).isEmpty());
            check(Float.floatToRawIntBits(access.i(0)) == 0x7fc01234);
        });
        expect(IllegalStateException.class, () -> escapedReader[0].i(0));
        check(pool.statistics().outstandingLeases() == 0);
        try (IqBufferLease next = acquire(pool, 4)) {
            next.withWriteAccess(access -> access.put(0, 9, -9));
            next.publish(0, 1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                next.withReadAccess(access -> {
                    Future<?> future = executor.submit(() -> expect(IllegalStateException.class, () -> access.i(0)));
                    try { future.get(10, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new AssertionError(e); }
                });
            } finally {
                executor.shutdownNow();
                require(executor.awaitTermination(10, TimeUnit.SECONDS), "Scope worker did not stop");
            }
        }
        assertDrained(pool);
    }

    private static void sharedBlockConcurrentChildren() throws Exception {
        IqBufferPool pool = new IqBufferPool(8);
        IqBufferLease lease = acquire(pool, 1);
        lease.put(0, 17, -17);
        lease.publish(0, 1);
        LeasedSampleBlock root = LeasedSampleBlock.from(lease, 9, 0, 1);
        lease.close();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> tasks = new ArrayList<>();
        try {
            for (int n = 0; n < 4; n++) {
                LeasedSampleBlock anchor = root.retain();
                tasks.add(executor.submit(() -> {
                    try (anchor) {
                        require(go.await(10, TimeUnit.SECONDS), "Child-retain race did not start");
                        for (int j = 0; j < 5000; j++) {
                            try (LeasedSampleBlock child = anchor.retain()) {
                                child.withView(view -> require(view.i(0) == 17 && view.q(0) == -17
                                        && view.sampleIndex() == 9, "Child claim lost its data"));
                            }
                        }
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                }));
            }
            root.close();
            pool.close();
            go.countDown();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            go.countDown();
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Child-retain workers did not stop");
        }
        assertDrained(pool);
    }

    private static void sharedBlockOwnership() throws Exception {
        IqBufferPool pool = new IqBufferPool(16);
        IqBufferLease writer = acquire(pool, 2);
        writer.put(1, 123, -123);
        writer.publish(1, 1);
        expect(IllegalArgumentException.class, () -> LeasedSampleBlock.from(writer, -1, 0, 1));
        expect(IllegalArgumentException.class, () -> LeasedSampleBlock.from(writer, 0, 0, Double.NaN));
        check(pool.statistics().outstandingLeases() == 1);
        LeasedSampleBlock first = LeasedSampleBlock.from(writer, 7, -3, 48_000);
        writer.close();
        LeasedSampleBlock retained = first.retain();
        first.close();
        expect(IllegalStateException.class, first::retain);
        check(pool.tryAcquire(2).isEmpty());
        try (LeasedSampleBlock grandchild = retained.retain()) {
            grandchild.withView(view -> check(view.i(0) == 123 && view.sampleIndex() == 7));
            check(pool.statistics().outstandingLeases() == 1);
        }
        IqSampleView[] escaped = new IqSampleView[1];
        retained.withView(view -> {
            escaped[0] = view;
            check(view.i(0) == 123 && view.q(0) == -123 && view.sampleIndex() == 7);
            check(view.timestampNanos() == -3 && view.sampleRate() == 48_000);
        });
        expect(IllegalStateException.class, () -> escaped[0].sampleIndex());
        expect(IllegalArgumentException.class, () -> retained.withView(view -> { throw new IllegalArgumentException(); }));
        check(pool.statistics().outstandingLeases() == 1);
        ComplexSampleBlock legacy = retained.toComplexSampleBlock();
        pool.close();
        retained.withView(view -> check(view.i(0) == 123));
        retained.close();
        expect(IllegalStateException.class, retained::close);
        check(legacy.i(0) == 123 && pool.statistics().outstandingLeases() == 0);
        assertDrained(pool);

        IqBufferPool racePool = new IqBufferPool(8);
        IqBufferLease lease = acquire(racePool, 1);
        lease.put(0, 17, -17);
        lease.publish(0, 1);
        LeasedSampleBlock root = LeasedSampleBlock.from(lease, 0, 0, 1);
        lease.close();
        ExecutorService executor = Executors.newFixedThreadPool(4);
        List<Future<?>> tasks = new ArrayList<>();
        CountDownLatch go = new CountDownLatch(1);
        try {
            for (int n = 0; n < 4; n++) tasks.add(executor.submit(() -> {
                try {
                    require(go.await(10, TimeUnit.SECONDS), "Shared-block race did not start");
                    for (int j = 0; j < 5000; j++) {
                        LeasedSampleBlock child;
                        try { child = root.retain(); } catch (IllegalStateException closed) { break; }
                        try (child) { child.withView(view -> require(view.i(0) == 17 && view.q(0) == -17, "Recycled shared block")); }
                    }
                } catch (InterruptedException e) { throw new AssertionError(e); }
            }));
            go.countDown();
            root.close();
            for (Future<?> task : tasks) task.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Shared-block workers did not stop");
        }
        assertDrained(racePool);
    }

    private static void ownershipAndAdapter() {
        IqBufferPool pool = new IqBufferPool(64);
        IqBufferLease writer = acquire(pool, 5);
        check(writer.capacity() == 8 && !writer.isReadOnly());
        float payload = Float.intBitsToFloat(0x7fc01234);
        writer.put(0, 11, 12);
        writer.putInterleaved(new float[]{99, payload, -0.0f, 3, -3, 88}, 1, 2, 2);
        expect(IllegalStateException.class, writer::retain);
        expect(IllegalStateException.class, () -> writer.i(0));
        expect(IndexOutOfBoundsException.class, () -> writer.publish(7, 2));
        check(!writer.isReadOnly());
        writer.publish(2, 2);
        check(writer.isReadOnly() && writer.offsetSamples() == 2 && writer.complexSamples() == 2);
        check(Float.floatToRawIntBits(writer.i(0)) == 0x7fc01234);
        check(Float.floatToRawIntBits(writer.q(0)) == 0x80000000);
        check(writer.i(1) == 3 && writer.q(1) == -3);
        expect(IndexOutOfBoundsException.class, () -> writer.i(2));
        expect(IllegalStateException.class, () -> writer.put(2, 1, 2));
        expect(IllegalStateException.class, () -> writer.putInterleaved(new float[2], 0, 0, 1));
        expect(IllegalStateException.class, () -> writer.publish(0, 1));
        ComplexSampleBlock block = writer.toComplexSampleBlock(37, -123, 48_000);
        check(block.sampleIndex() == 37 && block.timestampNanos() == -123 && block.sampleRate() == 48_000);
        check(block.complexSamples() == 2 && Float.floatToRawIntBits(block.i(0)) == 0x7fc01234);
        block.iq()[2] = 999;
        check(block.i(1) == 3);
        expect(IllegalArgumentException.class, () -> writer.toComplexSampleBlock(-1, 0, 1));
        IqBufferLease retained = writer.retain();
        check(pool.statistics().outstandingLeases() == 2 && pool.statistics().activeBytes() == 64);
        writer.close();
        expect(IllegalStateException.class, writer::close);
        check(retained.i(1) == 3 && pool.statistics().outstandingLeases() == 1);
        check(pool.tryAcquire(1).isEmpty());
        retained.close();
        check(pool.statistics().outstandingLeases() == 0 && pool.statistics().retainedBytes() == 64);
        try (IqBufferLease reused = acquire(pool, 5)) {
            reused.put(3, 42, 43);
            reused.publish(3, 1);
            check(reused.i(0) == 42 && block.i(1) == 3);
        }
        check(pool.statistics().reuses() == 1 && pool.statistics().allocations() == 1);
        assertDrained(pool);
    }

    private static void sizeClassesAndBudget() {
        IqBufferPool pool = new IqBufferPool(64);
        IqBufferLease first = acquire(pool, 3); // four samples, 32 bytes
        first.put(0, 101, -101);
        first.publish(0, 1);
        IqBufferLease second = acquire(pool, 3);
        second.put(0, 202, -202);
        second.publish(0, 1);
        check(first.i(0) == 101 && second.i(0) == 202);
        check(pool.tryAcquire(1).isEmpty());
        first.close();
        check(pool.statistics().activeBytes() == 32 && pool.statistics().retainedBytes() == 32);
        // No idle class of this size: evict the idle 32-byte array and allocate 8 bytes.
        IqBufferLease small = acquire(pool, 0);
        check(small.capacity() == 1 && pool.statistics().activeBytes() == 40);
        small.publish(1, 0);
        check(small.offsetSamples() == 1 && small.complexSamples() == 0);
        expect(IndexOutOfBoundsException.class, () -> small.i(0));
        small.close();
        check(second.i(0) == 202);
        second.close();
        check(pool.statistics().retainedBytes() == 40 && pool.statistics().peakReservedBytes() == 64);
        try (IqBufferLease reused = acquire(pool, 4)) {
            check(reused.capacity() == 4 && pool.statistics().reuses() == 1);
        }
        check(pool.statistics().peakActiveBytes() == 64 && pool.statistics().peakRetainedBytes() <= 64);
        assertDrained(pool);
    }

    private static void invalidOperations() {
        expect(IllegalArgumentException.class, () -> new IqBufferPool(-1));
        IqBufferPool empty = new IqBufferPool(7);
        check(empty.tryAcquire(0).isEmpty() && empty.statistics().exhaustions() == 1);
        expect(IllegalArgumentException.class, () -> empty.tryAcquire(-1));
        expect(IllegalArgumentException.class, () -> empty.tryAcquire(Integer.MAX_VALUE));
        check(empty.statistics().outstandingLeases() == 0 && empty.statistics().allocations() == 0);
        empty.close();
        expect(IllegalStateException.class, () -> empty.tryAcquire(0));

        IqBufferPool pool = new IqBufferPool(32);
        try (IqBufferLease lease = acquire(pool, 4)) {
            expect(NullPointerException.class, () -> lease.putInterleaved(null, 0, 0, 1));
            expect(IndexOutOfBoundsException.class, () -> lease.put(-1, 1, 2));
            expect(IndexOutOfBoundsException.class, () -> lease.putInterleaved(new float[2], Integer.MAX_VALUE, 0, 1));
            expect(IndexOutOfBoundsException.class, () -> lease.putInterleaved(new float[2], 0, Integer.MAX_VALUE, 1));
            expect(IndexOutOfBoundsException.class, () -> lease.putInterleaved(new float[2], 0, 0, -1));
            expect(IndexOutOfBoundsException.class, () -> lease.publish(-1, 0));
            lease.put(0, 7, 8);
            lease.publish(0, 1);
            check(lease.i(0) == 7 && lease.q(0) == 8);
        }
        IqBufferLease closed = acquire(pool, 4);
        closed.close();
        expect(IllegalStateException.class, closed::close);
        expect(IllegalStateException.class, closed::capacity);
        expect(IllegalStateException.class, closed::isReadOnly);
        expect(IllegalStateException.class, closed::retain);
        expect(IllegalStateException.class, closed::offsetSamples);
        expect(IllegalStateException.class, closed::complexSamples);
        expect(IllegalStateException.class, () -> closed.i(0));
        expect(IllegalStateException.class, () -> closed.q(0));
        expect(IllegalStateException.class, () -> closed.put(0, 1, 2));
        expect(IllegalStateException.class, () -> closed.publish(0, 0));
        expect(IllegalStateException.class, () -> closed.toComplexSampleBlock(0, 0, 1));
        check(pool.statistics().invalidReleases() == 1);
        assertDrained(pool);
    }

    private static void allocationFailure() throws Exception {
        String testClasses = Path.of(IqBufferPoolTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String mainClasses = Path.of(IqBufferPool.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        if (!Files.exists(javaExecutable)) javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java");
        Process process = new ProcessBuilder(javaExecutable.toString(), "-Xms16m", "-Xmx32m", "-cp",
                testClasses + java.io.File.pathSeparator + mainClasses, IqBufferPoolTest.class.getName(), "oom-child")
                .redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new AssertionError("Allocation-failure child timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.exitValue() == 0 && output.contains("allocation failure accounted"), "Allocation-failure child: " + output);
        checks++;
    }

    private static void allocationFailureChild() {
        IqBufferPool pool = new IqBufferPool(256L * 1024 * 1024);
        try (IqBufferLease lease = acquire(pool, 1)) { lease.publish(0, 0); }
        try {
            pool.tryAcquire(1 << 25);
            throw new AssertionError("Expected an out-of-memory error");
        } catch (OutOfMemoryError expected) {
            require(pool.statistics().outstandingLeases() == 0 && pool.statistics().activeBytes() == 0
                    && pool.statistics().retainedBytes() == 0 && pool.statistics().allocations() == 1,
                    "Failed allocation or eviction changed accounting");
        }
        try (IqBufferLease lease = acquire(pool, 1)) { lease.publish(0, 0); }
        require(pool.statistics().outstandingLeases() == 0, "Lease leaked after allocation failure");
        pool.close();
        System.out.println("allocation failure accounted");
    }

    private static void cancellationAndShutdown() throws Exception {
        IqBufferPool pool = new IqBufferPool(32);
        try {
            try (IqBufferLease writer = acquire(pool, 4)) {
                writer.put(0, 42, -42);
                throw new IllegalArgumentException("simulated acquisition error");
            }
        } catch (IllegalArgumentException expected) { check(pool.statistics().outstandingLeases() == 0); }

        IqBufferLease parent = acquire(pool, 4);
        parent.put(0, 9, -9);
        parent.publish(0, 1);
        IqBufferLease taskLease = parent.retain();
        CountDownLatch ready = new CountDownLatch(1), finished = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> task = executor.submit(() -> {
                try (taskLease) {
                    ready.countDown();
                    new CountDownLatch(1).await();
                } catch (InterruptedException expected) {
                    Thread.currentThread().interrupt();
                } finally { finished.countDown(); }
            });
            check(ready.await(10, TimeUnit.SECONDS));
            parent.close();
            pool.close();
            check(pool.statistics().activeBytes() == 32 && pool.statistics().outstandingLeases() == 1);
            check(taskLease.i(0) == 9);
            try (IqBufferLease another = taskLease.retain()) { check(another.q(0) == -9); }
            expect(IllegalStateException.class, () -> pool.tryAcquire(1));
            check(task.cancel(true));
            check(finished.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Cancellation worker did not stop");
        }
        pool.close();
        check(pool.statistics().closed() && pool.statistics().activeBytes() == 0 && pool.statistics().retainedBytes() == 0);
        assertDrained(pool);
    }

    private static void publicationRace() throws Exception {
        IqBufferPool pool = new IqBufferPool(8);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (IqBufferLease lease = acquire(pool, 1)) {
            CountDownLatch writing = new CountDownLatch(1);
            Future<?> writer = executor.submit(() -> {
                for (int n = 1; ; n++) {
                    try {
                        lease.put(0, n, -n);
                        writing.countDown();
                    } catch (IllegalStateException expected) {
                        return;
                    }
                }
            });
            check(writing.await(10, TimeUnit.SECONDS));
            lease.publish(0, 1);
            float i = lease.i(0), q = lease.q(0);
            writer.get(10, TimeUnit.SECONDS);
            check(i == -q && lease.i(0) == i && lease.q(0) == q);
            expect(IllegalStateException.class, () -> lease.put(0, 99, -99));
        } finally {
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Publication worker did not stop");
        }
        assertDrained(pool);
    }

    private static void retainCloseRace() throws Exception {
        int workers = 6;
        IqBufferPool pool = new IqBufferPool(8);
        IqBufferLease shared = acquire(pool, 1);
        shared.put(0, 17, -17);
        shared.publish(0, 1);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers), race = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < workers; w++) {
                futures.add(executor.submit(() -> {
                    try (IqBufferLease held = shared.retain()) {
                        ready.countDown();
                        require(race.await(10, TimeUnit.SECONDS), "Retain/close race did not start");
                        for (int n = 0; n < 10000; n++) {
                            IqBufferLease child;
                            try { child = shared.retain(); }
                            catch (IllegalStateException closed) { break; }
                            try (child) { require(child.i(0) == 17 && child.q(0) == -17, "Retain raced with recycling"); }
                        }
                        require(held.i(0) == 17 && held.q(0) == -17, "Close invalidated an independent owner");
                    }
                    return null;
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS));
            race.countDown();
            shared.close();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            race.countDown();
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Retain/close workers did not stop");
        }
        check(pool.statistics().invalidReleases() == 0);
        assertDrained(pool);
    }

    private static void concurrentRetain(long seed) throws Exception {
        int workers = 6, iterations = 3000;
        IqBufferPool pool = new IqBufferPool(32);
        IqBufferLease root = acquire(pool, 4);
        for (int n = 0; n < 4; n++) root.put(n, n + 1, -(n + 1));
        root.publish(1, 3);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers), start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < workers; w++) {
                IqBufferLease anchor = root.retain();
                long workerSeed = seed + w;
                futures.add(executor.submit(() -> {
                    try (anchor) {
                        ready.countDown();
                        require(start.await(10, TimeUnit.SECONDS), "Retain test did not start");
                        Random random = new Random(workerSeed);
                        for (int n = 0; n < iterations; n++) {
                            try (IqBufferLease child = anchor.retain()) {
                                int index = random.nextInt(3);
                                require(child.i(index) == index + 2 && child.q(index) == -index - 2, "Retained data changed");
                                if ((n & 31) == 0) {
                                    try (IqBufferLease grandchild = child.retain()) {
                                        require(grandchild.offsetSamples() == 1 && grandchild.complexSamples() == 3, "Window changed");
                                    }
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            check(ready.await(10, TimeUnit.SECONDS));
            root.close();
            check(pool.tryAcquire(1).isEmpty());
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Retain workers did not stop");
        }
        check(pool.statistics().invalidReleases() == 0 && pool.statistics().allocations() == 1);
        assertDrained(pool);
    }

    private static void concurrentAcquisition(long seed) throws Exception {
        int workers = 6, iterations = 1500;
        IqBufferPool pool = new IqBufferPool(4 * 32);
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < workers; w++) {
                int worker = w;
                futures.add(executor.submit(() -> {
                    Random random = new Random(seed + worker);
                    require(start.await(10, TimeUnit.SECONDS), "Acquisition test did not start");
                    for (int n = 0; n < iterations; n++) {
                        IqBufferLease lease;
                        do {
                            lease = pool.tryAcquire(4).orElse(null);
                            if (lease == null) Thread.yield();
                        } while (lease == null && !Thread.currentThread().isInterrupted());
                        require(lease != null, "Acquisition interrupted");
                        float value = worker * iterations + n + 1;
                        try (IqBufferLease owned = lease) {
                            for (int j = 0; j < 4; j++) owned.put(j, value + j, -value - j);
                            owned.publish(0, 4);
                            try (IqBufferLease retained = owned.retain()) {
                                if (random.nextBoolean()) Thread.yield();
                                for (int j = 0; j < 4; j++) {
                                    require(retained.i(j) == value + j && retained.q(j) == -value - j, "Active buffer aliased");
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(30, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            require(executor.awaitTermination(10, TimeUnit.SECONDS), "Acquisition workers did not stop");
        }
        check(pool.statistics().peakReservedBytes() <= pool.statistics().budgetBytes());
        check(pool.statistics().invalidReleases() == 0 && pool.statistics().reuses() > 0);
        assertDrained(pool);
    }

    private static IqBufferLease acquire(IqBufferPool pool, int samples) {
        return pool.tryAcquire(samples).orElseThrow(() -> new AssertionError("Unexpected exhaustion"));
    }

    private static void assertDrained(IqBufferPool pool) {
        check(pool.statistics().outstandingLeases() == 0 && pool.statistics().activeBytes() == 0);
        pool.close();
        check(pool.statistics().retainedBytes() == 0 && pool.statistics().outstandingLeases() == 0);
    }

    private static void expect(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable error) {
            if (type.isInstance(error)) return;
            throw new AssertionError("Expected " + type.getName() + ", got " + error, error);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    private static void check(boolean condition) {
        checks++;
        require(condition, "IQ pool check " + checks + " failed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
