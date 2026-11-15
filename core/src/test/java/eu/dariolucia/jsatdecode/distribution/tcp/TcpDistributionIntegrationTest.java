package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.tcp.client.TcpClient;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.EventMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.FrameRecord;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.MonitoringMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the TCP distribution implementation.
 * Tests the complete flow from distributor to client.
 */
public final class TcpDistributionIntegrationTest {

    private static final int TEST_PORT = 16001;

    @BeforeEach
    public void setUp() {
        // Clean up any lingering connections and server registry
        eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry.clearAll();
    }

    @AfterEach
    public void tearDown() {
        // Clean up
        eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry.clearAll();
    }

    @Test
    public void testFrameDistributorLifecycle() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpFrameDistributor distributor = new TcpFrameDistributor(settings);

        assertEquals(ComponentState.CONFIGURED, distributor.state());

        distributor.start();
        assertEquals(ComponentState.RUNNING, distributor.state());

        distributor.stop();
        assertEquals(ComponentState.STOPPED, distributor.state());

        distributor.close();
        assertEquals(ComponentState.CLOSED, distributor.state());
    }

    @Test
    public void testFrameDistributorDistribute() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 1,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpFrameDistributor distributor = new TcpFrameDistributor(settings);
        distributor.start();

        try {
            // Distribute a frame
            AnnotatedFrame frame = new AnnotatedFrame(
                    new byte[] {1, 2, 3, 4, 5},
                    System.nanoTime(),
                    Map.of()
            );

            distributor.distribute(frame);

            // Check monitoring snapshot
            var snapshot = distributor.monitoringSnapshot();
            assertTrue(snapshot.messagesReceived() > 0);

        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testMonitoringDistributorLifecycle() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 2,
                false,
                2,
                0,
                0,
                true,
                3000,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings);

        assertEquals(ComponentState.CONFIGURED, distributor.state());

        distributor.start();
        assertEquals(ComponentState.RUNNING, distributor.state());

        distributor.stop();
        assertEquals(ComponentState.STOPPED, distributor.state());

        distributor.close();
        assertEquals(ComponentState.CLOSED, distributor.state());
    }

    @Test
    public void testMonitoringDistributorPublish() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 3,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings);
        distributor.start();

        try {
            // Publish a monitoring snapshot
            MonitoringSnapshot snapshot = new MonitoringSnapshot(
                    System.nanoTime(),
                    Map.of(
                            "carrier_state", 1,
                            "symbol_state", 1,
                            "frame_state", 1,
                            "center_frequency_hz", 1000000L,
                            "snr_db", 25.5
                    )
            );

            distributor.publishMonitoring(snapshot);

            // Check monitoring snapshot
            var distSnapshot = distributor.monitoringSnapshot();
            assertTrue(distSnapshot.messagesReceived() > 0);

        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testClientConnectDisconnect() throws Exception {
        // Start a frame distributor
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 4,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpFrameDistributor distributor = new TcpFrameDistributor(settings);
        distributor.start();

        try {
            // Create and connect client
            TcpClient client = new TcpClient("localhost", TEST_PORT + 4, settings, 1000, 30000);
            
            AtomicInteger statusCount = new AtomicInteger(0);
            CountDownLatch connectedLatch = new CountDownLatch(1);
            
            client.setStatusListener(msg -> {
                statusCount.incrementAndGet();
                if (msg.contains("Connected")) {
                    connectedLatch.countDown();
                }
            });
            
            client.connect();
            
            // Wait for connection
            assertTrue(connectedLatch.await(5, TimeUnit.SECONDS));
            assertTrue(client.isConnected());
            assertTrue(client.isRunning());
            
            client.disconnect();
            assertFalse(client.isConnected());
            
        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testClientReconnect() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 5,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpFrameDistributor distributor = new TcpFrameDistributor(settings);
        distributor.start();

        try {
            TcpClient client = new TcpClient("localhost", TEST_PORT + 5, settings, 100, 1000);
            
            AtomicInteger statusCount = new AtomicInteger(0);
            client.setStatusListener(msg -> statusCount.incrementAndGet());
            
            client.connect();
            
            // Wait a bit for connection
            Thread.sleep(500);
            
            // Should have at least one status update (connected)
            assertTrue(statusCount.get() >= 1, "Expected at least one status update, got " + statusCount.get());
            
            client.disconnect();
            
        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testClientReceiveFrames() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 6,
                false,
                2,
                0,
                0,
                true,
                0,
                100,  // maxFrameLatencyMs - 100ms latency trigger
                2,   // maxFrameBuffer - flush after 2 frames
                1048576,
                1048576,
                1
        );

        TcpFrameDistributor distributor = new TcpFrameDistributor(settings);
        distributor.start();

        // Give server time to start listening
        Thread.sleep(500);

        try {
            TcpClient client = new TcpClient("localhost", TEST_PORT + 6, settings, 1000, 30000);
            
            CountDownLatch frameLatch = new CountDownLatch(1);
            AtomicInteger frameCount = new AtomicInteger(0);
            
            client.setFrameListener(frames -> {
                frameCount.addAndGet(frames.size());
                frameLatch.countDown();
            });
            
            client.connect();
            
            // Wait for connection to be established
            int retries = 0;
            while (!client.isConnected() && retries < 50) {
                Thread.sleep(100);
                retries++;
            }
            assertTrue(client.isConnected(), "Client should be connected");
            
            // Distribute frames
            AnnotatedFrame frame1 = new AnnotatedFrame(
                    new byte[] {1, 2, 3}, System.nanoTime(), Map.of()
            );
            AnnotatedFrame frame2 = new AnnotatedFrame(
                    new byte[] {4, 5, 6}, System.nanoTime(), Map.of()
            );
            
            distributor.distribute(frame1);
            distributor.distribute(frame2);
            
            // Wait for frames
            assertTrue(frameLatch.await(5, TimeUnit.SECONDS));
            assertEquals(2, frameCount.get());
            
            client.disconnect();
            
        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testClientReceiveMonitoring() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 7,
                false,
                2,
                0,
                1048576,
                true,
                0,
                0,
                2,
                1048576,
                1048576,
                1
        );

        TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings);
        distributor.start();

        // Give server time to start listening
        Thread.sleep(1000);

        try {
            TcpClient client = new TcpClient("localhost", TEST_PORT + 7, settings, 1000, 30000);
            
            CountDownLatch monitoringLatch = new CountDownLatch(1);
            AtomicInteger monitoringCount = new AtomicInteger(0);
            
            client.setMonitoringListener(msg -> {
                monitoringCount.incrementAndGet();
                monitoringLatch.countDown();
            });
            
            client.connect();
            
            // Wait for connection to be established
            int retries = 0;
            while (!client.isConnected() && retries < 50) {
                Thread.sleep(100);
                retries++;
            }
            assertTrue(client.isConnected(), "Client should be connected");
            
            // Publish monitoring
            MonitoringSnapshot snapshot = new MonitoringSnapshot(
                    System.nanoTime(),
                    Map.of(
                            "carrier_state", 1,
                            "symbol_state", 1,
                            "frame_state", 1,
                            "center_frequency_hz", 1000000L
                    )
            );
            
            distributor.publishMonitoring(snapshot);
            
            // Wait for monitoring message
            assertTrue(monitoringLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, monitoringCount.get());
            
            client.disconnect();
            
        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testClientReceiveEvent() throws Exception {
        TcpSettings settings = new TcpSettings(
                TEST_PORT + 8,
                false,
                2,
                0,
                0,
                true,
                0,
                0,
                5,
                1048576,
                1048576,
                1
        );

        TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings);
        distributor.start();

        try {
            TcpClient client = new TcpClient("localhost", TEST_PORT + 8, settings, 1000, 30000);
            
            CountDownLatch eventLatch = new CountDownLatch(1);
            AtomicInteger eventCount = new AtomicInteger(0);
            
            client.setEventListener(event -> {
                eventCount.incrementAndGet();
                eventLatch.countDown();
            });
            
            client.connect();
            
            // Wait for connection to be established
            int retries = 0;
            while (!client.isConnected() && retries < 50) {
                Thread.sleep(100);
                retries++;
            }
            assertTrue(client.isConnected(), "Client should be connected");
            
            // Publish event
            eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent event = 
                    new eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent(
                            System.nanoTime(),
                            eu.dariolucia.jsatdecode.api.distribution.EventSeverity.INFO,
                            "test",
                            "decoder.started",
                            "Test event",
                            Map.of()
                    );
            
            distributor.publishEvent(event);
            
            // Wait for event
            assertTrue(eventLatch.await(5, TimeUnit.SECONDS));
            assertEquals(1, eventCount.get());
            
            client.disconnect();
            
        } finally {
            distributor.stop();
            distributor.close();
        }
    }

    @Test
    public void testTcpSettingsFromConfiguration() {
        java.util.Map<String, Object> configMap = new java.util.HashMap<>();
        configMap.put("distribution.tcp.port", 6002);
        configMap.put("distribution.tcp.max_clients", 5);
        configMap.put("distribution.tcp.max_frame_buffer", 10);
        configMap.put("distribution.tcp.max_message_bytes", 2097152);
        
        eu.dariolucia.jsatdecode.api.config.Configuration config = 
                new eu.dariolucia.jsatdecode.api.config.Configuration(configMap);
        
        TcpSettings settings = TcpSettings.from(config);
        
        assertEquals(6002, settings.port());
        assertEquals(5, settings.maxClients());
        assertEquals(10, settings.maxFrameBuffer());
        assertEquals(2097152, settings.maxMessageBytes());
    }

    @Test
    public void testTcpSettingsDefault() {
        java.util.Map<String, Object> configMap = new java.util.HashMap<>();
        
        eu.dariolucia.jsatdecode.api.config.Configuration config = 
                new eu.dariolucia.jsatdecode.api.config.Configuration(configMap);
        
        TcpSettings settings = TcpSettings.from(config);
        
        assertEquals(TcpSettings.DEFAULT_PORT, settings.port());
        assertEquals(TcpSettings.DEFAULT_MAX_CLIENTS, settings.maxClients());
        assertEquals(TcpSettings.DEFAULT_MAX_FRAME_BUFFER, settings.maxFrameBuffer());
        assertEquals(TcpSettings.DEFAULT_MAX_MESSAGE_BYTES, settings.maxMessageBytes());
    }
}
