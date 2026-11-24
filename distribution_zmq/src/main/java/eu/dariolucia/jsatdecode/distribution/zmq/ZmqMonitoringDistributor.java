package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.OutputException;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SatDecode v1 monitoring/event PUB-bind output. The worker merges snapshots
 * into bounded latest metric state, emits a full status initially, periodically
 * (default 0.5 seconds) and after draining at stop. Events retain their own
 * independent sequence and FIFO submission order. No lock state is inferred.
 * Concurrent submissions never perform serialization or network I/O; lifecycle
 * is management-serialized with producers quiesced before stop/close.
 */
public final class ZmqMonitoringDistributor extends ZmqWorker<Object> implements MonitoringDistributor {
    private record Event(RuntimeEvent value, long sequence) {
    }

    private final AtomicLong eventSequence = new AtomicLong();
    private final Map<String, Object> metrics = new LinkedHashMap<>();
    private final Map<String, Long> timestamps = new LinkedHashMap<>();
    private long statusSequence, nextStatus, nextEventSequence;

    /**
     * Validates component settings without allocating a socket or worker.
     *
     * @param configuration unprefixed settings; decoder identity is required
     */
    public ZmqMonitoringDistributor(Configuration configuration) {
        super(ZmqSettings.read(configuration, true));
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("zmq", "SatDecode ZMQ monitoring", "1", "SatDecode v1 status/event canonical CBOR PUB");
    }

    @Override
    public void publishMonitoring(MonitoringSnapshot snapshot) {
        offer(snapshot);
    }

    @Override
    public void publishEvent(RuntimeEvent event) {
        Objects.requireNonNull(event);
        offer(new Event(event, sequence(eventSequence)));
    }

    @Override
    void opened() {
        publishStatus();
        nextStatus = System.nanoTime() + settings.intervalNanos();
    }

    @Override
    void process(Object value) {
        if (value instanceof Event(RuntimeEvent value1, long sequence1)) {
            long sequence = orderedSequence(nextEventSequence, sequence1);
            send(wire.event(value1, sequence));
            nextEventSequence = sequence + 1;
            return;
        }
        MonitoringSnapshot snapshot = (MonitoringSnapshot) value;
        // Validate the entire schema expansion before retaining any new metric.
        long additional = snapshot.values().keySet().stream().filter(k -> !metrics.containsKey(k)).count();
        if (additional > settings.metricCapacity() - metrics.size())
            throw new OutputException("ZMQ metric_capacity exceeded");
        long timestamp = wire.clock.reception(snapshot.timestampNanos());
        snapshot.values().forEach((k, v) -> {
            metrics.put(k, v);
            timestamps.put(k, timestamp);
        });
    }

    @Override
    void tick() {
        long now = System.nanoTime();
        if (now - nextStatus >= 0) {
            publishStatus();
            nextStatus = now + settings.intervalNanos();
        }
    }

    @Override
    void finished() {
        publishStatus();
    }

    private void publishStatus() {
        if (statusSequence < 0) throw new OutputException("ZMQ status sequence exhausted");
        try {
            send(wire.status(metrics, timestamps, statusSequence++));
        } catch (RuntimeException e) {
            lost();
            throw e;
        }
    }
}
