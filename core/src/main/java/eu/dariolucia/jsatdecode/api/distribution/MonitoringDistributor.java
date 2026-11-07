package eu.dariolucia.jsatdecode.api.distribution;

import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

/**
 * Protocol-neutral observational output. Submissions while RUNNING are bounded
 * and nonblocking, callable concurrently. Worker threads own transport I/O.
 * Status may be coalesced. Events are preserved by default; dropping requires
 * an explicit configured policy and counters. Queue overflow never waits for DSP.
 */
public interface MonitoringDistributor extends LifecycleComponent {
    /**
     * Returns metadata.
     *
     * @return immutable descriptor
     */
    MonitoringDistributorDescriptor descriptor();

    /**
     * Offers a snapshot without waiting; stale snapshots may be coalesced.
     *
     * @param snapshot non-null immutable metrics
     */
    void publishMonitoring(MonitoringSnapshot snapshot);

    /**
     * Offers an event without waiting; any policy-permitted loss is counted.
     *
     * @param event non-null immutable event
     */
    void publishEvent(RuntimeEvent event);

    /**
     * Returns safely published counters without transport I/O.
     *
     * @return immutable distribution status
     */
    DistributionSnapshot monitoringSnapshot();
}
