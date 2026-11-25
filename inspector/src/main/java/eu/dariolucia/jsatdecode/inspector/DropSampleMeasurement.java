package eu.dariolucia.jsatdecode.inspector;

/* One acquisition writer, management readers. This is the entire per-read
   counting callback: no sample arrays, logging, Swing calls or timers/threads.
   Deadlines are monotonic and evaluated before classifying a completed read. */
final class DropSampleMeasurement {
    private final long durationNanos, warmupNanos;
    private volatile boolean received, counting, complete;
    private volatile long firstReadNanos, lastReadNanos, startNanos, endNanos, arrivedSamples;
    private volatile int evaluationSamples;
    private volatile Throwable failure;

    DropSampleMeasurement(int durationSeconds, long warmupNanos) {
        if (durationSeconds <= 0 || warmupNanos < 0) throw new IllegalArgumentException("Invalid test timing");
        this.durationNanos = Math.multiplyExact(durationSeconds, 1_000_000_000L);
        this.warmupNanos = warmupNanos;
    }

    void accept(long elapsedNanos, int samples) {
        if (complete || failure != null) return;
        if (samples < 0 || elapsedNanos < 0 || received && elapsedNanos < lastReadNanos) {
            failure = new IllegalStateException("Invalid read-completion time or sample count");
            return;
        }
        lastReadNanos = elapsedNanos;
        if (!received) {
            firstReadNanos = elapsedNanos;
            received = true;
            if (warmupNanos != 0) return;
        }
        if (!counting) {
            if (elapsedNanos - firstReadNanos < warmupNanos) return;
            // The INITIALISING read defines T1 and is intentionally not counted.
            startNanos = elapsedNanos;
            arrivedSamples = 0;
            counting = true;
        } else {
            try { arrivedSamples = Math.addExact(arrivedSamples, samples); }
            catch (ArithmeticException e) { failure = e; return; }
            if (elapsedNanos - startNanos >= durationNanos) {
                // The first completion at or beyond STOP_TIMER defines T2.
                // Its samples complete the measured interval (T1,T2].
                endNanos = elapsedNanos;
                evaluationSamples = samples;
                complete = true;
            }
        }
    }

    DropSampleTest.State stateAt(long elapsedNanos) {
        if (failure != null) return DropSampleTest.State.FAILED;
        if (complete) return DropSampleTest.State.COMPLETE;
        if (!received || !counting && elapsedNanos - firstReadNanos < warmupNanos) return DropSampleTest.State.STARTING;
        if (!counting) return DropSampleTest.State.INITIALISING;
        return elapsedNanos - startNanos >= durationNanos ? DropSampleTest.State.EVALUATING : DropSampleTest.State.COUNTING;
    }

    boolean received() { return received; }
    boolean counting() { return counting; }
    boolean complete() { return complete; }
    long firstReadNanos() { return firstReadNanos; }
    long lastReadNanos() { return lastReadNanos; }
    long startNanos() { return startNanos; }
    long endNanos() { return endNanos; }
    long arrivedSamples() { return arrivedSamples; }
    int evaluationSamples() { return evaluationSamples; }
    Throwable failure() { return failure; }
    double effectiveSeconds() { return complete ? (endNanos - startNanos) * 1e-9 : Double.NaN; }
    double countingElapsedSeconds(long elapsedNanos) {
        return counting ? Math.max(0, ((complete ? endNanos : elapsedNanos) - startNanos) * 1e-9) : 0;
    }
}
