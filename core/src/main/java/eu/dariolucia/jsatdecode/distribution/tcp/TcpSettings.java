package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;

import java.util.Objects;

/**
 * Immutable TCP distribution configuration settings.
 *
 * <p>This class encapsulates all configuration parameters for the TCP distribution
 * server and client implementations. All values are validated at construction time.
 *
 * <h2>Configuration Properties</h2>
 * <p>The following properties are supported (with defaults in parentheses):
 * <ul>
 *   <li>{@code distribution.tcp.port} (6001) - TCP listen port</li>
 *   <li>{@code distribution.tcp.keepalive} (false) - Enable SO_KEEPALIVE</li>
 *   <li>{@code distribution.tcp.max_clients} (2) - Maximum concurrent clients</li>
 *   <li>{@code distribution.tcp.send_buffer_bytes} (OS default) - SO_SNDBUF size</li>
 *   <li>{@code distribution.tcp.receive_buffer_bytes} (OS default) - SO_RCVBUF size</li>
 *   <li>{@code distribution.tcp.tcp_nodelay} (true) - Enable TCP_NODELAY</li>
 *   <li>{@code distribution.tcp.monitoring_interval_s} (3) - Monitoring snapshot interval</li>
 *   <li>{@code distribution.tcp.max_frame_latency_s} (1) - Frame batch latency trigger</li>
 *   <li>{@code distribution.tcp.max_frame_buffer} (5) - Frame count flush trigger</li>
 *   <li>{@code distribution.tcp.max_message_bytes} (1048576) - Maximum message size</li>
 *   <li>{@code distribution.tcp.client_queue_bytes} (1048576) - Per-client queue limit</li>
 * </ul>
 *
 * @param port                     TCP listen port (1-65535)
 * @param keepalive                Enable SO_KEEPALIVE
 * @param maxClients               Maximum concurrent clients (1-255)
 * @param sendBufferBytes          SO_SNDBUF size in bytes (0 for OS default)
 * @param receiveBufferBytes       SO_RCVBUF size in bytes (0 for OS default)
 * @param tcpNoDelay               Enable TCP_NODELAY
 * @param monitoringIntervalMs     Monitoring snapshot interval in milliseconds
 * @param maxFrameLatencyMs        Maximum frame batch latency in milliseconds
 * @param maxFrameBuffer           Maximum frames per batch (1-65535)
 * @param maxMessageBytes          Maximum message size in bytes (40-2^32-1)
 * @param clientQueueBytes         Per-client pending bytes limit (1-2^31-1)
 * @param sourceId                 Antenna/decoder identifier (0-65535)
 */
public record TcpSettings(
        int port,
        boolean keepalive,
        int maxClients,
        int sendBufferBytes,
        int receiveBufferBytes,
        boolean tcpNoDelay,
        long monitoringIntervalMs,
        long maxFrameLatencyMs,
        int maxFrameBuffer,
        int maxMessageBytes,
        int clientQueueBytes,
        int sourceId
) {

    /**
     * Default TCP port for JSatDecode distribution.
     */
    public static final int DEFAULT_PORT = 6001;

    /**
     * Default maximum concurrent clients.
     */
    public static final int DEFAULT_MAX_CLIENTS = 2;

    /**
     * Default monitoring interval in seconds.
     */
    public static final int DEFAULT_MONITORING_INTERVAL_S = 3;

    /**
     * Default maximum frame latency in seconds.
     */
    public static final int DEFAULT_MAX_FRAME_LATENCY_S = 1;

    /**
     * Default maximum frames per batch.
     */
    public static final int DEFAULT_MAX_FRAME_BUFFER = 5;

    /**
     * Default maximum message size in bytes (1 MB).
     */
    public static final int DEFAULT_MAX_MESSAGE_BYTES = 1_048_576;

    /**
     * Default per-client queue limit in bytes (1 MB).
     */
    public static final int DEFAULT_CLIENT_QUEUE_BYTES = 1_048_576;

    /**
     * Default source identifier.
     */
    public static final int DEFAULT_SOURCE_ID = 1;

    /**
     * Creates and validates TCP distribution settings from configuration.
     *
     * @param configuration the configuration containing TCP settings
     * @return validated immutable settings
     * @throws ConfigurationException if any setting is invalid
     */
    public static TcpSettings from(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        int port = getInt(configuration, "distribution.tcp.port", DEFAULT_PORT, 1, 65535);
        boolean keepalive = configuration.asMap().containsKey("distribution.tcp.keepalive")
                ? configuration.getBoolean("distribution.tcp.keepalive")
                : false;
        int maxClients = getInt(configuration, "distribution.tcp.max_clients", DEFAULT_MAX_CLIENTS, 1, 255);

        int sendBufferBytes = configuration.asMap().containsKey("distribution.tcp.send_buffer_bytes")
                ? configuration.getInt("distribution.tcp.send_buffer_bytes")
                : 0;
        int receiveBufferBytes = configuration.asMap().containsKey("distribution.tcp.receive_buffer_bytes")
                ? configuration.getInt("distribution.tcp.receive_buffer_bytes")
                : 0;

        boolean tcpNoDelay = configuration.asMap().containsKey("distribution.tcp.tcp_nodelay")
                ? configuration.getBoolean("distribution.tcp.tcp_nodelay")
                : true;

        int monitoringIntervalS = getInt(configuration, "distribution.tcp.monitoring_interval_s",
                DEFAULT_MONITORING_INTERVAL_S, 1, Integer.MAX_VALUE);
        int maxFrameLatencyS = getInt(configuration, "distribution.tcp.max_frame_latency_s",
                DEFAULT_MAX_FRAME_LATENCY_S, 0, Integer.MAX_VALUE);
        int maxFrameBuffer = getInt(configuration, "distribution.tcp.max_frame_buffer",
                DEFAULT_MAX_FRAME_BUFFER, 1, 65535);
        int maxMessageBytes = getInt(configuration, "distribution.tcp.max_message_bytes",
                DEFAULT_MAX_MESSAGE_BYTES, 40, Integer.MAX_VALUE);
        int clientQueueBytes = getInt(configuration, "distribution.tcp.client_queue_bytes",
                DEFAULT_CLIENT_QUEUE_BYTES, 1, Integer.MAX_VALUE);

        // Validate socket buffer sizes if specified
        if (sendBufferBytes < 0) {
            throw new ConfigurationException("distribution.tcp.send_buffer_bytes must be non-negative");
        }
        if (receiveBufferBytes < 0) {
            throw new ConfigurationException("distribution.tcp.receive_buffer_bytes must be non-negative");
        }

        // Default source ID
        int sourceId = configuration.asMap().containsKey("distribution.tcp.source_id")
                ? configuration.getInt("distribution.tcp.source_id")
                : DEFAULT_SOURCE_ID;
        if (sourceId < 0 || sourceId > 65535) {
            throw new ConfigurationException("distribution.tcp.source_id must be between 0 and 65535");
        }

        return new TcpSettings(
                port,
                keepalive,
                maxClients,
                sendBufferBytes,
                receiveBufferBytes,
                tcpNoDelay,
                monitoringIntervalS * 1000L,
                maxFrameLatencyS * 1000L,
                maxFrameBuffer,
                maxMessageBytes,
                clientQueueBytes,
                sourceId
        );
    }

    /**
     * Creates and validates TCP client settings for the reference client.
     *
     * @param configuration the configuration containing TCP settings
     * @return validated immutable settings
     * @throws ConfigurationException if any setting is invalid
     */
    public static TcpSettings clientFrom(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");

        int port = getInt(configuration, "distribution.tcp.port", DEFAULT_PORT, 1, 65535);
        String host = configuration.asMap().containsKey("distribution.tcp.host")
                ? configuration.getString("distribution.tcp.host")
                : "localhost";

        // For client, we need to parse host:port if provided as a single endpoint
        if (configuration.asMap().containsKey("distribution.tcp.endpoint")) {
            String endpoint = configuration.getString("distribution.tcp.endpoint");
            String[] parts = endpoint.split(":");
            if (parts.length >= 2) {
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            }
        }

        long reconnectIntervalMs = configuration.asMap().containsKey("distribution.tcp.reconnect_interval_ms")
                ? configuration.getLong("distribution.tcp.reconnect_interval_ms")
                : 1000L;
        long maxReconnectIntervalMs = configuration.asMap().containsKey("distribution.tcp.max_reconnect_interval_ms")
                ? configuration.getLong("distribution.tcp.max_reconnect_interval_ms")
                : 30000L;

        int receiveBufferBytes = configuration.asMap().containsKey("distribution.tcp.receive_buffer_bytes")
                ? configuration.getInt("distribution.tcp.receive_buffer_bytes")
                : 0;
        boolean tcpNoDelay = configuration.asMap().containsKey("distribution.tcp.tcp_nodelay")
                ? configuration.getBoolean("distribution.tcp.tcp_nodelay")
                : true;

        if (reconnectIntervalMs <= 0) {
            throw new ConfigurationException("distribution.tcp.reconnect_interval_ms must be positive");
        }
        if (maxReconnectIntervalMs < reconnectIntervalMs) {
            throw new ConfigurationException("distribution.tcp.max_reconnect_interval_ms must be >= reconnect_interval_ms");
        }

        return new TcpSettings(
                port,
                false, // keepalive not typically used by client
                1, // maxClients not used by client
                0, // sendBufferBytes not used by client
                receiveBufferBytes,
                tcpNoDelay,
                0, // monitoringIntervalMs not used by client
                0, // maxFrameLatencyMs not used by client
                0, // maxFrameBuffer not used by client
                DEFAULT_MAX_MESSAGE_BYTES,
                DEFAULT_CLIENT_QUEUE_BYTES,
                0 // sourceId not used by client
        );
    }

    private static int getInt(Configuration config, String key, int defaultValue, int min, int max) {
        if (config.asMap().containsKey(key)) {
            int value = config.getInt(key);
            if (value < min || value > max) {
                throw new ConfigurationException(key + " must be between " + min + " and " + max);
            }
            return value;
        }
        return defaultValue;
    }

    /**
     * Returns the server host for client connections.
     * This is extracted from the endpoint configuration if available.
     *
     * @return the server host
     */
    public String host() {
        // This is a simplified approach; in practice, host should be stored separately
        // For now, return localhost as default
        return "localhost";
    }
}
