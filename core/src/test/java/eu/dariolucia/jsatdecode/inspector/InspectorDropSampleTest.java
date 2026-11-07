package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.source.fmcomms2.ReadDiagnostics;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained timing, loss-evidence, lifecycle and CSV checks; no radio required. */
public final class InspectorDropSampleTest {
    private static int checks;

    /** Runs the deterministic Inspector test-mode suite without network access. */
    public static void main(String[] arguments) throws Exception { run(); }

    /** Verifies the Cartesian plan, read boundaries, recovered pauses and cancellation. */
    public static void run() throws Exception {
        planAndConfiguration();
        measurementBoundaries();
        syntheticLossEvidence();
        independentRunsAndFailure();
        cancellation(false);
        cancellation(true);
        watchdogs();
        diagnosticOverflow();
        csv();
        System.out.println("JSatInspector drop samples: " + checks + " checks passed");
    }

    private static void planAndConfiguration() {
        List<DropSampleTest.Parameters> plan = DropSampleTest.plan();
        check(plan.size() == 120 && new HashSet<>(plan).size() == 120, "Every explicitly requested Cartesian combination occurs exactly once");
        for (DropSampleTest.Parameters p : plan) {
            var c = DropSampleTest.sourceConfiguration("192.168.2.1", p);
            int phy = p.dataRate() < 2_084_000 ? 4_000_000 : p.dataRate();
            check(c.getString("uri").equals("ip:192.168.2.1") && c.getLong("frequency") == 2_250_000_000L &&
                    c.getInt("bandwidth") == 5_000_000 && c.getInt("manual_gain1") == 50 && c.getString("gain1").equals("manual"),
                    "Test runs use requested IP, tuning, RF bandwidth and manual gain");
            check(c.getInt("sample_rate") == phy && c.getInt("fpga_decimation") == phy / p.dataRate(),
                    "Low output rates use exact native FPGA division rather than software-decimated traffic");
            check(c.getInt("buffer_size") == p.bufferSize() && c.getInt("block_samples") == p.bufferSize(),
                    "Each selected buffer size represents complex samples and one completed RX read");
            int capacity = DropSampleTest.logCapacity(p, 2_000_000_000L, 10_000_000_000L);
            check(capacity >= (p.durationSeconds() + 2) * (double) p.dataRate() / p.bufferSize() && capacity <= 500_000,
                    "Preallocated log holds nominal run/warmup cadence within a fixed bound");
        }
        check(DropSampleTest.sourceConfiguration("ip:host:30431", plan.getFirst()).getString("uri").equals("ip:host:30431"),
                "Existing ip URI prefix is preserved");
    }

    private static void measurementBoundaries() {
        DropSampleMeasurement m = new DropSampleMeasurement(1, 2_000_000_000L);
        check(m.stateAt(0) == DropSampleTest.State.STARTING, "Run begins in STARTING before samples");
        m.accept(0, 10);
        m.accept(1_999_999_999L, 20);
        check(m.stateAt(2_000_000_000L) == DropSampleTest.State.INITIALISING && m.arrivedSamples() == 0,
                "Warmup begins with first read and expires independently of the next completed read");
        m.accept(2_100_000_000L, 30);
        check(m.startNanos() == 2_100_000_000L && m.stateAt(2_100_000_000L) == DropSampleTest.State.COUNTING && m.arrivedSamples() == 0,
                "First INITIALISING read sets T1 and resets counter without adding its samples");
        m.accept(2_200_000_000L, 40);
        m.accept(3_099_999_999L, 50);
        check(m.arrivedSamples() == 90 && m.stateAt(3_100_000_000L) == DropSampleTest.State.EVALUATING,
                "Counting includes only reads before the absolute STOP_TIMER deadline");
        m.accept(3_250_000_000L, 60);
        check(m.complete() && m.arrivedSamples() == 150 && m.evaluationSamples() == 60 && Math.abs(m.effectiveSeconds() - 1.15) < 1e-12,
                "First EVALUATING read sets T2 and counts its samples exactly once for the complete interval");
        m.accept(4_000_000_000L, 70);
        check(m.arrivedSamples() == 150 && m.endNanos() == 3_250_000_000L, "Completed measurements cannot accumulate trailing reads");
        DropSampleMeasurement invalid = new DropSampleMeasurement(1, 0);
        invalid.accept(10, 5); invalid.accept(9, 5);
        check(invalid.failure() != null, "Backward monotonic read time explicitly fails measurement");
    }

    private static void syntheticLossEvidence() throws Exception {
        long[][] baseline = trace(false, false);
        long[][] bufferedPause = trace(true, false);
        long[][] missing = trace(true, true);
        DropSampleResult normal = runTrace(baseline);
        DropSampleResult recovered = runTrace(bufferedPause);
        DropSampleResult lost = runTrace(missing);
        check(normal.status() == DropSampleResult.Status.COMPLETE && normal.measuredSamples() == 1_000_000 &&
                normal.theoreticalSamples() == 1_000_000 && normal.averageSamplesPerSecond() == 1_000_000 &&
                normal.droppedPercent() == 0 && normal.estimatedLostSignalMs() == 0,
                "Healthy result includes the final read in its count, average rate, dropped percentage and lost signal");
        exportedMetrics(normal, 1_000_000, 1_000_000, 0, 0);
        check(!normal.significantLoss() && !recovered.significantLoss() && normal.measuredSamples() == recovered.measuredSamples(),
                "A 200 ms pause followed by buffered catchup does not trigger significant-loss highlighting");
        check(lost.significantLoss() && lost.attentionRequired() && lost.measuredSamples() == 800_000,
                "The same pause followed by persistent cumulative deficit is significant");
        exportedMetrics(lost, 800_000, 800_000, 20, 200);
        check(lost.detail().contains("200000 samples") && lost.detail().contains("cannot prove hardware"),
                "Result explains the same persistent deficit as its main metrics and host-timing limits");
        check(recovered.detail().contains("0 samples"), "Recovered buffering has zero final deficit including its final read");
        DropSampleResult oneMissing = runTrace(java.util.Arrays.stream(trace(false, false))
                .filter(read -> read[0] != 2_500_000_000L).toArray(long[][]::new));
        check(oneMissing.significantLoss() && oneMissing.measuredSamples() == 900_000,
                "A missing block remains significant without a second evaluation-block allowance");
        exportedMetrics(oneMissing, 900_000, 900_000, 10, 100);
        long[][] excess = new long[][] {{0, 100000}, {2_000_000_000L, 100000}, {2_500_000_000L, 2_000_000}, {3_000_000_000L, 100000}};
        DropSampleResult faster = runTrace(excess);
        check(faster.droppedPercent() == 0 && faster.estimatedLostSignalMs() == 0 && !faster.significantLoss(),
                "Excess delivery/negative raw deficits never produce negative dropped samples or lost signal");
        DropSampleTest.Parameters largeBuffer = new DropSampleTest.Parameters(1, 524288, 500000);
        DropSampleResult shortFinal = runTrace(new long[][] {{0, 100000}, {2_000_000_000L, 100000},
                {2_250_000_000L, 100000}, {2_500_000_000L, 100000}, {2_750_000_000L, 100000},
                {3_000_000_000L, 4096}}, largeBuffer);
        check(shortFinal.significantLoss() && shortFinal.evaluationReadSamples() == 4096,
                "A short evaluation read cannot conceal persistent loss behind a large configured RX buffer");
        exportedMetrics(shortFinal, 304096, 304096, 39.1808, 391.808);
        DropSampleResult fullBoundary = runTrace(new long[][] {{0, 524288}, {2_000_000_000L, 524288},
                {3_048_576_000L, 524288}}, largeBuffer);
        check(!fullBoundary.significantLoss() && fullBoundary.measuredSamples() == 524288 &&
                        Math.abs(fullBoundary.averageSamplesPerSecond() - 500000) < 1e-8,
                "A run whose only measured read is the evaluation block has its complete sample count and rate");
        exportedMetrics(fullBoundary, 524288, 500000, 0, 0);
    }

    private static void exportedMetrics(DropSampleResult result, long samples, double rate, double percent,
                                        double lostMs) throws Exception {
        Object[] row = result.row();
        StringWriter writer = new StringWriter();
        DropSampleResult.writeCsv(writer, List.of(result));
        String[] csv = writer.toString().split("\r\n")[1].split(",", 11);
        check((long) row[6] == samples && Long.parseLong(csv[6]) == samples,
                "Table row and CSV include exactly the same evaluation-inclusive sample count");
        double[] expected = {rate, percent, lostMs};
        for (int i = 0; i < expected.length; i++) {
            check(Math.abs((double) row[7 + i] - expected[i]) < 1e-8 &&
                            Math.abs(Double.parseDouble(csv[7 + i]) - expected[i]) < 1e-8,
                    "Table row and CSV have evaluation-inclusive rate and loss metrics");
        }
    }

    private static long[][] trace(boolean pause, boolean lose) {
        List<long[]> reads = new ArrayList<>();
        reads.add(new long[] {0, 100000});
        reads.add(new long[] {2_000_000_000L, 100000});
        for (int i = 1; i <= 10; i++) {
            if (pause && (i == 4 || i == 5)) {
                if (!lose) reads.add(new long[] {2_600_000_000L + (i - 4) * 1_000_000L, 100000});
            } else reads.add(new long[] {2_000_000_000L + i * 100_000_000L, 100000});
        }
        reads.sort(java.util.Comparator.comparingLong(r -> r[0]));
        return reads.toArray(long[][]::new);
    }

    private static DropSampleResult runTrace(long[][] trace) {
        return runTrace(trace, parameters());
    }

    private static DropSampleResult runTrace(long[][] trace, DropSampleTest.Parameters parameters) {
        List<DropSampleResult> results = new ArrayList<>();
        DropSampleTest engine = engine(List.of(parameters), (configuration, capacity, listener) -> new FakeSource(capacity) {
            @Override public void start() {
                for (long[] read : trace) {
                    if (stopped) break;
                    listener.onRead(1_700_000_000_000_000_000L + read[0], read[0], (int) read[1]);
                }
            }
        }, System::nanoTime, 2_000_000_000L, 1_000_000_000L);
        engine.run(ignored -> { }, results::add);
        check(results.size() == 1, "One independent completed synthetic run produces exactly one result");
        return results.getFirst();
    }

    private static void independentRunsAndFailure() {
        AtomicInteger created = new AtomicInteger(), closed = new AtomicInteger();
        List<DropSampleResult> results = new ArrayList<>();
        List<DropSampleTest.Progress> progress = new ArrayList<>();
        Thread management = Thread.currentThread();
        DropSampleTest engine = engine(List.of(parameters(), parameters()), (configuration, capacity, listener) -> {
            int id = created.incrementAndGet();
            return new FakeSource(capacity) {
                @Override public void start() {
                    if (id == 1) throw new IllegalStateException("simulated incompatible FPGA rate");
                    listener.onRead(0, 0, 100000);
                    listener.onRead(0, 500_000_000L, 100000);
                    listener.onRead(0, 1_000_000_000L, 100000);
                }
                @Override public void close() { super.close(); closed.incrementAndGet(); }
            };
        }, System::nanoTime, 0, 1_000_000_000L);
        engine.run(p -> { check(Thread.currentThread() == management, "Progress callbacks stay on management thread"); progress.add(p); }, results::add);
        check(created.get() == 2 && closed.get() == 2 && results.size() == 2, "Failed startup is closed and remaining parameter combinations still execute");
        check(results.getFirst().status() == DropSampleResult.Status.FAILED && results.getFirst().attentionRequired() &&
                Double.isNaN(results.getFirst().effectiveDurationMs()) && results.getFirst().detail().contains("incompatible FPGA"),
                "Startup failures remain visible as red-capable rows with unavailable metrics");
        check(results.get(1).status() == DropSampleResult.Status.COMPLETE && results.get(1).runId() == 2 &&
                results.get(1).measuredSamples() == 200000, "Next run starts from a fresh counter and source");
        check(progress.getFirst().state() == DropSampleTest.State.CONNECTING && progress.getLast().state() == DropSampleTest.State.COMPLETE,
                "Management progress includes connecting and final run states");
        boolean rejected = false;
        try { engine.run(ignored -> { }, ignored -> { }); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Suites are one-shot lifecycle owners");
    }

    private static void cancellation(boolean blockedRead) throws Exception {
        CountDownLatch entered = new CountDownLatch(1), stopLatch = new CountDownLatch(1);
        AtomicInteger closed = new AtomicInteger(), created = new AtomicInteger();
        List<DropSampleResult> results = new ArrayList<>();
        DropSampleTest engine = engine(List.of(parameters(), parameters()), (configuration, capacity, listener) -> {
            created.incrementAndGet();
            return new FakeSource(capacity) {
                @Override public void start() {
                    if (blockedRead) { listener.onRead(0, 0, 1000); entered.countDown(); return; }
                    entered.countDown();
                    try { if (!stopLatch.await(3, TimeUnit.SECONDS)) throw new AssertionError("Startup was not cancelled"); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    throw new IllegalStateException("socket closed during startup");
                }
                @Override public void requestStop() { super.requestStop(); stopLatch.countDown(); }
                @Override public void close() { super.close(); closed.incrementAndGet(); }
            };
        }, System::nanoTime, 2_000_000_000L, 10_000_000_000L);
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread worker = new Thread(() -> { try { engine.run(ignored -> { }, results::add); } catch (Throwable e) { workerFailure.set(e); } }, "drop-test-cancel");
        worker.start();
        check(entered.await(2, TimeUnit.SECONDS), "Synthetic source reaches blocked " + (blockedRead ? "read" : "startup"));
        long before = System.nanoTime(); engine.cancel();
        check(System.nanoTime() - before < 100_000_000L, "Cancel requests source stop without blocking/joining on caller");
        worker.join(3000);
        check(!worker.isAlive() && workerFailure.get() == null && closed.get() == 1 && created.get() == 1,
                "Cancellation closes blocked source and prevents subsequent runs");
        check(results.size() == 1 && results.getFirst().status() == DropSampleResult.Status.CANCELLED &&
                !results.getFirst().significantLoss(), "Cancelled partial run remains explicitly unavailable rather than a fabricated loss estimate");
    }

    private static void watchdogs() {
        for (boolean firstRead : new boolean[] {false, true}) {
            AtomicLong now = new AtomicLong();
            List<DropSampleResult> results = new ArrayList<>();
            DropSampleTest engine = engine(List.of(parameters()), (configuration, capacity, listener) -> new FakeSource(capacity) {
                @Override public void start() {
                    if (firstRead) listener.onRead(0, 0, 1000);
                }
            }, () -> now.getAndAdd(1_000_000_000L), 0, 2_000_000_000L);
            engine.run(ignored -> { }, results::add);
            check(results.getFirst().status() == DropSampleResult.Status.FAILED && results.getFirst().detail().contains("Timed out"),
                    "Missing " + (firstRead ? "post-timer" : "first") + " read terminates through bounded watchdog");
            if (firstRead) check(results.getFirst().detail().contains("EVALUATING"), "Watchdog covers an armed timer with no evaluation read");
        }
    }

    private static void diagnosticOverflow() {
        AtomicInteger calls = new AtomicInteger();
        List<DropSampleResult> results = new ArrayList<>();
        DropSampleTest engine = engine(List.of(parameters()), (configuration, capacity, listener) -> new FakeSource(capacity) {
            @Override public void start() {
                while (!stopped) { calls.incrementAndGet(); listener.onRead(0, 0, 1); }
            }
        }, System::nanoTime, 2_000_000_000L, 1_000_000_000L);
        engine.run(ignored -> { }, results::add);
        check(results.getFirst().status() == DropSampleResult.Status.FAILED && results.getFirst().detail().contains("overflow") &&
                calls.get() == DropSampleTest.logCapacity(parameters(), 2_000_000_000L, 1_000_000_000L) + 1,
                "Unexpected short-read cadence aborts immediately at first preallocated diagnostic overflow");
    }

    private static void csv() throws Exception {
        DropSampleResult failure = new DropSampleResult(1, 5, 500000, 4096, Double.NaN, Double.NaN, 0,
                Double.NaN, Double.NaN, Double.NaN, DropSampleResult.Status.FAILED, "radio, \"failed\"\nnew line");
        StringWriter writer = new StringWriter();
        DropSampleResult.writeCsv(writer, List.of(failure));
        check(writer.toString().startsWith("Run ID,Test duration (s)") && writer.toString().contains("Status,Detail\r\n"),
                "CSV uses requested table headings plus explicit status/detail");
        check(!writer.toString().contains("NaN") && writer.toString().contains("\"radio, \"\"failed\"\"\nnew line\""),
                "CSV leaves missing metrics empty and quotes commas, quotes and newlines safely");
        check(failure.row().length == 10 && DropSampleResult.COLUMNS.size() == 10, "Table row matches all ten requested columns");
    }

    private static DropSampleTest.Parameters parameters() { return new DropSampleTest.Parameters(1, 100000, 1_000_000); }

    private static DropSampleTest engine(List<DropSampleTest.Parameters> plan, DropSampleTest.SourceFactory factory,
                                         java.util.function.LongSupplier time, long warmup, long watchdog) {
        return new DropSampleTest("127.0.0.1", plan, factory, time, warmup, watchdog, new PrintWriter(new StringWriter()));
    }

    private static class FakeSource implements DropSampleTest.ReadSource {
        protected volatile boolean stopped;
        private final ReadDiagnostics log;
        FakeSource(int capacity) { log = new ReadDiagnostics(capacity, (utc, elapsed, count) -> { }); }
        @Override public void start() { }
        @Override public void requestStop() { stopped = true; }
        @Override public Throwable failure() { return null; }
        @Override public long reportedOverruns() { return 0; }
        @Override public ReadDiagnostics diagnostics() { return log; }
        @Override public void close() { requestStop(); }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }
}
