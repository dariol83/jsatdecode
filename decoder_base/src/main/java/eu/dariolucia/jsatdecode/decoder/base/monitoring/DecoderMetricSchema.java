package eu.dariolucia.jsatdecode.decoder.base.monitoring;

import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;

import java.util.Map;
import java.util.Set;

/**
 * Immutable mandatory key/type/unit schema. Mission descriptions and descriptor order stay local.
 */
public final class DecoderMetricSchema {
    private record Metadata(MonitoringType type, String unit) {}

    private static final Metadata LOCK = new Metadata(MonitoringType.STRING, "");
    private static final Metadata FRAMES = new Metadata(MonitoringType.LONG, "frames");
    private static final Metadata HERTZ = new Metadata(MonitoringType.DOUBLE, "Hz");
    private static final Map<String, Metadata> METRICS =
            Map.ofEntries(
                    Map.entry(
                            DecoderStatusKeys.SIGNAL_SNR_DB,
                            new Metadata(MonitoringType.DOUBLE, "dB")),
                    Map.entry(DecoderStatusKeys.CARRIER_LOCK, LOCK),
                    Map.entry(DecoderStatusKeys.CARRIER_OFFSET_HZ, HERTZ),
                    Map.entry(DecoderStatusKeys.SYMBOL_LOCK, LOCK),
                    Map.entry(
                            DecoderStatusKeys.SYMBOL_RATE_BAUD,
                            new Metadata(MonitoringType.DOUBLE, "baud")),
                    Map.entry(DecoderStatusKeys.FRAME_LOCK, LOCK),
                    Map.entry(DecoderStatusKeys.FRAME_GOOD, FRAMES),
                    Map.entry(DecoderStatusKeys.FRAME_BAD, FRAMES),
                    Map.entry(DecoderStatusKeys.FRAME_TOTAL, FRAMES),
                    Map.entry(DecoderStatusKeys.FRAME_RATE_HZ, HERTZ));

    private DecoderMetricSchema() {}

    /**
     * Creates metadata during class initialization or setup, never in DSP.
     *
     * @param key one of the ten DecoderStatusKeys
     * @param description mission-specific measurement, validation and availability semantics
     * @return immutable descriptor with the mandatory type and unit
     * @throws IllegalArgumentException if the key is outside the mandatory schema
     */
    public static MonitoringDescriptor descriptor(String key, String description) {
        Metadata metadata = METRICS.get(key);
        if (metadata == null)
            throw new IllegalArgumentException("Unknown mandatory decoder metric: " + key);
        return new MonitoringDescriptor(key, metadata.type(), metadata.unit(), description);
    }

    /**
     * Returns the immutable mandatory key set; no iteration order is specified.
     *
     * @return ten existing public API key strings
     */
    public static Set<String> keys() {
        return METRICS.keySet();
    }
}
