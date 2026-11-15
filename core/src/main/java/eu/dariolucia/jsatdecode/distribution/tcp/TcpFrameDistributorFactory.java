package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;

/** Creates a TCP frame adapter with a reference-counted shared listener. */
public final class TcpFrameDistributorFactory implements FrameDistributorFactory {
    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("tcp", "TCP Frame Distributor", "1",
                "Distributes frames to TCP clients using JTDP v1");
    }

    @Override
    public FrameDistributor create(Configuration configuration) {
        TcpSettings settings = TcpSettings.from(configuration, false);
        TcpServer server = TcpServerRegistry.getOrCreateServer(settings);
        try {
            return new TcpFrameDistributor(settings, server, true);
        } catch (RuntimeException | Error e) {
            TcpServerRegistry.releaseServer(settings.port());
            throw e;
        }
    }
}
