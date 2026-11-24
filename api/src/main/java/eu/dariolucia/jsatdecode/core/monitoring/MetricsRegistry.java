package eu.dariolucia.jsatdecode.core.monitoring;

import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;

import java.util.*;

/**
 * Capacity-limited, thread-confined setup registry. Registration returns typed
 * handles for allocation-free producer updates. Build freezes the schema; no
 * runtime registration, growing histories or dynamic metric keys are allowed.
 * The built store and handles may be safely published to other threads.
 */
public final class MetricsRegistry {
    private final int capacity;
    private final List<MonitoringStore.Metric> metrics = new ArrayList<>();
    private final Set<String> keys = new HashSet<>();
    private MonitoringStore built;

    /**
     * Creates a setup registry with an explicit schema bound.
     *
     * @param maximumMetrics positive maximum registered metrics
     * @throws IllegalArgumentException for a nonpositive bound
     */
    public MetricsRegistry(int maximumMetrics) {
        if (maximumMetrics <= 0) throw new IllegalArgumentException("Invalid metric capacity");
        capacity = maximumMetrics;
    }

    /**
     * Registers a boolean value during setup.
     *
     * @param descriptor non-null metadata with BOOLEAN type and unique key
     * @param initial    initial boolean state
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.BooleanMetric registerBoolean(MonitoringDescriptor descriptor, boolean initial) {
        validate(descriptor, MonitoringType.BOOLEAN);
        MonitoringStore.BooleanMetric metric = new MonitoringStore.BooleanMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Registers an integer value during setup.
     *
     * @param descriptor non-null metadata with INTEGER type and unique key
     * @param initial    initial signed integer value
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.IntMetric registerInt(MonitoringDescriptor descriptor, int initial) {
        validate(descriptor, MonitoringType.INTEGER);
        MonitoringStore.IntMetric metric = new MonitoringStore.IntMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Registers a long value during setup.
     *
     * @param descriptor non-null metadata with LONG type and unique key
     * @param initial    initial signed long value
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.LongMetric registerLong(MonitoringDescriptor descriptor, long initial) {
        validate(descriptor, MonitoringType.LONG);
        MonitoringStore.LongMetric metric = new MonitoringStore.LongMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Registers a double value during setup.
     *
     * @param descriptor non-null metadata with DOUBLE type and unique key
     * @param initial    initial estimate, including NaN when unavailable
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.DoubleMetric registerDouble(MonitoringDescriptor descriptor, double initial) {
        validate(descriptor, MonitoringType.DOUBLE);
        MonitoringStore.DoubleMetric metric = new MonitoringStore.DoubleMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Registers a string value during setup.
     *
     * @param descriptor non-null metadata with STRING type and unique key
     * @param initial    non-null immutable text
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.StringMetric registerString(MonitoringDescriptor descriptor, String initial) {
        validate(descriptor, MonitoringType.STRING);
        MonitoringStore.StringMetric metric = new MonitoringStore.StringMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Registers an enum value during setup.
     *
     * @param descriptor non-null metadata with ENUM type and unique key
     * @param initial    non-null enum constant; future updates must retain its enum class
     * @return cacheable producer handle
     * @throws IllegalArgumentException for wrong type or duplicate key
     * @throws IllegalStateException    if frozen or at capacity
     */
    public MonitoringStore.EnumMetric registerEnum(MonitoringDescriptor descriptor, Enum<?> initial) {
        validate(descriptor, MonitoringType.ENUM);
        MonitoringStore.EnumMetric metric = new MonitoringStore.EnumMetric(descriptor, initial);
        add(metric);
        return metric;
    }

    /**
     * Freezes registration and returns the store; repeated calls return the same store.
     * Empty stores are valid. Call on the setup thread before publishing to workers.
     *
     * @return fixed-schema monitoring storage
     */
    public MonitoringStore build() {
        if (built == null) built = new MonitoringStore(metrics);
        return built;
    }

    private void validate(MonitoringDescriptor descriptor, MonitoringType expected) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (built != null) throw new IllegalStateException("Metric registration is frozen");
        if (descriptor.type() != expected) throw new IllegalArgumentException("Wrong metric type: " + descriptor.key());
        if (keys.contains(descriptor.key()))
            throw new IllegalArgumentException("Duplicate metric: " + descriptor.key());
        if (metrics.size() == capacity) throw new IllegalStateException("Metric capacity reached");
    }

    private void add(MonitoringStore.Metric metric) {
        metrics.add(metric);
        keys.add(metric.descriptor().key());
    }
}
