package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.FrameRecord;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bounded asynchronous JTDP frame producer. Batching deadlines are measured from
 * submission on the producer, independently of recording timestamps and traffic.
 * Network backpressure is handled separately by the server's byte-bounded queues.
 */
public final class TcpFrameDistributor implements FrameDistributor {
    private record PendingFrame(AnnotatedFrame frame, long submittedNanos, int recordLength) { }

    private final TcpSettings settings;
    private final TcpServer server;
    private final TcpMessageEncoder encoder;
    private final boolean serverOwned;
    private final boolean managedServer;
    private boolean serverReleased;
    private final ArrayDeque<PendingFrame> queue = new ArrayDeque<>();
    private final Object queueLock = new Object();
    private volatile boolean accepting;
    private Thread worker;
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicLong errorCounter = new AtomicLong();
    private final AtomicReference<ComponentState> state = new AtomicReference<>(ComponentState.CONFIGURED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public TcpFrameDistributor(TcpSettings settings) {
        this(settings, null, false);
    }

    /** Uses a caller-owned server; its lifecycle remains with the caller. */
    public TcpFrameDistributor(TcpSettings settings, TcpServer server) {
        this(settings, server, false);
    }

    /** A managed server is a reference acquired from {@link TcpServerRegistry}. */
    public TcpFrameDistributor(TcpSettings settings, TcpServer server, boolean managedServer) {
        if (settings == null) throw new IllegalArgumentException("Settings cannot be null");
        if (managedServer && server == null) throw new IllegalArgumentException("Managed server cannot be null");
        this.settings = settings;
        this.serverOwned = server == null;
        this.managedServer = managedServer;
        this.server = server == null ? new TcpServer(settings) : server;
        this.encoder = new TcpMessageEncoder(settings.sourceId(), settings.maxMessageBytes());
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("tcp", "JSatDecode TCP Frame Distributor", "1",
                "Distributes frames to TCP clients using JTDP v1 protocol");
    }

    @Override
    public void distribute(AnnotatedFrame frame) {
        if (frame == null) throw new IllegalArgumentException("Frame cannot be null");
        boolean overflow = false;
        boolean accepted = true;
        RuntimeException overflowFailure = null;
        synchronized (queueLock) {
            requireRunning();
            messagesReceived.incrementAndGet();
            if (frame.dataLength() == 0) {
                droppedFrames.incrementAndGet();
                throw new IllegalArgumentException("JTDP frames must contain at least one byte");
            }
            long recordLength = 13L + frame.dataLength();
            if (recordLength + TcpConstants.HEADER_LENGTH > settings.maxMessageBytes()) {
                droppedFrames.incrementAndGet();
                throw new IllegalArgumentException("Frame too large: " + frame.dataLength() +
                        " bytes exceeds max message size");
            }
            if (queue.size() == settings.queueCapacity()) {
                overflow = true;
                droppedFrames.incrementAndGet();
                if (settings.overflowPolicy() == OverflowPolicy.DROP_OLDEST) {
                    queue.removeFirst();
                } else {
                    accepted = false;
                    if (settings.overflowPolicy() == OverflowPolicy.FAIL_RUNTIME) {
                        overflowFailure = new IllegalStateException("TCP frame producer queue overflow");
                        failure.compareAndSet(null, overflowFailure);
                        errorCounter.incrementAndGet();
                        accepting = false;
                        state.set(ComponentState.FAILED);
                    }
                }
            }
            if (accepted) {
                queue.addLast(new PendingFrame(frame, System.nanoTime(), (int) recordLength));
                server.recordFramesAccepted(1);
            }
            queueLock.notifyAll();
        }
        if (overflow) server.reportProducerOverflow(overflowFailure == null);
        if (overflowFailure != null) throw overflowFailure;
    }

    private void requireRunning() {
        if (state.get() != ComponentState.RUNNING || !accepting) {
            throw new IllegalStateException("Distributor is not running: " + state.get(), failure.get());
        }
    }

    private void frameLoop() {
        List<FrameRecord> batch = new ArrayList<>();
        int batchBytes = TcpConstants.HEADER_LENGTH;
        long firstSubmitted = 0;
        long latencyNanos = TimeUnit.MILLISECONDS.toNanos(settings.maxFrameLatencyMs());
        try {
            while (true) {
                PendingFrame pending = null;
                boolean flush = false;
                synchronized (queueLock) {
                    while (true) {
                        if (!batch.isEmpty() && (!accepting || System.nanoTime() - firstSubmitted >= latencyNanos)) {
                            flush = true;
                            break;
                        }
                        pending = queue.pollFirst();
                        if (pending != null || !accepting) break;
                        if (batch.isEmpty()) queueLock.wait();
                        else TimeUnit.NANOSECONDS.timedWait(queueLock,
                                Math.max(1, latencyNanos - (System.nanoTime() - firstSubmitted)));
                    }
                    if (!accepting && queue.isEmpty() && pending == null && batch.isEmpty()) break;
                }
                if (flush) {
                    flush(batch);
                    batchBytes = TcpConstants.HEADER_LENGTH;
                    firstSubmitted = 0;
                    continue;
                }
                if (pending == null) continue;
                if (!batch.isEmpty() && (long) batchBytes + pending.recordLength() > settings.maxMessageBytes()) {
                    flush(batch);
                    batchBytes = TcpConstants.HEADER_LENGTH;
                    firstSubmitted = 0;
                }
                if (batch.isEmpty()) firstSubmitted = pending.submittedNanos();
                batch.add(FrameRecord.fromAnnotatedFrame(pending.frame(), quality(pending.frame())));
                batchBytes += pending.recordLength();
                if (batch.size() >= settings.maxFrameBuffer() || System.nanoTime() - firstSubmitted >= latencyNanos) {
                    flush(batch);
                    batchBytes = TcpConstants.HEADER_LENGTH;
                    firstSubmitted = 0;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } catch (RuntimeException e) {
            fail(e);
        } finally {
            if (!batch.isEmpty()) flush(batch);
        }
    }

    private static byte quality(AnnotatedFrame frame) {
        Object value = frame.annotations().get("quality");
        if (value instanceof Number number) {
            int quality = number.intValue();
            if (quality >= 0 && quality <= 2) return (byte) quality;
        } else if (value != null) {
            return switch (value.toString().toUpperCase(Locale.ROOT)) {
                case "GOOD" -> TcpConstants.FRAME_QUALITY_GOOD;
                case "BAD" -> TcpConstants.FRAME_QUALITY_BAD;
                default -> TcpConstants.FRAME_QUALITY_UNKNOWN;
            };
        }
        return TcpConstants.FRAME_QUALITY_UNKNOWN;
    }

    private void flush(List<FrameRecord> batch) {
        if (batch.isEmpty()) return;
        try {
            server.broadcast(encoder.encodeFrameBatch(batch));
            messagesSent.incrementAndGet();
        } catch (RuntimeException e) {
            droppedFrames.addAndGet(batch.size());
            fail(e);
        } finally {
            batch.clear();
        }
    }

    private void fail(Throwable error) {
        errorCounter.incrementAndGet();
        failure.compareAndSet(null, error);
        synchronized (queueLock) {
            accepting = false;
            state.set(ComponentState.FAILED);
            droppedFrames.addAndGet(queue.size());
            queue.clear();
            queueLock.notifyAll();
        }
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        synchronized (queueLock) {
            return new DistributionSnapshot(isRunning(), server.isRunning(), messagesReceived.get(),
                    messagesSent.get(), droppedFrames.get(), errorCounter.get(), queue.size(),
                    settings.queueCapacity(), UtcTime.nowNanos());
        }
    }

    @Override
    public synchronized void start() throws Exception {
        if (!state.compareAndSet(ComponentState.CONFIGURED, ComponentState.STARTING)) {
            throw new IllegalStateException("Distributor is one-shot: current state = " + state.get());
        }
        try {
            if (serverOwned) server.start();
            else if (managedServer) TcpServerRegistry.startServer(server);
            accepting = true;
            state.set(ComponentState.RUNNING);
            worker = new Thread(this::frameLoop, "jsatdecode-tcp-frames");
            worker.setDaemon(true);
            worker.start();
        } catch (Exception e) {
            fail(e);
            releaseServer();
            throw e;
        }
    }

    @Override
    public synchronized void stop() {
        if (state.get() == ComponentState.CLOSED || state.get() == ComponentState.STOPPED) return;
        synchronized (queueLock) {
            accepting = false;
            if (state.get() != ComponentState.FAILED) state.set(ComponentState.STOPPING);
            queueLock.notifyAll();
        }
        boolean interrupted = Thread.interrupted();
        try {
            if (worker != null && worker != Thread.currentThread()) {
                while (worker.isAlive()) {
                    try {
                        worker.join();
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            releaseServer();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (state.get() != ComponentState.FAILED) state.set(ComponentState.STOPPED);
    }

    private void releaseServer() {
        if (serverReleased) return;
        serverReleased = true;
        if (managedServer) TcpServerRegistry.releaseServer(settings.port());
        else if (serverOwned) server.stop();
    }

    @Override
    public synchronized void close() {
        stop();
        state.set(ComponentState.CLOSED);
    }

    @Override public ComponentState state() { return state.get(); }
    public TcpServer getServer() { return server; }
    public TcpSettings getSettings() { return settings; }
    public long getMessagesReceived() { return messagesReceived.get(); }
    public long getMessagesSent() { return messagesSent.get(); }
    public long getDroppedFrames() { return droppedFrames.get(); }
    public long getFramesAccepted() { return server.getFramesAccepted(); }
    public Throwable getFailure() { return failure.get(); }

    @Override
    public String toString() {
        return "TcpFrameDistributor[state=" + state.get() + ", messagesReceived=" + messagesReceived.get() +
                ", messagesSent=" + messagesSent.get() + "]";
    }
}
