package eu.dariolucia.jsatdecode.core.monitoring;

import eu.dariolucia.jsatdecode.api.monitoring.Monitorable;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-schema, thread-safe latest-value monitoring storage. Create via
 * MetricsRegistry during setup, then cache typed handles on producer components.
 * Primitive updates do not allocate or build maps. Polling observers allocate
 * immutable snapshots; values across different metrics are weakly consistent.
 * No callbacks, observer queues, locks or transport I/O occur on update paths.
 */
public final class MonitoringStore implements Monitorable {
    private final List<Metric> metrics;
    private final List<MonitoringDescriptor> descriptors;
    private final Map<String, Metric> byKey;

    MonitoringStore(List<Metric> metrics) {
        this.metrics = List.copyOf(metrics);
        descriptors = metrics.stream().map(Metric::descriptor).toList();
        Map<String, Metric> index = new LinkedHashMap<>();
        metrics.forEach(metric -> index.put(metric.descriptor().key(), metric));
        byKey = Map.copyOf(index);
    }

    /**
     * Materializes immutable latest values on the observer thread.
     *
     * @return snapshot timestamped in UTC epoch nanoseconds
     */
    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Metric metric : metrics) values.put(metric.descriptor().key(), metric.snapshotValue());
        return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), values);
    }

    /**
     * Returns immutable metadata in registration order.
     *
     * @return fixed descriptor collection
     */
    @Override
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
        return descriptors;
    }

    /**
     * Looks up a handle during setup; cache it rather than looking up on DSP.
     *
     * @param key non-null metric key
     * @return registered typed handle
     * @throws IllegalArgumentException if unregistered
     */
    public Metric metric(String key) {
        Metric metric = byKey.get(Objects.requireNonNull(key, "key"));
        if (metric == null) throw new IllegalArgumentException("Unknown metric: " + key);
        return metric;
    }

    /**
     * Returns the fixed runtime metric count.
     *
     * @return registered count
     */
    public int size() {
        return metrics.size();
    }

    /**
     * Shared immutable metadata for typed handles; values are safely published.
     */
    public abstract static sealed class Metric
            permits BooleanMetric, IntMetric, LongMetric, DoubleMetric, StringMetric, EnumMetric {
        private final MonitoringDescriptor descriptor;

        private Metric(MonitoringDescriptor descriptor) {
            this.descriptor = descriptor;
        }

        /**
         * Returns immutable registration metadata.
         *
         * @return metric descriptor
         */
        public final MonitoringDescriptor descriptor() {
            return descriptor;
        }

        abstract Object snapshotValue();
    }

    /**
     * Boolean state, independent of all other lock/state handles. Safe for concurrent updates and reads.
     */
    public static final class BooleanMetric extends Metric {
        private volatile boolean value;

        BooleanMetric(MonitoringDescriptor descriptor, boolean initial) {
            super(descriptor);
            this.value = initial;
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public boolean get() {
            return value;
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value new boolean state
         */
        public void set(boolean value) {
            this.value = value;
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }

    /**
     * Atomic signed integer gauge/counter; updates allocate no objects. Safe for concurrent updates and reads.
     */
    public static final class IntMetric extends Metric {
        private final AtomicInteger value;

        IntMetric(MonitoringDescriptor descriptor, int initial) {
            super(descriptor);
            this.value = new AtomicInteger(initial);
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public int get() {
            return value.get();
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value new signed integer value
         */
        public void set(int value) {
            this.value.set(value);
        }

        /**
         * Atomically adds to a counter, with Java signed wraparound on overflow.
         *
         * @param delta signed increment
         * @return new value
         */
        public int addAndGet(int delta) {
            return value.addAndGet(delta);
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }

    /**
     * Atomic signed long gauge/counter; updates allocate no objects. Safe for concurrent updates and reads.
     */
    public static final class LongMetric extends Metric {
        private final AtomicLong value;

        LongMetric(MonitoringDescriptor descriptor, long initial) {
            super(descriptor);
            this.value = new AtomicLong(initial);
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public long get() {
            return value.get();
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value new signed long value
         */
        public void set(long value) {
            this.value.set(value);
        }

        /**
         * Atomically adds to a counter, with Java signed wraparound on overflow.
         *
         * @param delta signed increment
         * @return new value
         */
        public long addAndGet(long delta) {
            return value.addAndGet(delta);
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }

    /**
     * Double gauge; NaN denotes an unavailable estimator, infinities are preserved. Safe for concurrent updates and reads.
     */
    public static final class DoubleMetric extends Metric {
        private volatile double value;

        DoubleMetric(MonitoringDescriptor descriptor, double initial) {
            super(descriptor);
            this.value = initial;
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public double get() {
            return value;
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value new estimate, including NaN when unavailable
         */
        public void set(double value) {
            this.value = value;
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }

    /**
     * Latest immutable text; no string construction is performed by the handle. Safe for concurrent updates and reads.
     */
    public static final class StringMetric extends Metric {
        private volatile String value;

        StringMetric(MonitoringDescriptor descriptor, String initial) {
            super(descriptor);
            this.value = Objects.requireNonNull(initial, "initial");
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public String get() {
            return value;
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value non-null immutable text
         * @throws NullPointerException if value is null
         */
        public void set(String value) {
            this.value = Objects.requireNonNull(value, "value");
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }

    /**
     * Latest enum constant; all updates must retain the initial enum declaring class. Safe for concurrent updates and reads.
     */
    public static final class EnumMetric extends Metric {
        private volatile Enum<?> value;

        EnumMetric(MonitoringDescriptor descriptor, Enum<?> initial) {
            super(descriptor);
            this.value = Objects.requireNonNull(initial, "initial");
        }

        /**
         * Returns the safely published current value.
         *
         * @return latest value
         */
        public Enum<?> get() {
            return value;
        }

        /**
         * Publishes a value without observer work or waiting.
         *
         * @param value non-null constant from the initial enum class
         * @throws NullPointerException     if value is null
         * @throws IllegalArgumentException if the enum class differs
         */
        public void set(Enum<?> value) {
            Objects.requireNonNull(value, "value");
            if (value.getDeclaringClass() != this.value.getDeclaringClass()) {
                throw new IllegalArgumentException("Enum class cannot change");
            }
            this.value = value;
        }

        @Override
        Object snapshotValue() {
            return get();
        }
    }
}
