package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.InitialMonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.EventMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpStatus;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Asynchronous JTDP monitoring and event producer. Snapshots and events share one
 * bounded FIFO. Events are sent promptly; snapshots update one immutable current
 * observation and are broadcast only at the configured monotonic cadence.
 */
public final class TcpMonitoringDistributor implements MonitoringDistributor, InitialMonitoringSnapshot {
    private record Observation(MonitoringSnapshot snapshot, byte status, long frequency, long accepted) { }
    private record Publication(Observation observation, RuntimeEvent event) { }
    private record OverflowNotification(long timestamp, Observation observation) { }

    private final TcpSettings settings;
    private final TcpServer server;
    private final TcpMessageEncoder encoder;
    private final boolean serverOwned;
    private final boolean managedServer;
    private boolean serverReleased;
    private final Object queueLock = new Object();
    private final ArrayDeque<Publication> queue = new ArrayDeque<>();
    private volatile Observation current = new Observation(new MonitoringSnapshot(UtcTime.nowNanos(), Map.of()),
            TcpConstants.encodeStatus(TcpConstants.STATE_UNKNOWN, TcpConstants.STATE_UNKNOWN,
                    TcpConstants.STATE_UNKNOWN, TcpConstants.SNR_UNAVAILABLE), 0, 0);
    // Coalescing overflow notifications retain constant memory even while the FIFO is full.
    private final AtomicReference<OverflowNotification> pendingOverrun = new AtomicReference<>();
    private final AtomicReference<OverflowNotification> pendingDiscard = new AtomicReference<>();
    private volatile boolean accepting;
    private Thread worker;
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong messagesDropped = new AtomicLong();
    private final AtomicLong errorCounter = new AtomicLong();
    private final AtomicReference<ComponentState> state = new AtomicReference<>(ComponentState.CONFIGURED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    public TcpMonitoringDistributor(TcpSettings settings) {
        this(settings, null, false);
    }

    /** Uses a caller-owned server; its lifecycle remains with the caller. */
    public TcpMonitoringDistributor(TcpSettings settings, TcpServer server) {
        this(settings, server, false);
    }

    /** A managed server is a reference acquired from {@link TcpServerRegistry}. */
    public TcpMonitoringDistributor(TcpSettings settings, TcpServer server, boolean managedServer) {
        if (settings == null) throw new IllegalArgumentException("Settings cannot be null");
        if (managedServer && server == null) throw new IllegalArgumentException("Managed server cannot be null");
        this.settings = settings;
        this.serverOwned = server == null;
        this.managedServer = managedServer;
        this.server = server == null ? new TcpServer(settings) : server;
        this.encoder = new TcpMessageEncoder(settings.sourceId(), settings.maxMessageBytes());
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("tcp", "JSatDecode TCP Monitoring Distributor", "1",
                "Distributes monitoring snapshots and events to TCP clients using JTDP v1 protocol");
    }

    @Override
    public void publishMonitoring(MonitoringSnapshot snapshot) {
        if (snapshot == null) throw new IllegalArgumentException("Snapshot cannot be null");
        RuntimeException overflowFailure;
        boolean overflow;
        synchronized (queueLock) {
            requireRunning();
            messagesReceived.incrementAndGet();
            Observation observation = new Observation(snapshot, TcpStatus.snapshotStatus(snapshot),
                    TcpStatus.centerFrequency(snapshot), server.getFramesAccepted());
            overflow = queue.size() == settings.queueCapacity();
            overflowFailure = enqueue(new Publication(observation, null));
        }
        if (overflow) server.reportProducerOverflow(overflowFailure == null);
        if (overflowFailure != null) throw overflowFailure;
    }

    @Override
    public void publishEvent(RuntimeEvent event) {
        if (event == null) throw new IllegalArgumentException("Event cannot be null");
        RuntimeException overflowFailure;
        boolean overflow;
        synchronized (queueLock) {
            requireRunning();
            messagesReceived.incrementAndGet();
            if (TcpStatus.mapEventCode(event.type()) == -1) return;
            Observation prior = current;
            Observation observation = new Observation(prior.snapshot(), TcpStatus.eventStatus(event, prior.status()),
                    TcpStatus.eventCenterFrequency(event, prior.frequency()), prior.accepted());
            overflow = queue.size() == settings.queueCapacity();
            overflowFailure = enqueue(new Publication(observation, event));
        }
        if (overflow) server.reportProducerOverflow(overflowFailure == null);
        if (overflowFailure != null) throw overflowFailure;
    }

    /** Called with queueLock held; no encoding or server enqueue occurs on producers. */
    private RuntimeException enqueue(Publication publication) {
        RuntimeException overflowFailure = null;
        boolean accepted = true;
        if (queue.size() == settings.queueCapacity()) {
            messagesDropped.incrementAndGet();
            if (settings.overflowPolicy() == OverflowPolicy.DROP_OLDEST) {
                queue.removeFirst();
            } else {
                accepted = false;
                if (settings.overflowPolicy() == OverflowPolicy.FAIL_RUNTIME) {
                    overflowFailure = new IllegalStateException("TCP monitoring producer queue overflow");
                    failure.compareAndSet(null, overflowFailure);
                    errorCounter.incrementAndGet();
                    accepting = false;
                    state.set(ComponentState.FAILED);
                }
            }
        }
        if (accepted) {
            queue.addLast(publication);
            current = publication.observation();
        }
        queueLock.notifyAll();
        return overflowFailure;
    }

    private void requireRunning() {
        if (state.get() != ComponentState.RUNNING || !accepting) {
            throw new IllegalStateException("Distributor is not running: " + state.get(), failure.get());
        }
    }

    private byte[] initialMonitoring() {
        return encodeObservation(current);
    }

    private byte[] encodeObservation(Observation observation) {
        return encoder.encodeMonitoring(observation.snapshot(), observation.status(), observation.frequency(),
                observation.accepted());
    }

    private void producerOverflow(boolean discarded) {
        if (!accepting) return;
        OverflowNotification notification = new OverflowNotification(UtcTime.nowNanos(), current);
        pendingOverrun.compareAndSet(null, notification);
        if (discarded) pendingDiscard.compareAndSet(null, notification);
        synchronized (queueLock) {
            queueLock.notifyAll();
        }
    }

    private void monitoringLoop() {
        long intervalNanos = TimeUnit.MILLISECONDS.toNanos(settings.monitoringIntervalMs());
        long nextSnapshot = System.nanoTime() + intervalNanos;
        Observation previous = null;
        String decoderState = null;
        try {
            while (true) {
                OverflowNotification overflow = pendingOverrun.getAndSet(null);
                if (overflow != null) sendEvent(event(overflow.timestamp(), "distribution.overrun"), overflow.observation());
                OverflowNotification discard = pendingDiscard.getAndSet(null);
                if (discard != null) sendEvent(event(discard.timestamp(), "data.discarded"), discard.observation());
                long now = System.nanoTime();
                if (accepting && now - nextSnapshot >= 0) {
                    broadcast(encodeObservation(current));
                    nextSnapshot = System.nanoTime() + intervalNanos;
                }
                Publication publication;
                synchronized (queueLock) {
                    publication = queue.pollFirst();
                    if (publication == null) {
                        if (!accepting && pendingOverrun.get() == null && pendingDiscard.get() == null) break;
                        if (pendingOverrun.get() == null && pendingDiscard.get() == null) {
                            TimeUnit.NANOSECONDS.timedWait(queueLock, Math.max(1, nextSnapshot - System.nanoTime()));
                        }
                        continue;
                    }
                }
                Observation observation = publication.observation();
                if (publication.event() != null) {
                    sendEvent(publication.event(), observation);
                    previous = observation;
                } else {
                    Object observedState = observation.snapshot().values().get("runtime.decoder_state");
                    String newDecoderState = observedState == null ? null : observedState.toString();
                    if (newDecoderState != null && !newDecoderState.equals(decoderState)) {
                        if ("RUNNING".equals(newDecoderState)) {
                            sendEvent(event(observation.snapshot().timestampNanos(), "decoder.started"), observation);
                        } else if ("STOPPED".equals(newDecoderState) || "CLOSED".equals(newDecoderState)) {
                            if (!"STOPPED".equals(decoderState) && !"CLOSED".equals(decoderState)) {
                                sendEvent(event(observation.snapshot().timestampNanos(), "decoder.stopped"), observation);
                            }
                        } else if ("FAILED".equals(newDecoderState)) {
                            sendEvent(event(observation.snapshot().timestampNanos(), "decoder.error"), observation);
                        }
                        decoderState = newDecoderState;
                    }
                    if (previous != null) {
                        byte changed = (byte) (previous.status() ^ observation.status());
                        if ((changed & TcpConstants.STATUS_CARRIER_MASK) != 0) sendEvent(event(observation.snapshot().timestampNanos(), "carrier_state_changed"), observation);
                        if ((changed & TcpConstants.STATUS_SYMBOL_MASK) != 0) sendEvent(event(observation.snapshot().timestampNanos(), "symbol_state_changed"), observation);
                        if ((changed & TcpConstants.STATUS_FRAME_MASK) != 0) sendEvent(event(observation.snapshot().timestampNanos(), "frame_state_changed"), observation);
                    }
                    previous = observation;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(e);
        } catch (RuntimeException e) {
            fail(e);
        }
    }

    private static RuntimeEvent event(long timestamp, String type) {
        return new RuntimeEvent(timestamp, EventSeverity.INFO, "tcp", type, type, Map.of());
    }

    private void sendEvent(RuntimeEvent event, Observation observation) {
        byte code = TcpStatus.mapEventCode(event.type());
        if (code != -1) broadcast(encoder.encodeEvent(new EventMessage(code, event.timestampNanos(),
                observation.status(), observation.frequency())));
    }

    private void broadcast(byte[] message) {
        server.broadcast(message);
        messagesSent.incrementAndGet();
    }

    private void fail(Throwable error) {
        errorCounter.incrementAndGet();
        failure.compareAndSet(null, error);
        synchronized (queueLock) {
            accepting = false;
            state.set(ComponentState.FAILED);
            messagesDropped.addAndGet(queue.size());
            queue.clear();
            queueLock.notifyAll();
        }
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        synchronized (queueLock) {
            return new DistributionSnapshot(isRunning(), server.isRunning(), messagesReceived.get(),
                    messagesSent.get(), messagesDropped.get(), errorCounter.get(), queue.size(),
                    settings.queueCapacity(), UtcTime.nowNanos());
        }
    }

    @Override
    public synchronized void start() throws Exception {
        if (!state.compareAndSet(ComponentState.CONFIGURED, ComponentState.STARTING)) {
            throw new IllegalStateException("Distributor is one-shot: current state = " + state.get());
        }
        try {
            server.setMonitoringSupplier(this::initialMonitoring);
            server.setProducerOverflowListener(this::producerOverflow);
            if (serverOwned) server.start();
            else if (managedServer) TcpServerRegistry.startServer(server);
            accepting = true;
            state.set(ComponentState.RUNNING);
            worker = new Thread(this::monitoringLoop, "jsatdecode-tcp-monitoring");
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
        server.setMonitoringSupplier(null);
        server.setProducerOverflowListener(null);
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
    public long getDroppedMessages() { return messagesDropped.get(); }
    public Throwable getFailure() { return failure.get(); }

    @Override
    public String toString() {
        return "TcpMonitoringDistributor[state=" + state.get() + ", messagesReceived=" + messagesReceived.get() +
                ", messagesSent=" + messagesSent.get() + "]";
    }
}
