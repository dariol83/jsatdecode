package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;

class TcpFactoryTest {
    @AfterEach void cleanup() { TcpServerRegistry.clearAll(); }

    @Test void serviceLoaderDiscoversBothFactories() {
        assertTrue(ServiceLoader.load(FrameDistributorFactory.class).stream()
                .anyMatch(provider -> provider.type().equals(TcpFrameDistributorFactory.class)));
        assertTrue(ServiceLoader.load(MonitoringDistributorFactory.class).stream()
                .anyMatch(provider -> provider.type().equals(TcpMonitoringDistributorFactory.class)));
    }

    @Test void tcpServiceRegistrationPreservesOtherOutputs() {
        var frames = ServiceLoader.load(FrameDistributorFactory.class).stream()
                .map(provider -> provider.get().descriptor().id()).collect(java.util.stream.Collectors.toSet());
        var monitoring = ServiceLoader.load(MonitoringDistributorFactory.class).stream()
                .map(provider -> provider.get().descriptor().id()).collect(java.util.stream.Collectors.toSet());
        assertTrue(frames.containsAll(java.util.Set.of("tcp", "synthetic", "zmq", "frame-file")));
        assertTrue(monitoring.containsAll(java.util.Set.of("tcp", "synthetic", "zmq")));
    }

    @Test void runtimeStyleSectionsStartOneSharedListenerAndReleaseItLast() throws Exception {
        int port = TcpTestSupport.port();
        var frame = (TcpFrameDistributor) new TcpFrameDistributorFactory().create(
                new Configuration(Map.of("type", "tcp", "port", port, "max_frame_buffer", 1)));
        var monitor = (TcpMonitoringDistributor) new TcpMonitoringDistributorFactory().create(
                new Configuration(Map.of("type", "tcp", "port", port, "monitoring_interval_s", .025)));
        assertSame(frame.getServer(), monitor.getServer());
        frame.start();
        monitor.start();
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(2000);
            assertEquals(1, TcpTestSupport.read(socket).getType());
            assertEquals(ComponentState.RUNNING, frame.getServer().state());
            frame.stop();
            assertTrue(monitor.getServer().isRunning(), "monitor still owns listener");
            assertTrue(TcpServerRegistry.hasServer(port));
            monitor.stop();
            assertFalse(TcpServerRegistry.hasServer(port));
        } finally { frame.close(); monitor.close(); }
        try (ServerSocket rebound = new ServerSocket(port)) { assertTrue(rebound.isBound()); }
    }

    @Test void closeBeforeStartReleasesConstructionReferences() throws Exception {
        int port = TcpTestSupport.port();
        var frame = new TcpFrameDistributorFactory().create(new Configuration(Map.of("port", port)));
        assertTrue(TcpServerRegistry.hasServer(port));
        frame.close();
        assertFalse(TcpServerRegistry.hasServer(port));
    }

    @Test void directFactoriesRejectConflictingSharedSettings() throws Exception {
        int port = TcpTestSupport.port();
        try (var frame = new TcpFrameDistributorFactory().create(new Configuration(Map.of("port", port)))) {
            assertThrows(IllegalArgumentException.class, () -> new TcpMonitoringDistributorFactory().create(
                    new Configuration(Map.of("port", port, "source_id", 2))));
        }
    }

    @Test void bindFailureReleasesTheRegistryReference() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            int port = occupied.getLocalPort();
            try (var frame = new TcpFrameDistributorFactory().create(new Configuration(Map.of("port", port)))) {
                assertThrows(Exception.class, frame::start);
            }
            assertFalse(TcpServerRegistry.hasServer(port));
        }
    }

    @Test void descriptorsRemainAvailableWithoutStarting() {
        try (var frame = new TcpFrameDistributor(TcpSettings.from(new Configuration(Map.of())));
             var monitor = new TcpMonitoringDistributor(TcpSettings.from(new Configuration(Map.of()), true))) {
            assertEquals("tcp", frame.descriptor().id());
            assertEquals("tcp", monitor.descriptor().id());
            assertEquals("tcp", new TcpFrameDistributorFactory().descriptor().id());
            assertEquals("tcp", new TcpMonitoringDistributorFactory().descriptor().id());
        }
    }
}
