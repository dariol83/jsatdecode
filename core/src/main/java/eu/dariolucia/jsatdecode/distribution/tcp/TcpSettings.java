package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;

import java.net.URI;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Validated JTDP settings. Factories accept unprefixed component sections;
 * full configurations may also use shared distribution.tcp aliases.
 * Producer item queues and per-client network byte queues are independent.
 */
public record TcpSettings(
        int port, boolean keepalive, int maxClients, int sendBufferBytes,
        int receiveBufferBytes, boolean tcpNoDelay, long monitoringIntervalMs,
        long maxFrameLatencyMs, int maxFrameBuffer, int maxMessageBytes,
        int clientQueueBytes, int sourceId, OverflowPolicy overflowPolicy,
        int queueCapacity, String host, long reconnectIntervalMs, long maxReconnectIntervalMs) {

    public static final int DEFAULT_PORT = 6001;
    public static final int DEFAULT_MAX_CLIENTS = 2;
    public static final int DEFAULT_MONITORING_INTERVAL_S = 3;
    public static final int DEFAULT_MAX_FRAME_LATENCY_S = 1;
    public static final int DEFAULT_MAX_FRAME_BUFFER = 5;
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 1_048_576;
    public static final int DEFAULT_CLIENT_QUEUE_BYTES = 1_048_576;
    public static final int DEFAULT_SOURCE_ID = 1;
    public static final int DEFAULT_QUEUE_CAPACITY = 256;
    public static final OverflowPolicy DEFAULT_OVERFLOW_POLICY = OverflowPolicy.FAIL_RUNTIME;

    private static final Set<String> SHARED = Set.of("port", "keepalive", "max_clients",
            "send_buffer_bytes", "receive_buffer_bytes", "tcp_nodelay",
            "monitoring_interval_s", "max_frame_latency_s", "max_frame_buffer",
            "max_message_bytes", "client_queue_bytes", "source_id", "slow_client_policy");
    private static final Set<String> COMMON = Set.of("type", "receiver_id", "decoder", "port",
            "keepalive", "max_clients", "send_buffer_bytes", "receive_buffer_bytes",
            "tcp_nodelay", "max_message_bytes", "client_queue_bytes", "source_id",
            "slow_client_policy", "queue_capacity", "overflow_policy");

    /** Preserves the original explicit-settings constructor. */
    public TcpSettings(int port, boolean keepalive, int maxClients, int sendBufferBytes,
                       int receiveBufferBytes, boolean tcpNoDelay, long monitoringIntervalMs,
                       long maxFrameLatencyMs, int maxFrameBuffer, int maxMessageBytes,
                       int clientQueueBytes, int sourceId, OverflowPolicy overflowPolicy) {
        this(port, keepalive, maxClients, sendBufferBytes, receiveBufferBytes, tcpNoDelay,
                monitoringIntervalMs, maxFrameLatencyMs, maxFrameBuffer, maxMessageBytes,
                clientQueueBytes, sourceId, overflowPolicy, DEFAULT_QUEUE_CAPACITY,
                "localhost", 1000, 30000);
    }

    public TcpSettings {
        require(port >= 1 && port <= 65535, "port must be in [1,65535]");
        require(maxClients >= 1 && maxClients <= 65535, "max_clients must be in [1,65535]");
        require(sendBufferBytes >= 0 && receiveBufferBytes >= 0, "socket buffers must be nonnegative");
        require(monitoringIntervalMs >= 1 && monitoringIntervalMs <= 86_400_000,
                "monitoring interval must be in [1,86400000] ms");
        require(maxFrameLatencyMs >= 1 && maxFrameLatencyMs <= 86_400_000,
                "frame latency must be in [1,86400000] ms");
        require(maxFrameBuffer >= 1 && maxFrameBuffer <= 65535, "max_frame_buffer must be in [1,65535]");
        require(maxMessageBytes >= 40, "max_message_bytes must be at least 40");
        require(clientQueueBytes >= 1, "client_queue_bytes must be positive");
        require(sourceId >= 0 && sourceId <= 65535, "source_id must be in [0,65535]");
        require(queueCapacity >= 1, "queue_capacity must be positive");
        Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        require(host != null && !host.isBlank(), "host must not be blank");
        require(reconnectIntervalMs >= 1 && maxReconnectIntervalMs >= reconnectIntervalMs
                        && maxReconnectIntervalMs <= 86_400_000,
                "reconnect intervals must satisfy 1 <= min <= max <= 86400000 ms");
    }

    public static TcpSettings from(Configuration configuration) {
        return from(configuration, false);
    }

    /** Reads either an unprefixed factory section or a full properties map. */
    public static TcpSettings from(Configuration configuration, boolean monitoring) {
        Objects.requireNonNull(configuration, "configuration");
        Map<String, Object> input = configuration.asMap();
        if (input.keySet().stream().anyMatch(k -> k.startsWith("distribution."))) {
            configuration = normalizeConfiguration(configuration);
            Map<String, Object> selected = new LinkedHashMap<>();
            configuration.getSection("distribution.tcp").asMap().forEach((key, value) -> {
                if (applicable(key, monitoring)) selected.put(key, value);
            });
            selected.putAll(configuration.getSection("distribution." + (monitoring ? "monitoring" : "frames")).asMap());
            return read(new Configuration(selected), monitoring);
        }
        return read(configuration, monitoring);
    }

    private static TcpSettings read(Configuration c, boolean monitoring) {
        for (String key : c.asMap().keySet()) {
            if (!COMMON.contains(key) && !(monitoring ? key.equals("monitoring_interval_s")
                    : key.equals("max_frame_latency_s") || key.equals("max_frame_buffer"))) {
                throw new ConfigurationException("Unknown TCP setting: " + key);
            }
        }
        if (c.asMap().containsKey("type") && !c.getString("type").equals("tcp"))
            throw new ConfigurationException("TCP factory requires type=tcp");
        for (String identity : Set.of("receiver_id", "decoder"))
            if (c.asMap().containsKey(identity) && c.getString(identity).isBlank())
                throw new ConfigurationException(identity + " must not be blank");
        if (!text(c, "slow_client_policy", "DISCONNECT").equals("DISCONNECT"))
            throw new ConfigurationException("slow_client_policy supports only DISCONNECT");
        OverflowPolicy policy;
        try {
            policy = OverflowPolicy.valueOf(text(c, "overflow_policy", "FAIL_RUNTIME"));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid TCP overflow_policy", e);
        }
        try {
            return new TcpSettings(integer(c, "port", DEFAULT_PORT, 1, 65535),
                    bool(c, "keepalive", false), integer(c, "max_clients", DEFAULT_MAX_CLIENTS, 1, 65535),
                    optionalBuffer(c, "send_buffer_bytes"), optionalBuffer(c, "receive_buffer_bytes"),
                    bool(c, "tcp_nodelay", true),
                    monitoring ? interval(c, "monitoring_interval_s", DEFAULT_MONITORING_INTERVAL_S) : 3000,
                    monitoring ? 1000 : interval(c, "max_frame_latency_s", DEFAULT_MAX_FRAME_LATENCY_S),
                    monitoring ? DEFAULT_MAX_FRAME_BUFFER : integer(c, "max_frame_buffer", DEFAULT_MAX_FRAME_BUFFER, 1, 65535),
                    integer(c, "max_message_bytes", DEFAULT_MAX_MESSAGE_BYTES, 40, Integer.MAX_VALUE),
                    integer(c, "client_queue_bytes", DEFAULT_CLIENT_QUEUE_BYTES, 1, Integer.MAX_VALUE),
                    integer(c, "source_id", DEFAULT_SOURCE_ID, 0, 65535), policy,
                    integer(c, "queue_capacity", DEFAULT_QUEUE_CAPACITY, 1, Integer.MAX_VALUE),
                    "localhost", 1000, 30000);
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid TCP settings", e);
        }
    }

    /**
     * Resolves shared aliases into the selected TCP sections and validates their
     * shared listener settings before any factories acquire resources.
     * Explicit per-stream keys take precedence over shared aliases.
     */
    public static Configuration normalizeConfiguration(Configuration configuration) {
        Map<String, Object> values = new LinkedHashMap<>(configuration.asMap());
        Map<String, Object> shared = configuration.getSection("distribution.tcp").asMap();
        for (String key : shared.keySet())
            if (!SHARED.contains(key)) throw new ConfigurationException("Unknown shared TCP setting: " + key);
        // Validate every supplied shared value even if an explicit stream key overrides it.
        Map<String, Object> frameDefaults = new LinkedHashMap<>(), monitoringDefaults = new LinkedHashMap<>();
        shared.forEach((key, value) -> {
            if (applicable(key, false)) frameDefaults.put(key, value);
            if (applicable(key, true)) monitoringDefaults.put(key, value);
        });
        read(new Configuration(frameDefaults), false);
        read(new Configuration(monitoringDefaults), true);
        boolean frames = "tcp".equals(values.get("distribution.frames.type"));
        boolean monitoring = "tcp".equals(values.get("distribution.monitoring.type"));
        for (boolean monitor : new boolean[]{false, true}) {
            if (!(monitor ? monitoring : frames)) continue;
            String prefix = "distribution." + (monitor ? "monitoring" : "frames") + ".";
            shared.forEach((key, value) -> {
                if (applicable(key, monitor)) values.putIfAbsent(prefix + key, value);
            });
        }
        Configuration normalized = new Configuration(values);
        TcpSettings frameSettings = frames ? read(normalized.getSection("distribution.frames"), false) : null;
        TcpSettings monitorSettings = monitoring ? read(normalized.getSection("distribution.monitoring"), true) : null;
        if (frames && monitoring && !frameSettings.sharesListenerWith(monitorSettings))
            throw new ConfigurationException("TCP frame and monitoring listener settings must match");
        return normalized;
    }

    /** Checks fields shared by the socket listener; producer queues remain independent. */
    public boolean sharesListenerWith(TcpSettings other) {
        return port == other.port && keepalive == other.keepalive && maxClients == other.maxClients
                && sendBufferBytes == other.sendBufferBytes && receiveBufferBytes == other.receiveBufferBytes
                && tcpNoDelay == other.tcpNoDelay && maxMessageBytes == other.maxMessageBytes
                && clientQueueBytes == other.clientQueueBytes && sourceId == other.sourceId;
    }

    public static TcpSettings clientFrom(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Configuration c = configuration.asMap().keySet().stream().anyMatch(k -> k.startsWith("distribution.tcp."))
                ? configuration.getSection("distribution.tcp") : configuration;
        Set<String> allowed = Set.of("host", "endpoint", "port", "keepalive", "receive_buffer_bytes",
                "tcp_nodelay", "max_message_bytes", "reconnect_interval_ms", "max_reconnect_interval_ms");
        for (String key : c.asMap().keySet())
            if (!allowed.contains(key)) throw new ConfigurationException("Unknown TCP client setting: " + key);
        String host = text(c, "host", "localhost");
        int port = integer(c, "port", DEFAULT_PORT, 1, 65535);
        if (c.asMap().containsKey("endpoint")) {
            try {
                String endpoint = c.getString("endpoint");
                URI uri = URI.create(endpoint.contains("://") ? endpoint : "tcp://" + endpoint);
                if (!"tcp".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                        || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                        || (uri.getPath() != null && !uri.getPath().isEmpty())
                        || uri.getQuery() != null || uri.getFragment() != null)
                    throw new IllegalArgumentException("Expected host:port or tcp://host:port");
                host = uri.getHost();
                if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
                port = uri.getPort();
            } catch (IllegalArgumentException e) {
                throw new ConfigurationException("Invalid TCP endpoint", e);
            }
        }
        try {
            return new TcpSettings(port, bool(c, "keepalive", false), 1, 0,
                    optionalBuffer(c, "receive_buffer_bytes"), bool(c, "tcp_nodelay", true),
                    3000, 1000, 5,
                    integer(c, "max_message_bytes", DEFAULT_MAX_MESSAGE_BYTES, 40, Integer.MAX_VALUE),
                    DEFAULT_CLIENT_QUEUE_BYTES, 0, DEFAULT_OVERFLOW_POLICY, DEFAULT_QUEUE_CAPACITY, host,
                    integer(c, "reconnect_interval_ms", 1000, 1, 86_400_000),
                    integer(c, "max_reconnect_interval_ms", 30000, 1, 86_400_000));
        } catch (IllegalArgumentException e) {
            throw new ConfigurationException("Invalid TCP client settings", e);
        }
    }

    private static boolean applicable(String key, boolean monitoring) {
        return !key.equals(monitoring ? "max_frame_buffer" : "monitoring_interval_s")
                && !(monitoring && key.equals("max_frame_latency_s"));
    }

    private static long interval(Configuration c, String key, double fallback) {
        double seconds = c.asMap().containsKey(key) ? c.getDouble(key) : fallback;
        if (seconds < 0.001 || seconds > 86400)
            throw new ConfigurationException(key + " must be in [0.001,86400] seconds");
        BigDecimal decimal = c.asMap().containsKey(key)
                ? new BigDecimal(c.asMap().get(key).toString().trim()) : BigDecimal.valueOf(fallback);
        return Math.max(1, decimal.movePointRight(3).longValue());
    }

    private static int optionalBuffer(Configuration c, String key) {
        return c.asMap().containsKey(key) ? integer(c, key, 0, 1, Integer.MAX_VALUE) : 0;
    }

    private static int integer(Configuration c, String key, int fallback, int min, int max) {
        int value = c.asMap().containsKey(key) ? c.getInt(key) : fallback;
        if (value < min || value > max) throw new ConfigurationException(key + " must be in [" + min + "," + max + "]");
        return value;
    }

    private static boolean bool(Configuration c, String key, boolean fallback) {
        return c.asMap().containsKey(key) ? c.getBoolean(key) : fallback;
    }

    private static String text(Configuration c, String key, String fallback) {
        return c.asMap().containsKey(key) ? c.getString(key) : fallback;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
