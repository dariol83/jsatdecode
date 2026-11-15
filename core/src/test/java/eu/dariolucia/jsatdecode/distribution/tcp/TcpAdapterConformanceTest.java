package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

class TcpAdapterConformanceTest {
    private static TcpSettings settings(int count, long latency, long interval, int maxBytes,
                                        int capacity, OverflowPolicy policy) {
        return new TcpSettings(6001, false, 2, 0, 0, true, interval, latency, count,
                maxBytes, 1_048_576, 42, policy, capacity, "localhost", 1000, 30000);
    }

    private static AnnotatedFrame frame(int value) {
        return new AnnotatedFrame(new byte[]{(byte) value}, value, Map.of());
    }

    private static class RecordingServer extends TcpServer {
        final BlockingQueue<byte[]> messages = new LinkedBlockingQueue<>();
        volatile Supplier<byte[]> supplier;
        volatile Thread broadcastThread;
        RecordingServer(TcpSettings settings) { super(settings); }
        @Override public void broadcast(byte[] message) {
            broadcastThread = Thread.currentThread();
            messages.add(message);
        }
        @Override public void setMonitoringSupplier(Supplier<byte[]> supplier) {
            super.setMonitoringSupplier(supplier);
            this.supplier = supplier;
        }
        byte[] next() throws InterruptedException {
            byte[] message = messages.poll(2, TimeUnit.SECONDS);
            assertNotNull(message, "Expected a submitted JTDP message");
            return message;
        }
    }

    /** Holds the first submission to deterministically fill producer queues. */
    private static final class BlockedServer extends RecordingServer {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private boolean first = true;
        BlockedServer(TcpSettings settings) { super(settings); }
        @Override public void broadcast(byte[] message) {
            if (first) {
                first = false;
                entered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS), "Test did not release broadcaster");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(e);
                }
            }
            super.broadcast(message);
        }
        void awaitBlocked() throws InterruptedException { assertTrue(entered.await(2, TimeUnit.SECONDS)); }
    }

    private record WireFrame(int quality, long timestamp, byte[] data) { }
    private static List<WireFrame> frames(byte[] message) {
        ByteBuffer wire = ByteBuffer.wrap(message);
        assertEquals(0xA5, Byte.toUnsignedInt(wire.get()));
        assertEquals(0, wire.get());
        assertEquals(42, Short.toUnsignedInt(wire.getShort()));
        assertEquals(message.length, wire.getInt());
        List<WireFrame> records = new ArrayList<>();
        while (wire.hasRemaining()) {
            int quality = Byte.toUnsignedInt(wire.get());
            long timestamp = wire.getLong();
            byte[] data = new byte[wire.getInt()];
            wire.get(data);
            records.add(new WireFrame(quality, timestamp, data));
        }
        return records;
    }

    @Test void countFlushPreservesOrderReceptionTimesRawPayloadAndQuality() throws Exception {
        TcpSettings settings = settings(3, 5000, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server)) {
            distributor.start();
            byte[] raw = {(byte) 0xA5, 0, (byte) 255};
            distributor.distribute(new AnnotatedFrame(raw, 999_999_999_999L, Map.of("quality", "GOOD")));
            distributor.distribute(new AnnotatedFrame(raw, 7, Map.of("quality", "BAD")));
            distributor.distribute(new AnnotatedFrame(raw, Long.MAX_VALUE, Map.of("quality", "UNKNOWN")));
            List<WireFrame> result = frames(server.next());
            assertEquals(List.of(0, 1, 2), result.stream().map(WireFrame::quality).toList());
            assertEquals(List.of(999_999_999_999L, 7L, Long.MAX_VALUE), result.stream().map(WireFrame::timestamp).toList());
            result.forEach(record -> assertArrayEquals(raw, record.data()));
            assertEquals(3, distributor.getFramesAccepted());
            assertEquals(0, distributor.getDroppedFrames());
            assertNotEquals(Thread.currentThread(), server.broadcastThread);
        }
    }

    @Test void sizeFlushStartsNextBatchAndRejectsOnlyIndividuallyInvalidFrames() throws Exception {
        TcpSettings settings = settings(99, 35, 5000, 40, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server)) {
            distributor.start();
            distributor.distribute(new AnnotatedFrame(new byte[10], 1, Map.of()));
            distributor.distribute(new AnnotatedFrame(new byte[10], 2, Map.of()));
            assertEquals(1, frames(server.next()).getFirst().timestamp());
            assertEquals(2, frames(server.next()).getFirst().timestamp());
            assertThrows(IllegalArgumentException.class,
                    () -> distributor.distribute(new AnnotatedFrame(new byte[20], 3, Map.of())));
            assertThrows(IllegalArgumentException.class,
                    () -> distributor.distribute(new AnnotatedFrame(new byte[0], 4, Map.of())));
            assertEquals(2, distributor.getFramesAccepted());
            assertEquals(2, distributor.getDroppedFrames());
            assertEquals(ComponentState.RUNNING, distributor.state());
        }
    }

    @Test void quietBatchFlushesOnRealProcessingTimeDeadline() throws Exception {
        TcpSettings settings = settings(99, 30, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server)) {
            distributor.start();
            long before = System.nanoTime();
            distributor.distribute(new AnnotatedFrame(new byte[]{1}, Long.MAX_VALUE, Map.of()));
            byte[] message = server.messages.poll(700, TimeUnit.MILLISECONDS);
            assertNotNull(message, "A quiet partial batch must flush without another producer call");
            assertTrue(System.nanoTime() - before >= TimeUnit.MILLISECONDS.toNanos(20));
            assertEquals(Long.MAX_VALUE, frames(message).getFirst().timestamp());
        }
    }

    @Test void latencyIncludesTimeAlreadySpentInTheProducerQueue() throws Exception {
        TcpSettings settings = settings(2, 180, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        BlockedServer server = new BlockedServer(settings);
        TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server);
        try {
            distributor.start();
            distributor.distribute(frame(1));
            distributor.distribute(frame(2));
            server.awaitBlocked();
            distributor.distribute(frame(3));
            Thread.sleep(230);
            server.release.countDown();
            assertEquals(2, frames(server.next()).size());
            byte[] overdue = server.messages.poll(100, TimeUnit.MILLISECONDS);
            assertNotNull(overdue, "Queue waiting must not restart a batch's submission deadline");
            assertEquals(3, frames(overdue).getFirst().timestamp());
        } finally {
            server.release.countDown();
            distributor.close();
        }
    }

    @Test void normalShutdownAttemptsTheFinalPartialBatch() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server);
        distributor.start();
        distributor.distribute(frame(11));
        distributor.close();
        assertEquals(11, frames(server.next()).getFirst().timestamp());
        assertEquals(0, distributor.monitoringSnapshot().queueDepth());
    }

    @Test void frameProducerQueuesEnforceAllOverflowPoliciesAndKeepNetworkWorkOffCaller() throws Exception {
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            TcpSettings settings = settings(1, 5000, 5000, 1024, 2, policy);
            BlockedServer server = new BlockedServer(settings);
            TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server);
            try {
                distributor.start();
                distributor.distribute(frame(1));
                server.awaitBlocked();
                distributor.distribute(frame(2));
                distributor.distribute(frame(3));
                if (policy == OverflowPolicy.FAIL_RUNTIME) {
                    assertThrows(IllegalStateException.class, () -> distributor.distribute(frame(4)));
                    assertEquals(ComponentState.FAILED, distributor.state());
                } else distributor.distribute(frame(4));
                assertEquals(2, distributor.monitoringSnapshot().queueDepth());
                assertEquals(2, distributor.monitoringSnapshot().queueCapacity());
                assertEquals(1, distributor.getDroppedFrames());
                assertEquals(policy == OverflowPolicy.DROP_OLDEST ? 4 : 3, distributor.getFramesAccepted());
                server.release.countDown();
                distributor.stop();
                List<Long> delivered = new ArrayList<>();
                for (byte[] message : server.messages) frames(message).forEach(record -> delivered.add(record.timestamp()));
                assertEquals(policy == OverflowPolicy.DROP_OLDEST ? List.of(1L, 3L, 4L) : List.of(1L, 2L, 3L), delivered);
            } finally {
                server.release.countDown();
                distributor.close();
            }
        }
    }

    @Test void acceleratedProductionRetainsOnlyTheConfiguredNumberOfFrames() throws Exception {
        TcpSettings settings = settings(1, 5000, 5000, 1024, 4, OverflowPolicy.DROP_NEWEST);
        BlockedServer server = new BlockedServer(settings);
        TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server);
        try {
            distributor.start();
            AnnotatedFrame frame = frame(1);
            distributor.distribute(frame);
            server.awaitBlocked();
            for (int i = 0; i < 50_000; i++) distributor.distribute(frame);
            assertEquals(4, distributor.monitoringSnapshot().queueDepth());
            assertEquals(49_996, distributor.getDroppedFrames());
            assertEquals(5, distributor.getFramesAccepted());
            server.release.countDown();
            distributor.stop();
            assertEquals(5, server.messages.size());
            assertEquals(0, distributor.monitoringSnapshot().queueDepth());
        } finally {
            server.release.countDown();
            distributor.close();
        }
    }

    @Test void interruptedShutdownStillDrainsTheFinalBatchAndRestoresTheInterruptFlag() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        TcpFrameDistributor distributor = new TcpFrameDistributor(settings, server);
        distributor.start();
        distributor.distribute(frame(1));
        Thread.currentThread().interrupt();
        try {
            distributor.stop();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(1, server.messages.size());
        } finally {
            Thread.interrupted();
            distributor.close();
        }
    }

    @Test void monitoringUsesCanonicalRuntimeMetricsAndTheSharedAcceptedFrameCounter() throws Exception {
        TcpSettings settings = settings(1, 5000, 30, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpFrameDistributor frames = new TcpFrameDistributor(settings, server);
             TcpMonitoringDistributor monitoring = new TcpMonitoringDistributor(settings, server)) {
            frames.start();
            monitoring.start();
            frames.distribute(frame(1));
            frames.distribute(frame(2));
            frames.distribute(frame(3));
            monitoring.publishMonitoring(new MonitoringSnapshot(123, Map.of(
                    "carrier.lock", "LOCKED", "symbol.lock", "SEARCHING", "frame.lock", "UNLOCKED",
                    "signal.snr_db", 4.5, "carrier.offset_hz", -123.0,
                    "source.center_frequency_hz", 145_000_000L,
                    "frame.total", 12L, "frame.bad", 2L, "frame.good", 10L)));
            byte[] wire;
            do { wire = server.next(); } while (wire[1] != 1);
            ByteBuffer payload = ByteBuffer.wrap(wire, 8, 32);
            assertEquals(TcpConstants.encodeStatus(1, 0, 2, 1), payload.get());
            long frequency = 0;
            for (int i = 0; i < 7; i++) frequency = frequency << 8 | Byte.toUnsignedInt(payload.get());
            assertEquals(145_000_000L, frequency);
            assertEquals(-123, payload.getInt());
            assertEquals(4.5f, payload.getFloat());
            assertEquals(12, payload.getInt());
            assertEquals(2, payload.getInt());
            assertEquals(10, payload.getInt());
            assertEquals(3, payload.getInt());
        }
    }

    @Test void monitoringPublicationDoesNotBypassTheConfiguredPeriodicCadence() throws Exception {
        TcpSettings settings = settings(99, 5000, 180, 1024, 512, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server)) {
            distributor.start();
            MonitoringSnapshot snapshot = new MonitoringSnapshot(1, Map.of("carrier.lock", "LOCKED"));
            for (int i = 0; i < 100; i++) distributor.publishMonitoring(snapshot);
            assertNull(server.messages.poll(70, TimeUnit.MILLISECONDS));
            assertEquals(1, server.next()[1]);
            assertNull(server.messages.poll(70, TimeUnit.MILLISECONDS));
            assertEquals(1, server.next()[1]);
        }
    }

    @Test void aNewClientReceivesCurrentMonitoringWithoutWaitingForThePeriodicInterval() throws Exception {
        int port;
        try (ServerSocket reservation = new ServerSocket(0)) { port = reservation.getLocalPort(); }
        TcpSettings settings = new TcpSettings(port, false, 2, 0, 0, true,
                5000, 5000, 99, 1024, 1_048_576, 42, OverflowPolicy.FAIL_RUNTIME);
        try (TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings)) {
            distributor.start();
            distributor.publishMonitoring(new MonitoringSnapshot(1, Map.of("carrier.lock", "LOCKED",
                    "source.center_frequency_hz", 145_000_000L)));
            try (Socket client = new Socket("127.0.0.1", port)) {
                client.setSoTimeout(1000);
                byte[] wire = new DataInputStream(client.getInputStream()).readNBytes(40);
                assertEquals(40, wire.length);
                assertEquals(1, wire[1]);
                assertEquals(TcpConstants.STATE_LOCKED, TcpConstants.decodeCarrierState(wire[8]));
                assertEquals(0, distributor.getMessagesSent(), "Connect snapshots must target only the new client");
            }
        }
    }

    @Test void promptEventsCarryNewStatesConsistentMetadataAndTheirOccurrenceTime() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server)) {
            distributor.start();
            distributor.publishEvent(new RuntimeEvent(123, EventSeverity.INFO, "decoder", "carrier.locked", "locked",
                    Map.of("source.center_frequency_hz", 145_000_000L)));
            byte[] first = server.next();
            assertEquals(2, first[8]);
            assertEquals(123, ByteBuffer.wrap(first).getLong(9));
            assertEquals(TcpConstants.STATE_LOCKED, TcpConstants.decodeCarrierState(first[17]));
            assertEquals(TcpConstants.STATE_UNKNOWN, TcpConstants.decodeSymbolState(first[17]));
            distributor.publishEvent(new RuntimeEvent(456, EventSeverity.INFO, "decoder", "symbol_state_changed", "locked",
                    Map.of("new_state", "LOCKED")));
            byte[] second = server.next();
            assertEquals(3, second[8]);
            assertEquals(456, ByteBuffer.wrap(second).getLong(9));
            assertEquals(TcpConstants.STATE_LOCKED, TcpConstants.decodeCarrierState(second[17]));
            assertEquals(TcpConstants.STATE_LOCKED, TcpConstants.decodeSymbolState(second[17]));
            assertArrayEquals(Arrays.copyOfRange(first, 18, 25), Arrays.copyOfRange(second, 18, 25));
            distributor.publishEvent(new RuntimeEvent(789, EventSeverity.INFO, "runtime", "runtime.started", "started", Map.of()));
            assertNull(server.messages.poll(70, TimeUnit.MILLISECONDS), "Runtime lifecycle is not decoder lifecycle");
        }
    }

    private static MonitoringSnapshot decoderSnapshot(long timestamp, String lifecycle, String lock, long frequency) {
        return new MonitoringSnapshot(timestamp, Map.of("runtime.decoder_state", lifecycle,
                "carrier.lock", lock, "symbol.lock", lock, "frame.lock", lock,
                "source.center_frequency_hz", frequency));
    }

    @Test void runtimeDecoderSnapshotsGenerateLifecycleErrorAndIndependentLockTransitions() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 16, OverflowPolicy.FAIL_RUNTIME);
        RecordingServer server = new RecordingServer(settings);
        try (TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server)) {
            distributor.start();
            distributor.publishMonitoring(decoderSnapshot(10, "RUNNING", "SEARCHING", 100));
            distributor.publishMonitoring(decoderSnapshot(20, "RUNNING", "LOCKED", 200));
            distributor.publishMonitoring(decoderSnapshot(30, "FAILED", "LOCKED", 300));
            distributor.publishMonitoring(decoderSnapshot(40, "STOPPED", "LOCKED", 400));
            distributor.stop();
            assertEquals(6, server.messages.size());
            List<Integer> codes = server.messages.stream().map(wire -> Byte.toUnsignedInt(wire[8])).toList();
            assertEquals(List.of(0, 2, 3, 4, 5, 1), codes);
            for (byte[] wire : server.messages) {
                long timestamp = ByteBuffer.wrap(wire).getLong(9);
                long frequency = 0;
                for (int i = 18; i < 25; i++) frequency = frequency << 8 | Byte.toUnsignedInt(wire[i]);
                assertEquals(timestamp * 10, frequency);
                if (timestamp == 20) {
                    assertEquals(1, TcpConstants.decodeCarrierState(wire[17]));
                    assertEquals(1, TcpConstants.decodeSymbolState(wire[17]));
                    assertEquals(1, TcpConstants.decodeFrameState(wire[17]));
                }
            }
            assertEquals(0, distributor.monitoringSnapshot().queueDepth());
        }
    }

    @Test void monitoringSnapshotsAndEventsShareABoundedQueueWithoutRetainedEventHistory() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 2, OverflowPolicy.DROP_NEWEST);
        BlockedServer server = new BlockedServer(settings);
        TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server);
        try {
            distributor.start();
            RuntimeEvent event = new RuntimeEvent(1, EventSeverity.INFO, "decoder", "decoder.error", "error", Map.of());
            distributor.publishEvent(event);
            server.awaitBlocked();
            distributor.publishMonitoring(new MonitoringSnapshot(1, Map.of()));
            distributor.publishEvent(event);
            for (int i = 0; i < 50_000; i++) distributor.publishEvent(event);
            assertEquals(2, distributor.monitoringSnapshot().queueDepth());
            assertEquals(2, distributor.monitoringSnapshot().queueCapacity());
            assertEquals(50_000, distributor.getDroppedMessages());
            server.release.countDown();
            distributor.stop();
            assertEquals(0, distributor.monitoringSnapshot().queueDepth());
            assertEquals(ComponentState.STOPPED, distributor.state());
            assertNull(distributor.getFailure());
        } finally {
            server.release.countDown();
            distributor.close();
        }
    }

    @Test void concurrentlyRequestedConnectSnapshotsKeepStatusAndFrequencyFromOneObservation() throws Exception {
        TcpSettings settings = settings(99, 5000, 5000, 1024, 8, OverflowPolicy.DROP_OLDEST);
        RecordingServer server = new RecordingServer(settings);
        try (TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server)) {
            distributor.start();
            MonitoringSnapshot locked = new MonitoringSnapshot(1, Map.of("carrier.lock", "LOCKED", "source.center_frequency_hz", 100L));
            MonitoringSnapshot unlocked = new MonitoringSnapshot(2, Map.of("carrier.lock", "UNLOCKED", "source.center_frequency_hz", 200L));
            distributor.publishMonitoring(locked);
            Thread producer = new Thread(() -> {
                for (int i = 0; i < 5000; i++) distributor.publishMonitoring((i & 1) == 0 ? unlocked : locked);
            });
            producer.start();
            try {
                for (int i = 0; i < 5000; i++) {
                    byte[] wire = server.supplier.get();
                    int state = TcpConstants.decodeCarrierState(wire[8]);
                    long frequency = 0;
                    for (int offset = 9; offset < 16; offset++) frequency = frequency << 8 | Byte.toUnsignedInt(wire[offset]);
                    assertEquals(state == 1 ? 100 : 200, frequency);
                }
            } finally {
                producer.join();
            }
        }
    }

    @Test void monitoringProducerQueueEnforcesFailNewestAndOldestPolicies() throws Exception {
        for (OverflowPolicy policy : OverflowPolicy.values()) {
            TcpSettings settings = settings(99, 5000, 5000, 1024, 2, policy);
            BlockedServer server = new BlockedServer(settings);
            TcpMonitoringDistributor distributor = new TcpMonitoringDistributor(settings, server);
            try {
                distributor.start();
                RuntimeEvent first = new RuntimeEvent(1, EventSeverity.INFO, "decoder", "decoder.error", "error", Map.of());
                distributor.publishEvent(first);
                server.awaitBlocked();
                distributor.publishMonitoring(new MonitoringSnapshot(2, Map.of()));
                distributor.publishEvent(new RuntimeEvent(3, EventSeverity.INFO, "decoder", "decoder.error", "error", Map.of()));
                RuntimeEvent incoming = new RuntimeEvent(4, EventSeverity.INFO, "decoder", "decoder.error", "error", Map.of());
                if (policy == OverflowPolicy.FAIL_RUNTIME) {
                    assertThrows(IllegalStateException.class, () -> distributor.publishEvent(incoming));
                    assertEquals(ComponentState.FAILED, distributor.state());
                } else distributor.publishEvent(incoming);
                assertEquals(2, distributor.monitoringSnapshot().queueDepth());
                assertEquals(1, distributor.getDroppedMessages());
                server.release.countDown();
                distributor.stop();
                List<Long> timestamps = server.messages.stream().filter(wire -> wire[8] == 5)
                        .map(wire -> ByteBuffer.wrap(wire).getLong(9)).toList();
                assertEquals(policy == OverflowPolicy.DROP_OLDEST ? List.of(1L, 3L, 4L) : List.of(1L, 3L), timestamps);
                assertEquals(0, distributor.monitoringSnapshot().queueDepth());
            } finally {
                server.release.countDown();
                distributor.close();
            }
        }
    }
}
