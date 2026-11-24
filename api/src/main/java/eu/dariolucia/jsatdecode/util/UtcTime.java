package eu.dariolucia.jsatdecode.util;

import java.time.Instant;

/**
 * Shared UTC epoch-nanosecond clock for sample, frame and observation timestamps.
 * Anchors once to the host UTC clock, then advances with the monotonic clock so
 * DSP reads allocate nothing and clock corrections cannot reorder observations.
 * Accuracy depends on host synchronization at initialization; subsequent host
 * clock changes require a new process to recalibrate this timeline. This clock
 * supplies UTC instants, never raw process-relative nanoTime values. Durations
 * and scheduling must continue to use System.nanoTime directly.
 */
public final class UtcTime {
    private static final long EPOCH_ANCHOR = epochNanos(Instant.now());
    private static final long MONOTONIC_ANCHOR = System.nanoTime();

    private UtcTime() {
    }

    /**
     * Returns host-anchored UTC nanoseconds since 1970-01-01T00:00:00Z.
     * Safe for concurrent and DSP callers; no allocation, blocking or timezone lookup.
     *
     * @return UTC epoch nanoseconds
     * @throws ArithmeticException if the epoch-nanosecond representation overflows
     */
    public static long nowNanos() {
        return Math.addExact(EPOCH_ANCHOR, System.nanoTime() - MONOTONIC_ANCHOR);
    }

    /**
     * Converts an absolute instant without a default timezone.
     *
     * @param instant UTC timeline instant, including offset-normalized instants
     * @return signed nanoseconds since the Unix epoch
     * @throws ArithmeticException outside the signed-long epoch-nanosecond range
     */
    public static long epochNanos(Instant instant) {
        return Math.addExact(Math.multiplyExact(instant.getEpochSecond(), 1_000_000_000L), instant.getNano());
    }
}
