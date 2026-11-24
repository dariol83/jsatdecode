package eu.dariolucia.jsatdecode.distribution.tcp.client;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.ProtocolException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class TcpClientConformanceTest {
    private static byte[] hex(String wire) { return HexFormat.of().parseHex(wire.replace(" ", "")); }
    private static final byte[] EVENT = hex("a502123400000019 0201020304050607087c00000000000001");
    private static final byte[] MONITORING = hex("a501123400000028 fc00000000000001 80000000 7fc00000 ffffffff 00000000 ffffffff 80000000");

    private static TcpClient client(int port) {
        return new TcpClient(TcpSettings.clientFrom(new Configuration(Map.of(
                "endpoint", "tcp://127.0.0.1:" + port, "max_message_bytes", 40,
                "reconnect_interval_ms", 10, "max_reconnect_interval_ms", 40))));
    }

    @Test
    void protocolErrorsCloseSocketAndStartFreshSessions() throws Exception {
        byte[][] malformed = {
                hex("0000000000000008"), hex("a501000000000027"), hex("a500000000000008"),
                hex("a5000000000000160300000000000000000000000100"), hex("a5ff000080000000")
        };
        for (byte[] invalid : malformed) {
            try (ServerSocket listener = new ServerSocket(0); var workers = Executors.newVirtualThreadPerTaskExecutor();
                 TcpClient client = client(listener.getLocalPort())) {
                listener.setSoTimeout(3000);
                CountDownLatch restored = new CountDownLatch(1);
                List<Throwable> errors = new CopyOnWriteArrayList<>();
                AtomicLong events = new AtomicLong();
                client.setEventListener(event -> events.incrementAndGet());
                client.setMonitoringListener(monitoring -> {
                    assertEquals(0xFFFFFFFFL, monitoring.totalDecodedFrames());
                    restored.countDown();
                });
                client.setErrorListener(errors::add);
                var server = workers.submit(() -> {
                    try (Socket first = listener.accept()) {
                        first.setSoTimeout(3000);
                        first.getOutputStream().write(ByteBuffer.allocate(EVENT.length + invalid.length).put(EVENT).put(invalid).array());
                        assertEquals(-1, first.getInputStream().read(), "malformed-session socket must close");
                    }
                    try (Socket second = listener.accept()) {
                        second.setSoTimeout(3000);
                        second.getOutputStream().write(MONITORING);
                        assertEquals(-1, second.getInputStream().read());
                    }
                    return null;
                });
                client.connect();
                assertTrue(restored.await(3, TimeUnit.SECONDS), errors.toString());
                assertEquals(1, events.get(), "complete preceding messages are delivered once");
                assertTrue(errors.stream().anyMatch(ProtocolException.class::isInstance));
                assertTrue(client.getDataGaps() >= 1);
                assertTrue(client.getReconnects() >= 1);
                client.disconnect();
                server.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void truncatedEofDiscardsOldSessionBeforeReconnection() throws Exception {
        for (int partialLength : new int[] {3, 12}) {
            try (ServerSocket listener = new ServerSocket(0); var workers = Executors.newVirtualThreadPerTaskExecutor();
                 TcpClient client = client(listener.getLocalPort())) {
                listener.setSoTimeout(3000);
                CountDownLatch restored = new CountDownLatch(1);
                AtomicLong events = new AtomicLong();
                client.setEventListener(event -> events.incrementAndGet());
                client.setMonitoringListener(monitoring -> restored.countDown());
                var server = workers.submit(() -> {
                    try (Socket first = listener.accept()) {
                        first.getOutputStream().write(EVENT, 0, partialLength);
                    }
                    try (Socket second = listener.accept()) {
                        second.setSoTimeout(3000);
                        second.getOutputStream().write(MONITORING);
                        assertEquals(-1, second.getInputStream().read());
                    }
                    return null;
                });
                client.connect();
                assertTrue(restored.await(3, TimeUnit.SECONDS));
                assertEquals(0, events.get());
                assertEquals(1, client.getDataGaps());
                assertTrue(client.getErrors() >= 1);
                client.disconnect();
                server.get(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void idleReadTimeoutsPreserveConnectionAndPartialFraming() throws Exception {
        try (ServerSocket listener = new ServerSocket(0); var workers = Executors.newVirtualThreadPerTaskExecutor();
             TcpClient client = client(listener.getLocalPort())) {
            listener.setSoTimeout(3000);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch firstPart = new CountDownLatch(1);
            CountDownLatch resume = new CountDownLatch(1);
            CountDownLatch received = new CountDownLatch(1);
            client.setMonitoringListener(monitoring -> received.countDown());
            var server = workers.submit(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(3000);
                    assertTrue(start.await(3, TimeUnit.SECONDS));
                    socket.getOutputStream().write(MONITORING, 0, 12);
                    firstPart.countDown();
                    assertTrue(resume.await(3, TimeUnit.SECONDS));
                    socket.getOutputStream().write(MONITORING, 12, MONITORING.length - 12);
                    assertEquals(-1, socket.getInputStream().read());
                }
                return null;
            });
            client.connect();
            Field socketField = TcpClient.class.getDeclaredField("socket");
            socketField.setAccessible(true);
            ((Socket) socketField.get(client)).setSoTimeout(20);
            start.countDown();
            assertTrue(firstPart.await(3, TimeUnit.SECONDS));
            assertFalse(received.await(150, TimeUnit.MILLISECONDS));
            assertTrue(client.isConnected());
            assertEquals(0, client.getErrors());
            assertEquals(0, client.getDataGaps());
            resume.countDown();
            assertTrue(received.await(3, TimeUnit.SECONDS));
            client.disconnect();
            server.get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void configuredLimitIsEnforcedAndReconnectBackoffCannotOverflow() throws Exception {
        try (TcpClient client = client(12345)) {
            assertEquals("127.0.0.1", client.getHost());
            assertEquals(40, client.getSettings().maxMessageBytes());
            Field attemptField = TcpClient.class.getDeclaredField("reconnectAttempt");
            attemptField.setAccessible(true);
            AtomicLong attempt = (AtomicLong) attemptField.get(client);
            Method interval = TcpClient.class.getDeclaredMethod("calculateReconnectInterval");
            interval.setAccessible(true);
            long[] expected = {10, 10, 20, 40, 40};
            for (int i = 0; i < expected.length; i++) {
                attempt.set(i);
                assertEquals(expected[i], interval.invoke(client));
            }
            attempt.set(Long.MAX_VALUE);
            assertEquals(40L, interval.invoke(client));
        }
    }
}
