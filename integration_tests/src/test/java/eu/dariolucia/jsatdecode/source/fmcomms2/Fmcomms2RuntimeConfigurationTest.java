package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.SourceException;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Real local IIOD transactions, including a streaming socket blocked in READBUF. */
class Fmcomms2RuntimeConfigurationTest {
    @Test
    void runtimeUpdatesUseIndependentControlAndPreserveTheSampleClock() throws Exception {
        try (Peer peer = new Peer(); Fmcomms2SampleSource source = source(peer)) {
            BlockingQueue<ComplexSampleBlock> blocks = new LinkedBlockingQueue<>();
            source.setSampleSink(blocks::add);
            assertThrows(IllegalStateException.class, () -> source.updateConfiguration(new Configuration(Map.of("frequency", 145000000L))));
            source.start();
            assertTrue(peer.readPending.await(5, TimeUnit.SECONDS));
            assertEquals(1, peer.connections.get());
            assertThrows(ConfigurationException.class, () -> source.updateConfiguration(new Configuration(Map.of("sample_rate", 4000000))));
            assertThrows(ConfigurationException.class, () -> source.updateConfiguration(new Configuration(Map.of("manual_gain1", 74))));
            assertThrows(ConfigurationException.class, () -> source.updateConfiguration(new Configuration(Map.of("frequency", 1L, "bandwidth", 1000000))));
            assertEquals(1, peer.connections.get(), "Invalid requests must not open a control connection");
            assertEquals(ComponentState.RUNNING, source.state());
            peer.permits.release();
            ComplexSampleBlock first = blocks.poll(5, TimeUnit.SECONDS);
            assertNotNull(first);
            source.updateConfiguration(new Configuration(Map.of("frequency", 145000000L, "bandwidth", 1000000,
                    "gain1", "manual", "manual_gain1", 42.5)));
            assertEquals(2, peer.connections.get());
            assertEquals("145000000", peer.value("iio:device0 OUTPUT altvoltage0 frequency"));
            assertEquals("1000000", peer.value("iio:device0 INPUT voltage0 rf_bandwidth"));
            assertEquals("manual", peer.value("iio:device0 INPUT voltage0 gain_control_mode"));
            assertEquals("42.5", peer.value("iio:device0 INPUT voltage0 hardwaregain"));
            assertEquals(145000000.0, source.monitoringSnapshot().values().get("source.center_frequency"));
            assertEquals(1, peer.requests.stream().filter(s -> s.contains(" OPEN ")).count());
            assertFalse(peer.requests.stream().anyMatch(s -> s.startsWith("1 READBUF") || s.startsWith("1 OPEN")));
            source.updateConfiguration(new Configuration(Map.of("manual_gain2", 50, "gain2", "manual")));
            assertNull(peer.attributes.get("iio:device0 INPUT voltage1 hardwaregain"), "Inactive receiver must not be written");
            peer.permits.release();
            ComplexSampleBlock second = blocks.poll(5, TimeUnit.SECONDS);
            assertNotNull(second);
            assertEquals(first.sampleIndex() + first.complexSamples(), second.sampleIndex());
            assertEquals((long) (second.sampleIndex() / 3000000.0 * 1e9), second.timestampNanos() - first.timestampNanos());
            assertEquals(first.sampleRate(), second.sampleRate());
            assertEquals(first.i(0), second.i(0));
            assertEquals(first.q(0), second.q(0));
            source.stop();
            assertTrue(source.failure().isEmpty());
            assertThrows(IllegalStateException.class, () -> source.updateConfiguration(new Configuration(Map.of("frequency", 146000000L))));
        }
    }

    @Test
    void hardwareFailureFailsAndCancelsAcquisitionInsteadOfAcknowledgingPartialChanges() throws Exception {
        try (Peer peer = new Peer(); Fmcomms2SampleSource source = source(peer)) {
            source.setSampleSink(block -> { });
            source.start();
            assertTrue(peer.readPending.await(5, TimeUnit.SECONDS));
            peer.rejectAttribute = "rf_bandwidth";
            assertThrows(SourceException.class, () -> source.updateConfiguration(new Configuration(Map.of("frequency", 145000000L, "bandwidth", 1000000))));
            assertTrue(source.awaitCompletion(Duration.ofSeconds(5)));
            assertEquals(ComponentState.FAILED, source.state());
            assertTrue(source.failure().isPresent());
        }
    }

    @Test
    void cancellationAlsoClosesABlockedRuntimeControlConnection() throws Exception {
        try (Peer peer = new Peer(); Fmcomms2SampleSource source = source(peer);
             ExecutorService owner = Executors.newSingleThreadExecutor()) {
            source.setSampleSink(block -> { });
            source.start();
            peer.pauseControl = true;
            Future<?> update = owner.submit(() -> source.updateConfiguration(new Configuration(Map.of("frequency", 145000000L))));
            assertTrue(peer.controlPending.await(5, TimeUnit.SECONDS));
            source.requestStop();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> update.get(5, TimeUnit.SECONDS));
            assertInstanceOf(SourceException.class, failure.getCause());
            source.stop();
            assertTrue(source.failure().isEmpty(), "Intentional cancellation must not fail acquisition");
        }
    }

    private static Fmcomms2SampleSource source(Peer peer) {
        return new Fmcomms2SourceFactory().create(new Configuration(Map.of("uri", "ip:127.0.0.1:" + peer.port(),
                "frequency", 137100000L, "buffer_size", 8, "block_samples", 8)));
    }

    private static final class Peer implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        final ExecutorService clients = Executors.newCachedThreadPool();
        final List<Socket> sockets = new CopyOnWriteArrayList<>();
        final List<String> requests = new CopyOnWriteArrayList<>();
        final Map<String, byte[]> attributes = new ConcurrentHashMap<>();
        final AtomicInteger connections = new AtomicInteger();
        final CountDownLatch readPending = new CountDownLatch(1), controlPending = new CountDownLatch(1);
        final Semaphore permits = new Semaphore(0);
        final Thread acceptor;
        volatile String rejectAttribute;
        volatile boolean pauseControl, closing;
        volatile Throwable failure;

        Peer() throws IOException {
            acceptor = new Thread(() -> {
                try {
                    while (!closing) {
                        Socket socket = server.accept();
                        sockets.add(socket);
                        int id = connections.getAndIncrement();
                        clients.submit(() -> serve(socket, id));
                    }
                } catch (SocketException e) { if (!closing) failure = e; }
                catch (Throwable e) { failure = e; }
            }, "runtime-iiod-test");
            acceptor.start();
        }

        int port() { return server.getLocalPort(); }
        String value(String name) { return new String(attributes.get(name), StandardCharsets.US_ASCII).replace("\0", ""); }

        void serve(Socket socket, int id) {
            try (socket) {
                socket.setTcpNoDelay(true);
                InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream();
                String mask = "";
                for (String command; (command = line(input)) != null;) {
                    requests.add(id + " " + command);
                    if (id > 0 && pauseControl) {
                        controlPending.countDown();
                        input.read();
                        return;
                    }
                    if (command.startsWith("TIMEOUT ") || command.startsWith("SET ")) send(output, "0\n");
                    else if (command.equals("PRINT")) {
                        byte[] xml = Fmcomms2SourceTest.xml("le:S12/16>>0", 0).getBytes(StandardCharsets.UTF_8);
                        send(output, xml.length + "\n"); output.write(xml); send(output, "\n");
                    } else if (command.startsWith("WRITE ")) {
                        int split = command.lastIndexOf(' ');
                        int count = Integer.parseInt(command.substring(split + 1));
                        byte[] bytes = input.readNBytes(count);
                        if (bytes.length != count) throw new EOFException();
                        String attribute = command.substring(6, split);
                        if (rejectAttribute != null && attribute.endsWith(rejectAttribute)) send(output, "-22\n");
                        else { attributes.put(attribute, bytes); send(output, count + "\n"); }
                    } else if (command.startsWith("READ ")) {
                        byte[] bytes = attributes.get(command.substring(5));
                        send(output, bytes.length + "\n"); output.write(bytes); send(output, "\n");
                    } else if (command.startsWith("OPEN ")) { mask = command.split(" ")[3]; send(output, "0\n"); }
                    else if (command.startsWith("READBUF ")) {
                        readPending.countDown();
                        if (!permits.tryAcquire(5, TimeUnit.SECONDS)) throw new IOException("Test did not release a pending read");
                        int count = Integer.parseInt(command.substring(command.lastIndexOf(' ') + 1));
                        ByteBuffer data = ByteBuffer.allocate(count).order(ByteOrder.LITTLE_ENDIAN);
                        while (data.hasRemaining()) data.putShort((short) 256).putShort((short) -128);
                        send(output, count + "\n" + mask + "\n"); output.write(data.array()); output.flush();
                    } else throw new IOException("Unexpected IIOD command: " + command);
                }
            } catch (SocketException e) { /* Source cancellation closes pending reads. */ }
            catch (Throwable e) { if (!closing) failure = e; }
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) >= 0;) {
                if (value == '\n') return bytes.toString(StandardCharsets.US_ASCII).strip();
                bytes.write(value);
            }
            return null;
        }

        private static void send(OutputStream output, String text) throws IOException {
            output.write(text.getBytes(StandardCharsets.US_ASCII)); output.flush();
        }

        @Override public void close() throws Exception {
            closing = true;
            server.close();
            for (Socket socket : sockets) socket.close();
            permits.release(100);
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(5, TimeUnit.SECONDS));
            acceptor.join(5000);
            assertFalse(acceptor.isAlive());
            if (failure != null) throw new AssertionError("IIOD peer failed", failure);
        }
    }
}
