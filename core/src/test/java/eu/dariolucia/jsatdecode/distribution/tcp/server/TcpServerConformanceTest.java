package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.EventMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.MonitoringMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class TcpServerConformanceTest {
    @Test
    void twoClientsReceiveCompleteMessagesInTheSameOrder() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 2, 4096, OverflowPolicy.FAIL_RUNTIME))) {
            server.start();
            try (Socket first = connect(server); Socket second = connect(server)) {
                await(() -> server.getActiveConnections() == 2);
                TcpMessageEncoder encoder = new TcpMessageEncoder(1, 4096);
                byte[] event = encoder.encodeEvent(new EventMessage(TcpConstants.EVENT_DECODER_STARTED,
                        1_700_000_000_000_000_000L, (byte) 0x3F, 145_000_000));
                byte[] monitoring = monitoring(encoder);
                server.broadcast(event);
                server.broadcast(monitoring);
                byte[] expected = concatenate(event, monitoring);
                assertArrayEquals(expected, first.getInputStream().readNBytes(expected.length));
                assertArrayEquals(expected, second.getInputStream().readNBytes(expected.length));
                await(() -> server.getMessagesSent() == 4);
                assertEquals(2L * expected.length, server.getBytesSent());
            }
        }
    }

    @Test
    void everyProducerPolicyDisconnectsOnNetworkByteOverflowWithoutBlocking() throws Exception {
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            try (TcpServer server = new TcpServer(settings(freePort(), 1, 10, policy))) {
                ControlledSocket socket = new ControlledSocket(true, true);
                ClientHandler handler = handler(server, 1, socket, 10, policy);
                try {
                    handler.enqueueMessage(new byte[6]);
                    Thread writer = Thread.ofVirtual().start(handler::run);
                    assertTrue(socket.writeStarted.await(2, TimeUnit.SECONDS));
                    assertEquals(6, handler.getQueueBytes(), "An in-flight write must remain reserved");
                    assertTimeoutPreemptively(Duration.ofMillis(500), () -> handler.enqueueMessage(new byte[5]));
                    assertTrue(handler.isClosed(), policy.toString());
                    assertEquals(0, handler.getQueueBytes());
                    assertEquals(0, server.getActiveConnections());
                    assertTrue(socket.closeStarted.await(2, TimeUnit.SECONDS));
                    // Even a socket whose close blocks must not hold the decoder.
                    socket.allowClose.countDown();
                    writer.join(2000);
                    assertFalse(writer.isAlive());
                    assertEquals(0, socket.outputCloseCalls.get());
                } finally {
                    socket.allowClose.countDown();
                    handler.close();
                }
            }
        }
    }

    @Test
    void concurrentEnqueuesNeverExceedTheReservationLimit() throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            try (TcpServer server = new TcpServer(settings(freePort(), 1, 1, OverflowPolicy.DROP_OLDEST))) {
                ControlledSocket socket = new ControlledSocket(true, false);
                ClientHandler handler = handler(server, 1, socket, 1, OverflowPolicy.DROP_OLDEST);
                CountDownLatch start = new CountDownLatch(1);
                AtomicLong maximumReserved = new AtomicLong();
                List<Thread> producers = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                    producers.add(Thread.ofVirtual().start(() -> {
                        try {
                            start.await();
                            handler.enqueueMessage(new byte[1]);
                            maximumReserved.accumulateAndGet(handler.getQueueBytes(), Math::max);
                        } catch (InterruptedException e) {
                            throw new AssertionError(e);
                        }
                    }));
                }
                start.countDown();
                for (Thread producer : producers) {
                    producer.join(2000);
                    assertFalse(producer.isAlive());
                }
                assertTrue(handler.isClosed(), "The second accepted byte must disconnect");
                assertTrue(maximumReserved.get() <= 1, "Concurrent reservations exceeded the byte limit");
                assertEquals(0, handler.getQueueBytes());
                assertEquals(0, server.getActiveConnections());
            }
        }
    }

    @Test
    void aStalledClientCannotBlockAnotherClientOrGenerateExtraEvents() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 2, 10, OverflowPolicy.DROP_NEWEST))) {
            ControlledSocket stalledSocket = new ControlledSocket(true, true);
            ControlledSocket healthySocket = new ControlledSocket(false, false);
            ClientHandler stalled = handler(server, 1, stalledSocket, 10, OverflowPolicy.DROP_NEWEST);
            ClientHandler healthy = handler(server, 2, healthySocket, 10, OverflowPolicy.DROP_NEWEST);
            Thread stalledWriter = Thread.ofVirtual().start(stalled::run);
            Thread healthyWriter = Thread.ofVirtual().start(healthy::run);
            AtomicInteger emittedMessages = new AtomicInteger();
            server.setMessageListener((type, bytes) -> emittedMessages.incrementAndGet());
            try {
                byte[] first = {1, 2, 3, 4, 5, 6};
                byte[] second = {7, 8, 9, 10, 11};
                server.broadcast(first);
                assertTrue(stalledSocket.writeStarted.await(2, TimeUnit.SECONDS));
                await(() -> healthy.getQueueBytes() == 0);
                assertTimeoutPreemptively(Duration.ofMillis(500), () -> server.broadcast(second));
                await(() -> healthy.getMessagesSent() == 2);
                assertTrue(stalled.isClosed());
                assertFalse(healthy.isClosed());
                assertArrayEquals(concatenate(first, second), healthySocket.writtenBytes());
                assertEquals(2, emittedMessages.get(), "A network overflow cannot emit a monitoring event");
            } finally {
                stalledSocket.allowClose.countDown();
                stalled.close();
                healthy.close();
                stalledWriter.join(2000);
                healthyWriter.join(2000);
            }
        }
    }

    @Test
    void idleEofReleasesTheClientSlotForAReplacement() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 1, 4096, OverflowPolicy.FAIL_RUNTIME))) {
            server.start();
            Socket first = connect(server);
            await(() -> server.getActiveConnections() == 1);
            first.close();
            await(() -> server.getActiveConnections() == 0);
            try (Socket replacement = connect(server)) {
                await(() -> server.getActiveConnections() == 1);
                assertEquals(2, server.getTotalConnections());
                server.broadcast(new byte[]{1, 2, 3});
                assertArrayEquals(new byte[]{1, 2, 3}, replacement.getInputStream().readNBytes(3));
            }
        }
    }

    @Test
    void malformedInputClosesTheConnectionAndReleasesItsSlot() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 1, 4096, OverflowPolicy.FAIL_RUNTIME))) {
            server.start();
            try (Socket peer = connect(server)) {
                await(() -> server.getActiveConnections() == 1);
                peer.getOutputStream().write(new byte[]{0, 1, 0, 1, 0, 0, 0, 40});
                peer.getOutputStream().flush();
                assertEquals(-1, peer.getInputStream().read());
                await(() -> server.getActiveConnections() == 0);
            }
        }
    }

    @Test
    void initialMonitoringPrecedesAConcurrentBroadcastAndCanBeDisabled() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 2, 4096, OverflowPolicy.FAIL_RUNTIME))) {
            TcpMessageEncoder encoder = new TcpMessageEncoder(1, 4096);
            byte[] initial = monitoring(encoder);
            byte[] event = encoder.encodeEvent(new EventMessage(TcpConstants.EVENT_DECODER_STARTED,
                    1_700_000_000_000_000_000L, (byte) 0x3F, 145_000_000));
            CountDownLatch supplying = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            server.setMonitoringSupplier(() -> {
                supplying.countDown();
                try {
                    if (!release.await(2, TimeUnit.SECONDS)) {
                        throw new AssertionError("Initial snapshot was not released");
                    }
                } catch (InterruptedException e) {
                    throw new AssertionError(e);
                }
                return initial;
            });
            server.start();
            try (Socket first = connect(server)) {
                assertTrue(supplying.await(2, TimeUnit.SECONDS));
                CountDownLatch broadcasting = new CountDownLatch(1);
                Thread producer = Thread.ofVirtual().start(() -> {
                    broadcasting.countDown();
                    server.broadcast(event);
                });
                assertTrue(broadcasting.await(2, TimeUnit.SECONDS));
                release.countDown();
                producer.join(2000);
                assertFalse(producer.isAlive());
                assertArrayEquals(concatenate(initial, event),
                        first.getInputStream().readNBytes(initial.length + event.length));
                server.setMonitoringSupplier(null);
                try (Socket later = connect(server)) {
                    await(() -> server.getActiveConnections() == 2);
                    server.broadcast(event);
                    assertArrayEquals(event, later.getInputStream().readNBytes(event.length));
                }
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void aFailedInitialMonitoringSupplierDoesNotLeakASlotOrStopAcceptance() throws Exception {
        try (TcpServer server = new TcpServer(settings(freePort(), 1, 4096, OverflowPolicy.FAIL_RUNTIME))) {
            byte[] initial = monitoring(new TcpMessageEncoder(1, 4096));
            AtomicInteger attempts = new AtomicInteger();
            server.setMonitoringSupplier(() -> {
                if (attempts.getAndIncrement() == 0) {
                    throw new IllegalStateException("Initial snapshot unavailable");
                }
                return initial;
            });
            server.start();
            try (Socket failedPeer = connect(server)) {
                assertEquals(-1, failedPeer.getInputStream().read());
                await(() -> server.getErrors() > 0);
                assertEquals(0, server.getActiveConnections());
                assertTrue(server.isRunning());
            }
            try (Socket replacement = connect(server)) {
                assertArrayEquals(initial, replacement.getInputStream().readNBytes(initial.length));
                assertEquals(1, server.getActiveConnections());
            }
        }
    }

    @Test
    void shutdownAttemptsEveryQueuedWriteBeforeClosing() throws Exception {
        TcpServer server = new TcpServer(settings(freePort(), 1, 4096, OverflowPolicy.FAIL_RUNTIME));
        server.start();
        try (Socket peer = connect(server)) {
            await(() -> server.getActiveConnections() == 1);
            byte[] lastMessage = new TcpMessageEncoder(1, 4096).encodeEvent(new EventMessage(
                    TcpConstants.EVENT_DECODER_STOPPED, 1_700_000_000_000_000_000L, (byte) 0x3F, 0));
            server.broadcast(lastMessage);
            server.stop();
            assertArrayEquals(lastMessage, peer.getInputStream().readNBytes(lastMessage.length));
            assertEquals(-1, peer.getInputStream().read());
            assertEquals(0, server.getActiveConnections());
        } finally {
            server.close();
        }
    }

    @Test
    void stalledShutdownUsesOneBoundedDeadlineForAllClients() throws Exception {
        TcpServer server = new TcpServer(settings(freePort(), 2, 4096, OverflowPolicy.FAIL_RUNTIME));
        ControlledSocket firstSocket = new ControlledSocket(true, false);
        ControlledSocket secondSocket = new ControlledSocket(true, false);
        ClientHandler first = handler(server, 1, firstSocket, 4096, OverflowPolicy.FAIL_RUNTIME);
        ClientHandler second = handler(server, 2, secondSocket, 4096, OverflowPolicy.FAIL_RUNTIME);
        Thread firstWriter = Thread.ofVirtual().start(first::run);
        Thread secondWriter = Thread.ofVirtual().start(second::run);
        try {
            server.broadcast(new byte[64]);
            assertTrue(firstSocket.writeStarted.await(2, TimeUnit.SECONDS));
            assertTrue(secondSocket.writeStarted.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(1800), server::stop);
            assertTrue(first.isClosed());
            assertTrue(second.isClosed());
            assertEquals(0, server.getActiveConnections());
        } finally {
            server.close();
            firstWriter.join(2000);
            secondWriter.join(2000);
        }
    }

    @Test
    void registryStartsOnceAndOnlyStopsAfterTheLastReference() throws Exception {
        int port = freePort();
        try {
            TcpSettings settings = settings(port, 2, 4096, OverflowPolicy.FAIL_RUNTIME);
            TcpServer first = TcpServerRegistry.getOrCreateServer(settings);
            TcpServer second = TcpServerRegistry.getOrCreateServer(new TcpSettings(
                    port, false, 2, 0, 0, true, 2000, 3000, 8,
                    4096, 4096, 1, OverflowPolicy.DROP_NEWEST));
            assertSame(first, second);
            TcpServerRegistry.startServer(first);
            TcpServerRegistry.startServer(second);
            assertTrue(first.isRunning());
            TcpServerRegistry.releaseServer(port);
            assertSame(first, TcpServerRegistry.getServer(port));
            assertTrue(first.isRunning());
            TcpServerRegistry.releaseServer(port);
            assertFalse(TcpServerRegistry.hasServer(port));
            assertEquals(ComponentState.CLOSED, first.state());
            try (ServerSocket rebound = new ServerSocket(port)) {
                assertTrue(rebound.isBound());
            }
        } finally {
            TcpServerRegistry.clearAll();
        }
    }

    @Test
    void registryRejectsEverySharedTransportMismatchWithoutAddingAReference() throws Exception {
        int port = freePort();
        try {
            TcpServer original = TcpServerRegistry.getOrCreateServer(settings(port, 2, 4096, OverflowPolicy.FAIL_RUNTIME));
            List<TcpSettings> conflicts = List.of(
                    new TcpSettings(port, true, 2, 0, 0, true, 1000, 1000, 5, 4096, 4096, 1, OverflowPolicy.FAIL_RUNTIME),
                    settings(port, 3, 4096, OverflowPolicy.FAIL_RUNTIME),
                    new TcpSettings(port, false, 2, 1, 0, true, 1000, 1000, 5, 4096, 4096, 1, OverflowPolicy.FAIL_RUNTIME),
                    new TcpSettings(port, false, 2, 0, 1, true, 1000, 1000, 5, 4096, 4096, 1, OverflowPolicy.FAIL_RUNTIME),
                    new TcpSettings(port, false, 2, 0, 0, false, 1000, 1000, 5, 4096, 4096, 1, OverflowPolicy.FAIL_RUNTIME),
                    new TcpSettings(port, false, 2, 0, 0, true, 1000, 1000, 5, 4097, 4096, 1, OverflowPolicy.FAIL_RUNTIME),
                    settings(port, 2, 4097, OverflowPolicy.FAIL_RUNTIME),
                    new TcpSettings(port, false, 2, 0, 0, true, 1000, 1000, 5, 4096, 4096, 2, OverflowPolicy.FAIL_RUNTIME));
            for (TcpSettings conflict : conflicts) {
                assertThrows(IllegalArgumentException.class, () -> TcpServerRegistry.getOrCreateServer(conflict));
            }
            TcpServerRegistry.releaseServer(port);
            assertEquals(ComponentState.CLOSED, original.state());
            assertFalse(TcpServerRegistry.hasServer(port));
        } finally {
            TcpServerRegistry.clearAll();
        }
    }

    private static TcpSettings settings(int port, int maxClients, int queueBytes, OverflowPolicy policy) {
        return new TcpSettings(port, false, maxClients, 0, 0, true,
                1000, 1000, 5, 4096, queueBytes, 1, policy);
    }

    private static ClientHandler handler(TcpServer server, long id, ControlledSocket socket,
                                         int queueBytes, OverflowPolicy policy) throws IOException {
        ClientHandler result = new ClientHandler(server, id, socket, queueBytes, 4096, policy, 1);
        server.registerClient(result);
        return result;
    }

    private static int freePort() throws IOException {
        try (ServerSocket reservation = new ServerSocket(0)) {
            return reservation.getLocalPort();
        }
    }

    private static Socket connect(TcpServer server) throws IOException {
        Socket socket = new Socket("127.0.0.1", server.getPort());
        socket.setSoTimeout(2000);
        return socket;
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "Condition did not become true before its deadline");
    }

    private static byte[] monitoring(TcpMessageEncoder encoder) {
        return encoder.encodeMonitoring(new MonitoringMessage((byte) 0x3F, 145_000_000,
                Integer.MIN_VALUE, Float.NaN, 0, 0, 0, 0));
    }

    private static byte[] concatenate(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    /** Deterministic stalled I/O without depending on OS socket buffer sizes. */
    private static final class ControlledSocket extends Socket {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch allowClose;
        final CountDownLatch allowWrite;
        final CountDownLatch closed = new CountDownLatch(1);
        final AtomicInteger outputCloseCalls = new AtomicInteger();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ControlledSocket(boolean stallWrites, boolean stallClose) {
            allowWrite = new CountDownLatch(stallWrites ? 1 : 0);
            allowClose = new CountDownLatch(stallClose ? 1 : 0);
        }

        @Override
        public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override
                public void write(int value) throws IOException { write(new byte[]{(byte) value}); }

                @Override
                public void write(byte[] data, int offset, int length) throws IOException {
                    writeStarted.countDown();
                    waitFor(allowWrite);
                    if (closed.getCount() == 0) {
                        throw new IOException("Socket closed");
                    }
                    synchronized (bytes) { bytes.write(data, offset, length); }
                }

                @Override
                public void close() {
                    outputCloseCalls.incrementAndGet();
                    fail("The handler must close the socket directly");
                }
            };
        }

        @Override
        public InputStream getInputStream() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    waitFor(closed);
                    return -1;
                }
            };
        }

        @Override
        public void close() throws IOException {
            closeStarted.countDown();
            waitFor(allowClose);
            closed.countDown();
            allowWrite.countDown();
        }

        @Override
        public InetSocketAddress getRemoteSocketAddress() {
            return new InetSocketAddress("127.0.0.1", 12345);
        }

        byte[] writtenBytes() {
            synchronized (bytes) { return bytes.toByteArray(); }
        }

        private static void waitFor(CountDownLatch latch) throws IOException {
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", e);
            }
        }
    }
}
