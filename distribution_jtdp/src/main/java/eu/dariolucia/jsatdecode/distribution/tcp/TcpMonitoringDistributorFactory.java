package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;

/** Creates a TCP monitoring adapter with a reference-counted shared listener. */
public final class TcpMonitoringDistributorFactory implements MonitoringDistributorFactory {
    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("tcp", "TCP Monitoring Distributor", "1",
                "Distributes monitoring and events to TCP clients using JTDP v1");
    }

    @Override
    public MonitoringDistributor create(Configuration configuration) {
        TcpSettings settings = TcpSettings.from(configuration, true);
        TcpServer server = TcpServerRegistry.getOrCreateServer(settings);
        try {
            return new TcpMonitoringDistributor(settings, server, true);
        } catch (RuntimeException | Error e) {
            TcpServerRegistry.releaseServer(settings.port());
            throw e;
        }
    }
}
