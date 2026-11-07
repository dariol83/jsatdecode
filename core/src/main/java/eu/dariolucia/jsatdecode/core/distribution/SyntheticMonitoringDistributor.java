package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

import java.util.function.Consumer;

/**
 * Bounded synthetic monitoring/event worker with a shared FIFO and explicit loss counters.
 * Retains no history; producers must be quiescent before management stop/close.
 */
public final class SyntheticMonitoringDistributor implements MonitoringDistributor {
    private final AsyncOutput<Object> output;

    /**
     * Creates output without starting a worker.
     *
     * @param capacity  shared queue capacity
     * @param policy    explicit policy, FAIL_RUNTIME preserves events by default
     * @param snapshots snapshot callback, must eventually return
     * @param events    event callback, must eventually return
     */
    public SyntheticMonitoringDistributor(int capacity, OverflowPolicy policy,
                                          Consumer<MonitoringSnapshot> snapshots, Consumer<RuntimeEvent> events) {
        java.util.Objects.requireNonNull(snapshots);
        java.util.Objects.requireNonNull(events);
        output = new AsyncOutput<>(capacity, policy, item -> {
            if (item instanceof MonitoringSnapshot snapshot) snapshots.accept(snapshot);
            else events.accept((RuntimeEvent) item);
        });
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("synthetic", "Synthetic monitoring", "1", "Bounded synthetic observations");
    }

    @Override
    public void publishMonitoring(MonitoringSnapshot snapshot) {
        output.offer(snapshot);
    }

    @Override
    public void publishEvent(RuntimeEvent event) {
        output.offer(event);
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        return output.snapshot();
    }

    @Override
    public void start() {
        output.start();
    }

    @Override
    public void stop() {
        output.stop();
    }

    @Override
    public void close() {
        output.close();
    }

    @Override
    public ComponentState state() {
        return output.state();
    }
}
