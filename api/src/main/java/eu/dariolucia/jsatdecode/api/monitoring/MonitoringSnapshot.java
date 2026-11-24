package eu.dariolucia.jsatdecode.api.monitoring;

import eu.dariolucia.jsatdecode.api.config.ImmutableValues;

import java.util.Map;

/**
 * Immutable, shareable observation assembled only when polled, outside DSP.
 * Individual metrics may be weakly consistent; no lock state is inferred.
 *
 * @param timestampNanos UTC epoch-nanosecond snapshot time
 * @param values         metric values; NaN denotes unavailable SNR
 */
public record MonitoringSnapshot(long timestampNanos, Map<String, Object> values) {
    /**
     * Recursively snapshots metric values.
     *
     * @param timestampNanos UTC epoch-nanosecond timestamp
     * @param values         non-null metric map
     */
    public MonitoringSnapshot {
        values = ImmutableValues.copy(values);
    }
}