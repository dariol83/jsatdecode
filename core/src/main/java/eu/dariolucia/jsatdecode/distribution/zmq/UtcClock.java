package eu.dariolucia.jsatdecode.distribution.zmq;

import java.time.Instant;
import java.util.function.LongSupplier;

/* Wire timestamps already carry UTC epoch nanoseconds. Never calibrate them a
   second time. Publication uses the current host UTC clock on the worker. */
final class UtcClock {
    private final LongSupplier utc;

    UtcClock() {
        this(UtcClock::epochNanos);
    }

    UtcClock(LongSupplier utc) {
        this.utc = utc;
    }

    long reception(long utcNanos) {
        return nonnegative(utcNanos);
    }

    long now() {
        return nonnegative(utc.getAsLong());
    }

    static long epochNanos() {
        return instant(Instant.now());
    }

    static long instant(Instant value) {
        return nonnegative(eu.dariolucia.jsatdecode.util.UtcTime.epochNanos(value));
    }

    static long nonnegative(long value) {
        if (value < 0) throw new IllegalArgumentException("Wire UTC timestamp must be nonnegative");
        return value;
    }
}
