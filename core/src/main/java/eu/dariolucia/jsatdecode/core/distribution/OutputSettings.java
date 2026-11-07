package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;

final class OutputSettings {
    private OutputSettings() {
    }

    static int capacity(Configuration c) {
        int value = c.asMap().containsKey("queue_capacity") ? c.getInt("queue_capacity") : 256;
        if (value <= 0) throw new ConfigurationException("queue_capacity must be positive");
        return value;
    }

    static OverflowPolicy policy(Configuration c) {
        try {
            return c.asMap().containsKey("overflow_policy") ?
                    OverflowPolicy.valueOf(c.getString("overflow_policy")) : OverflowPolicy.FAIL_RUNTIME;
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid overflow_policy", e);
        }
    }
}
