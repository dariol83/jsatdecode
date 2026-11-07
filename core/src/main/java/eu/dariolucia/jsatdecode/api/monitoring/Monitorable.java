package eu.dariolucia.jsatdecode.api.monitoring;

import java.util.Collection;

/**
 * Pull-based observer contract; implementations support concurrent readers without stalling DSP.
 */
public interface Monitorable {
    /**
     * Materializes observations on the polling caller, never via DSP callbacks.
     *
     * @return immutable snapshot
     */
    MonitoringSnapshot monitoringSnapshot();

    /**
     * Returns metric metadata.
     *
     * @return immutable collection of descriptors
     */
    Collection<MonitoringDescriptor> monitoringDescriptors();
}