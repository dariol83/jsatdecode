package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TcpSettingsTest {
    @Test
    void componentSectionsHonorEveryFrameSetting() {
        var values = new LinkedHashMap<String, Object>();
        values.put("type", "tcp");
        values.put("port", 43210);
        values.put("keepalive", true);
        values.put("max_clients", 65535);
        values.put("send_buffer_bytes", 4096);
        values.put("receive_buffer_bytes", 8192);
        values.put("tcp_nodelay", false);
        values.put("max_frame_latency_s", 0.025);
        values.put("max_frame_buffer", 7);
        values.put("max_message_bytes", 5000);
        values.put("client_queue_bytes", 6000);
        values.put("source_id", 65535);
        values.put("queue_capacity", 17);
        values.put("overflow_policy", "DROP_OLDEST");
        values.put("slow_client_policy", "DISCONNECT");
        var settings = TcpSettings.from(new Configuration(values), false);
        assertEquals(43210, settings.port());
        assertTrue(settings.keepalive());
        assertEquals(65535, settings.maxClients());
        assertEquals(4096, settings.sendBufferBytes());
        assertEquals(8192, settings.receiveBufferBytes());
        assertFalse(settings.tcpNoDelay());
        assertEquals(25, settings.maxFrameLatencyMs());
        assertEquals(7, settings.maxFrameBuffer());
        assertEquals(5000, settings.maxMessageBytes());
        assertEquals(6000, settings.clientQueueBytes());
        assertEquals(65535, settings.sourceId());
        assertEquals(17, settings.queueCapacity());
        assertEquals(OverflowPolicy.DROP_OLDEST, settings.overflowPolicy());
    }

    @Test
    void monitoringHasIndependentProducerQueueAndFractionalInterval() {
        var settings = TcpSettings.from(new Configuration(Map.of("monitoring_interval_s", .001,
                "queue_capacity", 11, "overflow_policy", "DROP_NEWEST")), true);
        assertEquals(1, settings.monitoringIntervalMs());
        assertEquals(11, settings.queueCapacity());
        assertEquals(OverflowPolicy.DROP_NEWEST, settings.overflowPolicy());
    }

    @Test
    void decimalIntervalsDoNotLoseMillisecondsToBinaryRounding() {
        for (var entry : Map.of("1.001", 1001L, "1.009", 1009L, "1.001999", 1001L).entrySet()) {
            assertEquals(entry.getValue().longValue(), TcpSettings.from(new Configuration(
                    Map.of("max_frame_latency_s", entry.getKey()))).maxFrameLatencyMs());
            assertEquals(entry.getValue().longValue(), TcpSettings.from(new Configuration(
                    Map.of("monitoring_interval_s", entry.getKey())), true).monitoringIntervalMs());
        }
    }

    @Test
    void sharedAliasesAreResolvedBeforeFactorySectioning() {
        var config = new Configuration(Map.of("distribution.frames.type", "tcp",
                "distribution.monitoring.type", "tcp", "distribution.tcp.port", 43211,
                "distribution.tcp.source_id", 27, "distribution.tcp.max_frame_buffer", 2,
                "distribution.tcp.monitoring_interval_s", .02,
                "distribution.frames.queue_capacity", 4, "distribution.monitoring.queue_capacity", 9));
        var normalized = TcpSettings.normalizeConfiguration(config);
        var frame = TcpSettings.from(normalized.getSection("distribution.frames"), false);
        var monitoring = TcpSettings.from(normalized.getSection("distribution.monitoring"), true);
        assertEquals(43211, frame.port());
        assertEquals(43211, monitoring.port());
        assertEquals(27, frame.sourceId());
        assertEquals(27, monitoring.sourceId());
        assertEquals(2, frame.maxFrameBuffer());
        assertEquals(20, monitoring.monitoringIntervalMs());
        assertEquals(4, frame.queueCapacity());
        assertEquals(9, monitoring.queueCapacity());
        assertTrue(frame.sharesListenerWith(monitoring));
    }

    @Test
    void canonicalKeysOverrideSharedAliasesAndDisabledStreamsStayUnselected() {
        var normalized = TcpSettings.normalizeConfiguration(new Configuration(Map.of(
                "distribution.frames.type", "tcp", "distribution.monitoring.type", "synthetic",
                "distribution.frames.port", 43213, "distribution.tcp.port", 43212)));
        assertEquals(43213, TcpSettings.from(normalized, false).port());
        assertEquals(Map.of("type", "synthetic"), normalized.getSection("distribution.monitoring").asMap());
    }

    @Test
    void mismatchedListenerSettingsAreRejectedBeforeConstruction() {
        for (var entry : Map.<String, Object>of("port", 43215, "keepalive", true,
                "max_clients", 3, "send_buffer_bytes", 2048, "receive_buffer_bytes", 2048,
                "tcp_nodelay", false, "max_message_bytes", 2048, "client_queue_bytes", 2048,
                "source_id", 2).entrySet()) {
            var values = new LinkedHashMap<String, Object>();
            values.put("distribution.frames.type", "tcp");
            values.put("distribution.monitoring.type", "tcp");
            values.put("distribution.monitoring." + entry.getKey(), entry.getValue());
            assertThrows(ConfigurationException.class,
                    () -> TcpSettings.normalizeConfiguration(new Configuration(values)), entry.getKey());
        }
    }

    @Test
    void unknownKeysUnsupportedPoliciesAndInvalidLimitsAreRejected() {
        for (var entry : Map.<String, Object>ofEntries(
                Map.entry("typo", 1), Map.entry("slow_client_policy", "DROP_NEWEST"),
                Map.entry("port", 0), Map.entry("max_clients", 0),
                Map.entry("send_buffer_bytes", 0), Map.entry("receive_buffer_bytes", -1),
                Map.entry("max_frame_buffer", 65536), Map.entry("max_frame_latency_s", 0),
                Map.entry("max_message_bytes", 39), Map.entry("client_queue_bytes", 0),
                Map.entry("source_id", 65536), Map.entry("queue_capacity", 0),
                Map.entry("overflow_policy", "drop_newest")).entrySet()) {
            assertThrows(ConfigurationException.class,
                    () -> TcpSettings.from(new Configuration(Map.of(entry.getKey(), entry.getValue()))),
                    entry.getKey());
        }
        for (double interval : new double[]{0, .0009, 86400.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(ConfigurationException.class, () -> TcpSettings.from(
                    new Configuration(Map.of("monitoring_interval_s", interval)), true));
        }
        assertThrows(ConfigurationException.class, () -> TcpSettings.normalizeConfiguration(
                new Configuration(Map.of("distribution.tcp.typo", 1))));
    }

    @Test
    void clientSettingsRetainEndpointReconnectAndReceiveMaximum() {
        var settings = TcpSettings.clientFrom(new Configuration(Map.of(
                "distribution.tcp.endpoint", "tcp://[::1]:43216",
                "distribution.tcp.reconnect_interval_ms", 7,
                "distribution.tcp.max_reconnect_interval_ms", 31,
                "distribution.tcp.max_message_bytes", 4096,
                "distribution.tcp.receive_buffer_bytes", 2048,
                "distribution.tcp.keepalive", true)));
        assertEquals("::1", settings.host());
        assertEquals(43216, settings.port());
        assertEquals(7, settings.reconnectIntervalMs());
        assertEquals(31, settings.maxReconnectIntervalMs());
        assertEquals(4096, settings.maxMessageBytes());
        assertEquals(2048, settings.receiveBufferBytes());
        assertTrue(settings.keepalive());
        for (String endpoint : new String[]{"host:0", "host:65536", "host", "tcp://host:1/path"})
            assertThrows(ConfigurationException.class,
                    () -> TcpSettings.clientFrom(new Configuration(Map.of("endpoint", endpoint))));
    }

    @Test
    void directSettingsCannotBypassStartupValidation() {
        assertThrows(IllegalArgumentException.class, () -> new TcpSettings(6001, false, 1,
                0, 0, true, 0, 1000, 5, 40, 40, 1, OverflowPolicy.FAIL_RUNTIME));
        assertThrows(IllegalArgumentException.class, () -> new TcpSettings(6001, false, 1,
                0, 0, true, 3000, 1000, 5, 39, 40, 1, OverflowPolicy.FAIL_RUNTIME));
    }
}
