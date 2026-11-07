package eu.dariolucia.jsatdecode;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.taps.ComplexSnapshot;
import eu.dariolucia.jsatdecode.api.taps.ComplexTap;
import eu.dariolucia.jsatdecode.core.buffers.ComplexFloatRingBuffer;
import eu.dariolucia.jsatdecode.core.monitoring.MetricsRegistry;
import eu.dariolucia.jsatdecode.core.monitoring.MonitoringStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Phase 2 deterministic, concurrent and slow-observer acceptance tests. */
public final class ObservabilityTest {
    private static int checks;
    private ObservabilityTest() {}

    /** Executes checks independently of the JVM assertion flag.
     * @param args unused
     * @throws Exception if a concurrency check fails
     */
    public static void main(String[] args) throws Exception {
        ringBoundaries();
        ringModel();
        snapshotsOwnStorage();
        slowObserver();
        concurrentSnapshots(false);
        concurrentSnapshots(true);
        metricValues();
        metricValidation();
        concurrentMetrics();
        System.out.println("Phase 2: " + checks + " checks passed");
    }

    private static void ringBoundaries() {
        ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(3, 48000);
        check(ring.capacitySamples() == 3 && ring.sampleRate() == 48000);
        check(ring.snapshotLatest(10).complexSamples() == 0);
        ring.write(new float[0], 0);
        check(ring.samplesWritten() == 0 && ring.overwrittenSamples() == 0);
        check(ring.snapshot(0).complexSamples() == 0);
        ring.write(1, -1);
        check(Arrays.equals(ring.snapshot(10).iq(), new float[]{1, -1}));
        ring.write(new float[]{2, -2, 3, -3}, 2);
        check(Arrays.equals(ring.snapshot(3).iq(), new float[]{1, -1, 2, -2, 3, -3}));
        ring.write(4, -4);
        check(Arrays.equals(ring.snapshot(Integer.MAX_VALUE).iq(), new float[]{2, -2, 3, -3, 4, -4}));
        check(ring.samplesWritten() == 4 && ring.overwrittenSamples() == 1);
        check(Arrays.equals(ring.snapshot(1).iq(), new float[]{4, -4}));

        // Invalid ranges must not mutate the published state.
        expect(NullPointerException.class, () -> ring.write(null, 0));
        expect(IndexOutOfBoundsException.class, () -> ring.write(new float[2], 2));
        expect(IndexOutOfBoundsException.class, () -> ring.write(new float[2], -1));
        expect(IndexOutOfBoundsException.class, () -> ring.write(new float[2], -1, 1));
        expect(IndexOutOfBoundsException.class, () -> ring.write(new float[2], Integer.MAX_VALUE, 1));
        expect(IllegalArgumentException.class, () -> ring.snapshot(-1));
        check(ring.samplesWritten() == 4);
        ring.write(new float[2], 1, 0);
        check(ring.samplesWritten() == 4);
        expect(IllegalArgumentException.class, () -> new ComplexFloatRingBuffer(0, 1));
        expect(IllegalArgumentException.class, () -> new ComplexFloatRingBuffer(-1, 1));
        expect(IllegalArgumentException.class, () -> new ComplexFloatRingBuffer(Integer.MAX_VALUE, 1));
        for (double rate : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            expect(IllegalArgumentException.class, () -> new ComplexFloatRingBuffer(3, rate));
        }

        ComplexFloatRingBuffer bits = new ComplexFloatRingBuffer(1, 1);
        float payloadNaN = Float.intBitsToFloat(0x7fc01234);
        bits.write(payloadNaN, -0.0f);
        float[] stored = bits.snapshot(1).iq();
        check(Float.floatToRawIntBits(stored[0]) == 0x7fc01234);
        check(Float.floatToRawIntBits(stored[1]) == 0x80000000);
        bits.write(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY);
        check(Arrays.equals(bits.snapshot(2).iq(), new float[]{Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}));
    }

    private static void ringModel() {
        // Compare many wrap positions and oversized writes with a chronological oracle.
        for (int capacity : new int[]{1, 3, 16, 31}) {
            ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(capacity, 1000);
            int next = 0;
            for (int batch : new int[]{0, 1, 2, 5, 10000, 0, 3, 31, 1, 65}) {
                float[] input = new float[(batch + 4) * 2];
                Arrays.fill(input, -999);
                for (int n = 0; n < batch; n++) {
                    input[(n + 2) * 2] = next + n;
                    input[(n + 2) * 2 + 1] = -(next + n);
                }
                ring.write(input, 2, batch);
                next += batch;
                for (int requested : new int[]{0, 1, 2, capacity, capacity + 10, Integer.MAX_VALUE}) {
                    ComplexSnapshot snapshot = ring.snapshotLatest(requested);
                    int count = Math.min(next, Math.min(capacity, requested));
                    check(snapshot.complexSamples() == count);
                    float[] actual = snapshot.iq();
                    for (int n = 0; n < count; n++) {
                        check(actual[n * 2] == next - count + n);
                        check(actual[n * 2 + 1] == -(next - count + n));
                    }
                }
                check(ring.samplesWritten() == next);
                check(ring.overwrittenSamples() == Math.max(0, next - capacity));
                check(ring.missedSnapshots() == 0);
            }
        }
    }

    private static void snapshotsOwnStorage() {
        ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(2, 1);
        float[] input = {1, -1, 2, -2};
        ring.write(input, 2);
        input[0] = 999;
        ComplexTap tap = ring;
        ComplexSnapshot old = tap.snapshot(2);
        ring.write(new float[]{3, -3, 4, -4, 5, -5}, 3);
        check(Arrays.equals(old.iq(), new float[]{1, -1, 2, -2}));
        old.iq()[0] = 888;
        check(Arrays.equals(tap.snapshot(2).iq(), new float[]{4, -4, 5, -5}));
        check(old.sampleRate() == 1);
        check(ring.snapshot(2).timestampNanos() - old.timestampNanos() >= 0);
    }

    private static void slowObserver() throws Exception {
        ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(8, 1000);
        ring.write(-1, 1);
        CountDownLatch observerHolding = new CountDownLatch(1);
        CountDownLatch resumeObserver = new CountDownLatch(1);
        CountDownLatch producerFinished = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread observer = worker("paused-observer", failure, () -> {
            ComplexSnapshot old = ring.snapshot(8);
            observerHolding.countDown();
            require(resumeObserver.await(10, TimeUnit.SECONDS), "Observer was not released");
            require(old.iq()[0] == -1, "Retained snapshot was overwritten");
            float[] latest = ring.snapshot(8).iq();
            require(latest[0] == 99992 && latest[14] == 99999, "Wrong final suffix");
        });
        Thread producer = worker("independent-producer", failure, () -> {
            for (int n = 0; n < 100000; n++) ring.write(n, -n);
            producerFinished.countDown();
        });
        observer.start();
        try {
            check(observerHolding.await(5, TimeUnit.SECONDS));
            producer.start();
            check(producerFinished.await(5, TimeUnit.SECONDS));
            check(resumeObserver.getCount() == 1); // Producer completed while observer remained stopped.
            check(ring.samplesWritten() == 100001 && ring.overwrittenSamples() == 99993);
        } finally {
            resumeObserver.countDown();
            join(producer);
            join(observer);
        }
        rethrow(failure);
    }

    private static void concurrentSnapshots(boolean blocks) throws Exception {
        // Coordinate startup, then let a single writer publish without reader handshakes.
        ComplexFloatRingBuffer ring = new ComplexFloatRingBuffer(127, 96000);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean finished = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch readersReady = new CountDownLatch(3);
        Thread producer = worker("tap-producer", failure, () -> {
            start.await();
            readersReady.await();
            float[] block = new float[514];
            int next = 0;
            try {
                for (int n = 0; n < 10000; n++) {
                    if (blocks) {
                        for (int j = 0; j < 257; j++) {
                            block[j * 2] = next + j;
                            block[j * 2 + 1] = -(next + j);
                        }
                        ring.write(block, 257);
                        next += 257;
                    } else {
                        ring.write(next, -next);
                        next++;
                    }
                }
            } finally {
                finished.set(true);
            }
        });
        Thread[] readers = new Thread[3];
        // Every accepted snapshot must contain complete pairs in chronological order;
        // empty snapshots are explicitly valid during bounded retry exhaustion.
        for (int n = 0; n < readers.length; n++) {
            readers[n] = worker("tap-observer-" + n, failure, () -> {
                start.await();
                readersReady.countDown();
                long previousTimestamp = 0;
                boolean hasTimestamp = false;
                do {
                    ComplexSnapshot snapshot = ring.snapshot(200);
                    float[] iq = snapshot.iq();
                    require(snapshot.complexSamples() <= 127, "Snapshot exceeded capacity");
                    if (iq.length != 0) {
                        require(!hasTimestamp || snapshot.timestampNanos() - previousTimestamp >= 0,
                                "Timestamp moved backwards");
                        previousTimestamp = snapshot.timestampNanos();
                        hasTimestamp = true;
                        for (int j = 0; j < iq.length; j += 2) {
                            require(iq[j + 1] == -iq[j], "Torn I/Q pair");
                            if (j != 0) require(iq[j] == iq[j - 2] + 1, "Nonchronological snapshot");
                        }
                    }
                } while (!finished.get());
            });
        }
        producer.start();
        for (Thread reader : readers) reader.start();
        start.countDown();
        join(producer);
        // After publication stops, all retained samples must be immediately readable.
        for (Thread reader : readers) join(reader);
        rethrow(failure);
        check(ring.samplesWritten() == (blocks ? 2570000 : 10000));
        check(ring.snapshot(200).complexSamples() == 127);
        check(ring.overwrittenSamples() == ring.samplesWritten() - 127);
    }

    private enum Lock { UNKNOWN, LOCKED, UNLOCKED }
    private enum Other { WRONG }

    private static MonitoringDescriptor descriptor(String key, MonitoringType type) {
        return new MonitoringDescriptor(key, type, "", "Test " + key);
    }

    private static void metricValues() {
        // Register every supported type and preserve independent lock handles.
        MetricsRegistry registry = new MetricsRegistry(7);
        var carrier = registry.registerBoolean(descriptor(DecoderStatusKeys.CARRIER_LOCK, MonitoringType.BOOLEAN), false);
        var symbol = registry.registerBoolean(descriptor(DecoderStatusKeys.SYMBOL_LOCK, MonitoringType.BOOLEAN), true);
        var integer = registry.registerInt(descriptor("fec.corrected", MonitoringType.INTEGER), 2);
        var count = registry.registerLong(descriptor("source.samples", MonitoringType.LONG), 0);
        var snr = registry.registerDouble(descriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE), Double.NaN);
        var text = registry.registerString(descriptor("source.name", MonitoringType.STRING), "initial");
        var state = registry.registerEnum(descriptor(DecoderStatusKeys.FRAME_LOCK, MonitoringType.ENUM), Lock.UNKNOWN);
        MonitoringStore store = registry.build();
        check(store == registry.build() && store.size() == 7);
        check(store.metric("source.samples") == count);
        check(Double.isNaN(snr.get()));
        check(integer.addAndGet(3) == 5);
        check(count.addAndGet(10) == 10);
        MonitoringSnapshot initial = store.monitoringSnapshot();
        // Updates must not mutate observations already handed to another thread.
        carrier.set(true);
        symbol.set(false);
        integer.set(7);
        count.set(100);
        snr.set(12.5);
        text.set("updated");
        state.set(Lock.LOCKED);
        MonitoringSnapshot latest = store.monitoringSnapshot();
        // Validate value types, schema ordering, immutability and unavailable SNR.
        check(initial.values().get("source.samples").equals(10L));
        check(initial.values().get("source.name").equals("initial"));
        check(Double.isNaN((Double) initial.values().get(DecoderStatusKeys.SIGNAL_SNR_DB)));
        check(latest.values().get(DecoderStatusKeys.CARRIER_LOCK).equals(true));
        check(latest.values().get(DecoderStatusKeys.SYMBOL_LOCK).equals(false));
        check(latest.values().get("source.samples").equals(100L));
        check(latest.values().get("fec.corrected").equals(7));
        check(latest.values().get(DecoderStatusKeys.SIGNAL_SNR_DB).equals(12.5));
        check(latest.values().get("source.name").equals("updated"));
        check(latest.values().get(DecoderStatusKeys.FRAME_LOCK) == Lock.LOCKED);
        check(latest.timestampNanos() - initial.timestampNanos() >= 0);
        check(carrier.get() && !symbol.get() && integer.get() == 7 && count.get() == 100);
        check(text.get().equals("updated") && state.get() == Lock.LOCKED);
        check(store.monitoringDescriptors().stream().map(MonitoringDescriptor::key).toList()
                .equals(List.of(DecoderStatusKeys.CARRIER_LOCK, DecoderStatusKeys.SYMBOL_LOCK, "fec.corrected", "source.samples",
                        DecoderStatusKeys.SIGNAL_SNR_DB, "source.name", DecoderStatusKeys.FRAME_LOCK)));
        expect(UnsupportedOperationException.class, () -> latest.values().clear());
        expect(UnsupportedOperationException.class, () -> store.monitoringDescriptors().clear());
        expect(IllegalStateException.class, () -> registry.registerLong(descriptor("new", MonitoringType.LONG), 0));
        snr.set(Double.NaN);
        check(Double.isNaN((Double) store.monitoringSnapshot().values().get(DecoderStatusKeys.SIGNAL_SNR_DB)));
        snr.set(Double.POSITIVE_INFINITY);
        check(snr.get() == Double.POSITIVE_INFINITY);
    }

    private static void metricValidation() {
        expect(IllegalArgumentException.class, () -> new MetricsRegistry(0));
        MetricsRegistry registry = new MetricsRegistry(1);
        expect(NullPointerException.class, () -> registry.registerLong(null, 0));
        expect(IllegalArgumentException.class, () -> registry.registerLong(descriptor("x", MonitoringType.DOUBLE), 0));
        expect(NullPointerException.class, () -> registry.registerString(descriptor("x", MonitoringType.STRING), null));
        registry.registerLong(descriptor("x", MonitoringType.LONG), 1);
        expect(IllegalArgumentException.class, () -> registry.registerLong(descriptor("x", MonitoringType.LONG), 1));
        expect(IllegalStateException.class, () -> registry.registerInt(descriptor("y", MonitoringType.INTEGER), 1));
        MonitoringStore store = registry.build();
        check(store.size() == 1);
        expect(IllegalArgumentException.class, () -> store.metric("missing"));
        expect(NullPointerException.class, () -> store.metric(null));
        MetricsRegistry references = new MetricsRegistry(2);
        var text = references.registerString(descriptor("text", MonitoringType.STRING), "");
        var state = references.registerEnum(descriptor("state", MonitoringType.ENUM), Lock.UNKNOWN);
        expect(NullPointerException.class, () -> text.set(null));
        expect(NullPointerException.class, () -> state.set(null));
        expect(IllegalArgumentException.class, () -> state.set(Other.WRONG));
        check(state.get() == Lock.UNKNOWN && text.get().isEmpty());
        check(new MetricsRegistry(1).build().monitoringSnapshot().values().isEmpty());
    }

    private static void concurrentMetrics() throws Exception {
        MetricsRegistry registry = new MetricsRegistry(3);
        var count = registry.registerLong(descriptor("count", MonitoringType.LONG), 0);
        var integer = registry.registerInt(descriptor("integer", MonitoringType.INTEGER), 0);
        var snr = registry.registerDouble(descriptor(DecoderStatusKeys.SIGNAL_SNR_DB, MonitoringType.DOUBLE), Double.NaN);
        MonitoringStore store = registry.build();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(4);
        Thread[] producers = new Thread[4];
        for (int n = 0; n < producers.length; n++) {
            producers[n] = worker("metric-producer-" + n, failure, () -> {
                start.await();
                try {
                    for (int i = 0; i < 50000; i++) {
                        count.addAndGet(1);
                        integer.addAndGet(1);
                        snr.set(i * 0.25);
                    }
                } finally { done.countDown(); }
            });
            producers[n].start();
        }
        Thread observer = worker("metric-observer", failure, () -> {
            start.await();
            long previous = 0;
            do {
                MonitoringSnapshot snapshot = store.monitoringSnapshot();
                long current = (Long) snapshot.values().get("count");
                require(current >= previous, "Counter moved backwards");
                previous = current;
                require(snapshot.values().size() == 3, "Schema changed");
            } while (done.getCount() != 0);
        });
        observer.start();
        start.countDown();
        for (Thread producer : producers) join(producer);
        join(observer);
        rethrow(failure);
        check(count.get() == 200000 && integer.get() == 200000);
        check(store.monitoringSnapshot().values().get("count").equals(200000L));
    }

    private interface Task { void run() throws Exception; }
    private static Thread worker(String name, AtomicReference<Throwable> failure, Task task) {
        Thread thread = new Thread(() -> {
            try { task.run(); }
            catch (Throwable e) { failure.compareAndSet(null, e); }
        }, name);
        thread.setDaemon(true); // A failed timeout must not leave the test process alive.
        return thread;
    }
    private static void join(Thread thread) throws InterruptedException {
        thread.join(10000);
        if (thread.isAlive()) {
            thread.interrupt();
            throw new AssertionError("Timed out: " + thread.getName());
        }
    }
    private static void rethrow(AtomicReference<Throwable> failure) {
        if (failure.get() != null) throw new AssertionError("Worker failed", failure.get());
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void check(boolean condition) {
        checks++;
        require(condition, "Phase 2 check " + checks + " failed");
    }
    private static void expect(Class<? extends Throwable> type, Runnable action) {
        checks++;
        try { action.run(); }
        catch (Throwable e) {
            if (type.isInstance(e)) return;
            throw new AssertionError("Expected " + type + ", got " + e, e);
        }
        throw new AssertionError("Expected " + type);
    }
}
