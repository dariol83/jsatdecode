package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;

import java.util.Set;

/* Parsed only at setup. TCP is the interoperable JeroMQ/libzmq transport;
   JeroMQ's IPC emulation is deliberately rejected rather than misrepresented. */
record ZmqSettings(String endpoint, String receiver, String decoder, int capacity, int hwm,
                   int lingerMs, OverflowPolicy overflow, long intervalNanos, int metricCapacity, int maxBytes) {
    static ZmqSettings read(Configuration c, boolean monitoring) {
        Set<String> allowed = Set.of("type", "endpoint", "receiver_id", "decoder", "queue_capacity", "send_hwm",
                "linger_ms", "overflow_policy", "interval_s", "metric_capacity", "max_message_bytes");
        for (String key : c.asMap().keySet())
            if (!allowed.contains(key)) throw new ConfigurationException("Unknown ZMQ setting: " + key);
        String endpoint = text(c, "endpoint", monitoring ? "tcp://*:5556" : "tcp://*:5555");
        if (!endpoint.matches("tcp://(?:\\*|[^/\\s:]+|\\[[0-9a-fA-F:]+\\]):(?:[0-9]+|\\*)"))
            throw new ConfigurationException("ZMQ endpoint must be a TCP bind address: " + endpoint);
        String port = endpoint.substring(endpoint.lastIndexOf(':') + 1);
        if (!port.equals("*")) {
            try {
                if (Integer.parseInt(port) < 1 || Integer.parseInt(port) > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new ConfigurationException("Invalid ZMQ TCP port: " + port, e);
            }
        }
        double interval = c.asMap().containsKey("interval_s") ? c.getDouble("interval_s") : 0.5;
        if (interval < 0.001 || interval > 86400)
            throw new ConfigurationException("interval_s must be in [0.001,86400]");
        int linger = c.asMap().containsKey("linger_ms") ? c.getInt("linger_ms") : 250;
        if (linger < 0 || linger > 60000) throw new ConfigurationException("linger_ms must be in [0,60000]");
        OverflowPolicy policy;
        try {
            policy = OverflowPolicy.valueOf(text(c, "overflow_policy", "FAIL_RUNTIME"));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid overflow_policy", e);
        }
        return new ZmqSettings(endpoint, text(c, "receiver_id", "receiver-1"), text(c, "decoder", null),
                positive(c, "queue_capacity", monitoring ? 256 : 4096), positive(c, "send_hwm", monitoring ? 256 : 4096),
                linger, policy, (long) (interval * 1_000_000_000), positive(c, "metric_capacity", 1024),
                positive(c, "max_message_bytes", 16 * 1024 * 1024));
    }

    private static String text(Configuration c, String key, String fallback) {
        String value = c.asMap().containsKey(key) ? c.getString(key) : fallback;
        if (value == null || value.isBlank()) throw new ConfigurationException("Missing/nonblank ZMQ setting: " + key);
        return value;
    }

    private static int positive(Configuration c, String key, int fallback) {
        int value = c.asMap().containsKey(key) ? c.getInt(key) : fallback;
        if (value <= 0) throw new ConfigurationException(key + " must be positive");
        return value;
    }
}
