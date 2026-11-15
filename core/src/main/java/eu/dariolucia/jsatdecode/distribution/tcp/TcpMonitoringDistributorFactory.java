package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;

import java.util.Objects;

/**
 * Factory for creating TCP monitoring distributors.
 *
 * <p>This factory is discovered via ServiceLoader and creates
 * {@link TcpMonitoringDistributor} instances. It reads configuration
 * from the {@code distribution.tcp} namespace.
 *
 * <h2>Configuration Properties</h2>
 * <p>The following properties are supported:
 * <ul>
 *   <li>{@code distribution.tcp.port} (6001) - TCP listen port</li>
 *   <li>{@code distribution.tcp.keepalive} (false) - Enable SO_KEEPALIVE</li>
 *   <li>{@code distribution.tcp.max_clients} (2) - Maximum concurrent clients</li>
 *   <li>{@code distribution.tcp.send_buffer_bytes} (OS default) - SO_SNDBUF size</li>
 *   <li>{@code distribution.tcp.receive_buffer_bytes} (OS default) - SO_RCVBUF size</li>
 *   <li>{@code distribution.tcp.tcp_nodelay} (true) - Enable TCP_NODELAY</li>
 *   <li>{@code distribution.tcp.monitoring_interval_s} (3) - Monitoring snapshot interval</li>
 *   <li>{@code distribution.tcp.max_message_bytes} (1048576) - Maximum message size</li>
 *   <li>{@code distribution.tcp.client_queue_bytes} (1048576) - Per-client queue limit</li>
 *   <li>{@code distribution.tcp.source_id} (1) - Source identifier</li>
 * </ul>
 *
 * <h2>ServiceLoader Integration</h2>
 * <p>To use this factory, create a file named:
 * <pre>META-INF/services/eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory</pre>
 * containing the line:
 * <pre>eu.dariolucia.jsatdecode.distribution.tcp.TcpMonitoringDistributorFactory</pre>
 *
 * @see TcpMonitoringDistributor
 * @see MonitoringDistributorFactory
 */
public final class TcpMonitoringDistributorFactory implements MonitoringDistributorFactory {

    /**
     * Creates a new factory instance.
     */
    public TcpMonitoringDistributorFactory() {
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor(
                "tcp",
                "TCP Monitoring Distributor",
                "1",
                "Distributes monitoring snapshots and events to TCP clients using JTDP v1 protocol"
        );
    }

    @Override
    public MonitoringDistributor create(Configuration configuration) throws ConfigurationException {
        Objects.requireNonNull(configuration, "configuration");

        // Create settings
        TcpSettings settings;
        try {
            settings = TcpSettings.from(configuration);
        } catch (Exception e) {
            throw new ConfigurationException("Invalid TCP distribution configuration", e);
        }

        // Get or create a shared server for this port
        // This ensures both FrameDistributor and MonitoringDistributor share the same server
        TcpServer sharedServer = TcpServerRegistry.getOrCreateServer(settings);

        // Create and return distributor with the shared server
        return new TcpMonitoringDistributor(settings, sharedServer);
    }
}
