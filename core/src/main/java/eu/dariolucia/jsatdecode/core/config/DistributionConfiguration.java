package eu.dariolucia.jsatdecode.core.config;

import eu.dariolucia.jsatdecode.api.config.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Setup-time identity and flattened SatDecode configuration aliases. Explicit
 * distribution settings take precedence over aliases. This mapper knows no
 * transport classes and can also supply identity to future distributor factories.
 */
public final class DistributionConfiguration {
    private DistributionConfiguration() {
    }

    /**
     * Normalizes aliases without mutating the supplied settings. Network aliases
     * configure settings but do not select a transport implementation.
     *
     * @param configuration merged settings
     * @return immutable normalized global configuration
     */
    public static Configuration normalize(Configuration configuration) {
        Map<String, Object> values = new LinkedHashMap<>(configuration.asMap());
        alias(values, "decoder.type", "decoder.name");
        for (String kind : List.of("frames", "monitoring")) {
            String section = "distribution." + kind + ".";
            String legacy = "network." + (kind.equals("frames") ? "frames" : "monitor") + ".";
            for (String key : List.of("endpoint", "queue_capacity", "send_hwm", "linger_ms"))
                alias(values, section + key, legacy + key);
            alias(values, section + "receiver_id", "application.receiver_id");
            values.putIfAbsent(section + "receiver_id", "receiver-1");
            alias(values, section + "decoder", "decoder.type");
        }
        alias(values, "distribution.monitoring.interval_s", "application.monitor_interval_s");
        return new Configuration(values);
    }

    private static void alias(Map<String, Object> values, String target, String source) {
        if (values.containsKey(source)) values.putIfAbsent(target, values.get(source));
    }
}
