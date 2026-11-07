package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.source.fmcomms2.Fmcomms2SampleSource;
import eu.dariolucia.jsatdecode.source.fmcomms2.ReadDiagnostics;

import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/* One-shot management owner for a Cartesian suite of independent radio runs.
   run/close/join and console work stay off EDT and acquisition; cancel only
   requests socket cancellation and never waits. No InspectorReceiver, analysis
   worker, FFT, waterfall or recorder participates in this acquisition path. */
final class DropSampleTest {
    enum State { CONNECTING, STARTING, INITIALISING, COUNTING, EVALUATING, COMPLETE, CANCELLED, FAILED }
    record Parameters(int durationSeconds, int bufferSize, int dataRate) {
        Parameters {
            if (durationSeconds <= 0 || bufferSize <= 0 || bufferSize > 1_048_576 || dataRate <= 0)
                throw new IllegalArgumentException("Invalid drop-sample test parameters");
        }
    }
    record Progress(int runId, int total, Parameters parameters, State state, double elapsedSeconds) { }

    // Package-private injection keeps state/deadline/cancellation tests independent
    // of radio hardware and allows deterministic immediate synthetic read traces.
    interface ReadSource extends AutoCloseable {
        void start();
        void requestStop();
        Throwable failure();
        long reportedOverruns();
        ReadDiagnostics diagnostics();
        @Override void close();
    }
    interface SourceFactory {
        ReadSource create(Configuration configuration, int logCapacity, ReadDiagnostics.ReadListener listener);
    }
    private static final class CompletionClock {
        volatile long hostNanos;
        volatile boolean observed;
    }

    private static final long WARMUP_NANOS = 2_000_000_000L;
    private static final long READ_WATCHDOG_NANOS = 10_000_000_000L;
    private final String address;
    private final List<Parameters> parameters;
    private final SourceFactory factory;
    private final LongSupplier nanoTime;
    private final long warmupNanos, readWatchdogNanos;
    private final PrintWriter console;
    private final AtomicBoolean started = new AtomicBoolean();
    private volatile boolean cancelled;
    private volatile ReadSource activeSource;

    DropSampleTest(String ipAddress) {
        this(ipAddress, plan(), DropSampleTest::radio, System::nanoTime,
                WARMUP_NANOS, READ_WATCHDOG_NANOS, new PrintWriter(System.out));
    }

    DropSampleTest(String ipAddress, List<Parameters> parameters, SourceFactory factory, LongSupplier nanoTime,
                   long warmupNanos, long readWatchdogNanos, PrintWriter console) {
        address = Objects.requireNonNull(ipAddress).trim();
        if (address.isEmpty() || warmupNanos < 0 || readWatchdogNanos <= 0)
            throw new IllegalArgumentException("Invalid test address or timing");
        this.parameters = List.copyOf(parameters);
        this.factory = Objects.requireNonNull(factory);
        this.nanoTime = Objects.requireNonNull(nanoTime);
        this.warmupNanos = warmupNanos;
        this.readWatchdogNanos = readWatchdogNanos;
        this.console = Objects.requireNonNull(console);
    }

    // The five explicitly listed buffer sizes produce 4 x 6 x 5 = 120 runs.
    // 524.288 is interpreted as 524288 complex samples, not a fractional buffer.
    static List<Parameters> plan() {
        List<Parameters> result = new ArrayList<>();
        for (int duration : new int[] {5, 10, 30, 60})
            for (int rate : new int[] {500_000, 1_000_000, 2_000_000, 3_000_000, 4_000_000, 5_000_000})
                for (int buffer : new int[] {4096, 65536, 131072, 262144, 524288})
                    result.add(new Parameters(duration, buffer, rate));
        return List.copyOf(result);
    }

    void cancel() {
        cancelled = true;
        ReadSource current = activeSource;
        if (current != null) current.requestStop();
    }

    void run(Consumer<Progress> progress, Consumer<DropSampleResult> results) {
        Objects.requireNonNull(progress);
        Objects.requireNonNull(results);
        if (!started.compareAndSet(false, true)) throw new IllegalStateException("Test suite is one-shot");
        for (int index = 0; index < parameters.size() && !cancelled; index++) {
            if (Thread.currentThread().isInterrupted()) { cancel(); break; }
            Parameters p = parameters.get(index);
            int runId = index + 1;
            progress.accept(new Progress(runId, parameters.size(), p, State.CONNECTING, 0));
            DropSampleResult result = runOne(runId, p, progress);
            results.accept(result);
            State finalState = switch (result.status()) {
                case COMPLETE -> State.COMPLETE;
                case FAILED -> State.FAILED;
                case CANCELLED -> State.CANCELLED;
            };
            progress.accept(new Progress(runId, parameters.size(), p, finalState,
                    Double.isFinite(result.effectiveDurationMs()) ? result.effectiveDurationMs() / 1000 : 0));
        }
    }

    private DropSampleResult runOne(int runId, Parameters p, Consumer<Progress> progress) {
        DropSampleMeasurement measurement = new DropSampleMeasurement(p.durationSeconds(), warmupNanos);
        AtomicReference<ReadSource> owner = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        long[] readCount = {0}; // acquisition writer only
        CompletionClock lastCompletionHost = new CompletionClock();
        int capacity = logCapacity(p, warmupNanos, readWatchdogNanos);
        ReadSource source = null;
        Throwable failure = null;
        try {
            source = factory.create(sourceConfiguration(address, p), capacity, (utc, elapsed, count) -> {
                // No boxing/allocation, I/O, logging or progress callbacks belong
                // here. The host clock below is only the management idle watchdog.
                lastCompletionHost.hostNanos = nanoTime.getAsLong();
                lastCompletionHost.observed = true;
                if (++readCount[0] > capacity) callbackFailure.compareAndSet(null,
                        new IllegalStateException("Preallocated read diagnostic log overflow; run is incomplete"));
                measurement.accept(elapsed, count);
                if (measurement.complete() || measurement.failure() != null || callbackFailure.get() != null || cancelled) {
                    ReadSource current = owner.get();
                    if (current != null) current.requestStop();
                }
            });
            owner.set(source);
            activeSource = source;
            if (!cancelled) source.start();
            long startHost = nanoTime.getAsLong(), lastProgress = Long.MIN_VALUE;
            while (!cancelled && !measurement.complete() && measurement.failure() == null && callbackFailure.get() == null) {
                failure = source.failure();
                if (failure != null) break;
                long now = nanoTime.getAsLong();
                boolean observedRead = lastCompletionHost.observed;
                long lastRead = lastCompletionHost.hostNanos;
                if (now - (observedRead ? lastRead : startHost) >= readWatchdogNanos) {
                    failure = new IllegalStateException(!observedRead ? "Timed out waiting for the first completed read" :
                            "Timed out waiting for a completed read in state " + measurement.stateAt(estimatedElapsed(measurement, now, lastRead)));
                    source.requestStop();
                    break;
                }
                if (lastProgress == Long.MIN_VALUE || now - lastProgress >= 100_000_000L) {
                    long elapsed = estimatedElapsed(measurement, now, lastRead);
                    progress.accept(new Progress(runId, parameters.size(), p, measurement.stateAt(elapsed),
                            measurement.countingElapsedSeconds(elapsed)));
                    lastProgress = now;
                }
                try { Thread.sleep(25); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); cancel(); }
            }
        } catch (RuntimeException e) {
            failure = e;
        } finally {
            if (source != null) {
                try { source.close(); }
                catch (RuntimeException e) { if (failure == null) failure = e; else if (failure != e) failure.addSuppressed(e); }
                // close joins startup/acquisition before inspecting diagnostic arrays.
                if (failure == null) failure = source.failure();
            }
            activeSource = null;
        }
        if (failure == null) failure = callbackFailure.get();
        if (failure == null) failure = measurement.failure();
        if (source != null && source.diagnostics().overflowReadCount() > 0 && failure == null)
            failure = new IllegalStateException("Preallocated diagnostic log overflow: " + source.diagnostics().overflowReadCount() + " reads unlogged");
        long overruns = source == null ? 0 : source.reportedOverruns();
        if (overruns > 0 && failure == null) failure = new IllegalStateException("IIOD reported " + overruns + " RX buffer overflow(s)");
        DropSampleResult result = result(runId, p, measurement, failure, overruns);
        if (source != null) logReads(result, measurement, source.diagnostics());
        else { console.println("Drop samples run " + runId + ": " + result.status() + ": " + result.detail()); console.flush(); }
        return result;
    }

    private long estimatedElapsed(DropSampleMeasurement measurement, long now, long lastHost) {
        return !measurement.received() ? 0 : measurement.lastReadNanos() + Math.max(0, now - lastHost);
    }

    private DropSampleResult result(int id, Parameters p, DropSampleMeasurement m, Throwable failure, long overruns) {
        DropSampleResult.Status status = cancelled && !m.complete() ? DropSampleResult.Status.CANCELLED :
                failure != null || !m.complete() ? DropSampleResult.Status.FAILED : DropSampleResult.Status.COMPLETE;
        double seconds = m.effectiveSeconds();
        double theoretical = seconds * p.dataRate();
        double lost = Math.max(0, theoretical - m.arrivedSamples());
        double average = m.arrivedSamples() / seconds;
        String detail;
        if (status == DropSampleResult.Status.CANCELLED) detail = "Cancelled; incomplete measurements are not loss estimates";
        else if (failure != null) detail = failure.toString();
        else if (!m.complete()) detail = "Acquisition ended without an evaluation read";
        else {
            double tolerance = Math.max(p.dataRate() * .002, theoretical * .001);
            double deficit = theoretical - m.arrivedSamples();
            boolean significant = deficit > tolerance;
            detail = (significant ? "Significant persistent delivery deficit; possible SDR/buffer interruption" :
                    "No significant persistent delivery deficit") +
                    ". Measured samples include the evaluation read (" + m.evaluationSamples() +
                    " samples). Threshold: max(0.1% expected samples, 2 ms at configured rate) = " +
                    Math.round(tolerance) + " samples. Final cumulative deficit including evaluation read: " +
                    Math.round(deficit) + " samples. Host timing estimates cannot prove hardware sample loss.";
        }
        if (overruns > 0) detail += "; IIOD RX overflow reports: " + overruns;
        return new DropSampleResult(id, p.durationSeconds(), p.dataRate(), p.bufferSize(), seconds * 1000,
                theoretical, m.arrivedSamples(), average, theoretical > 0 ? lost / theoretical * 100 : Double.NaN,
                lost / p.dataRate() * 1000, status, detail, m.evaluationSamples());
    }

    static int logCapacity(Parameters p, long warmup, long watchdog) {
        // Four times the full-buffer cadence tolerates short reads. Unexpected
        // more frequent reads explicitly fail at the bound, never grow memory.
        double seconds = p.durationSeconds() + (warmup + watchdog * 2.0) * 1e-9;
        return (int) Math.min(500_000, Math.max(1024, Math.ceil(seconds * p.dataRate() / p.bufferSize() * 4) + 1024));
    }

    static Configuration sourceConfiguration(String address, Parameters p) {
        Map<String, Object> values = new LinkedHashMap<>();
        String uri = address.trim();
        values.put("uri", uri.startsWith("ip:") ? uri : "ip:" + uri);
        values.put("frequency", 2_250_000_000L);
        int phyRate = p.dataRate() < 2_084_000 ? 4_000_000 : p.dataRate();
        values.put("sample_rate", phyRate);
        values.put("fpga_decimation", phyRate / p.dataRate());
        values.put("bandwidth", 5_000_000);
        values.put("buffer_size", p.bufferSize());
        values.put("block_samples", p.bufferSize());
        values.put("rx1_en", true);
        values.put("rx2_en", false);
        values.put("gain1", "manual");
        values.put("manual_gain1", 50);
        values.put("filter_source", "Off");
        return new Configuration(values);
    }

    private static ReadSource radio(Configuration configuration, int capacity, ReadDiagnostics.ReadListener listener) {
        ReadDiagnostics log = new ReadDiagnostics(capacity, listener);
        Fmcomms2SampleSource source = new Fmcomms2SampleSource(configuration, log);
        source.setSampleSink(ignored -> { throw new IllegalStateException("Diagnostic run delivered IQ samples"); });
        return new ReadSource() {
            @Override public void start() { source.start(); }
            @Override public void requestStop() { source.requestStop(); }
            @Override public void close() { source.close(); }
            @Override public Throwable failure() { return source.failure().orElse(null); }
            @Override public long reportedOverruns() {
                Object value = source.monitoringSnapshot().values().get("source.reported_overruns");
                return value instanceof Number number ? number.longValue() : 0;
            }
            @Override public ReadDiagnostics diagnostics() { return log; }
        };
    }

    private void logReads(DropSampleResult result, DropSampleMeasurement m, ReadDiagnostics log) {
        console.println("Drop samples run " + result.runId() + ": " + result.status() + ": " + result.detail());
        console.println("read,completion_utc,elapsed_ns,samples,gap_ms,cumulative_samples,window_expected_samples,window_arrived_samples,window_deficit_samples");
        long cumulative = 0, window = 0, previous = 0;
        for (int i = 0; i < log.capturedReadCount(); i++) {
            long elapsed = log.elapsedNanos(i), utc = log.completionTimeNanos(i);
            int count = log.sampleCount(i);
            cumulative += count;
            boolean inWindow = m.counting() && elapsed > m.startNanos() && (!m.complete() || elapsed <= m.endNanos());
            if (inWindow) window += count;
            double expected = m.counting() && elapsed >= m.startNanos() ?
                    (elapsed - m.startNanos()) * 1e-9 * result.dataRate() : Double.NaN;
            String completion = Instant.ofEpochSecond(Math.floorDiv(utc, 1_000_000_000L), Math.floorMod(utc, 1_000_000_000L)).toString();
            console.println((i + 1) + "," + completion + "," + elapsed + "," + count + "," +
                    (i == 0 ? 0 : (elapsed - previous) * 1e-6) + "," + cumulative + "," + expected + "," +
                    (Double.isFinite(expected) ? window : "") + "," + (Double.isFinite(expected) ? expected - window : ""));
            previous = elapsed;
        }
        console.println("Logged reads: " + log.capturedReadCount() + "; unlogged reads: " + log.overflowReadCount() +
                ". Cumulative window includes the evaluation read to distinguish recovered buffering from a remaining deficit.");
        console.flush();
    }
}
