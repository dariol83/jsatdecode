package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * TCP-based monitoring distributor implementation for JTDP v1.
 *
 * <p>This class implements the {@link MonitoringDistributor} interface, providing
 * a TCP server that distributes monitoring snapshots and runtime events to
 * connected clients using the JTDP v1 binary protocol.
 *
 * <h2>Distribution Behavior</h2>
 * <ul>
 *   <li>Monitoring snapshots are sent periodically at the configured interval</li>
 *   <li>An initial snapshot is sent when a client connects</li>
 *   <li>Runtime events are sent immediately as they occur</li>
 *   <li>All messages are broadcast to all connected clients</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The {@link #publishMonitoring(MonitoringSnapshot)}
 * and {@link #publishEvent(RuntimeEvent)} methods can be called from any thread.
 *
 * <h2>Integration</h2>
 * <p>This distributor integrates with JSatDecode's distribution framework via
 * the {@link TcpMonitoringDistributorFactory} mechanism. It can be selected
 * by setting the configuration property {@code distribution.monitoring.type=tcp}.
 *
 * @see TcpMonitoringDistributorFactory
 * @see TcpServer
 * @see MonitoringDistributor
 */
public final class TcpMonitoringDistributor implements MonitoringDistributor {

    private final TcpSettings settings;
    private final TcpServer server;
    private final TcpMessageEncoder encoder;
    private final boolean serverOwned;

    // Monitoring state
    private volatile MonitoringSnapshot lastSnapshot;
    private final Object snapshotLock = new Object();

    // Event queue
    private final CopyOnWriteArrayList<RuntimeEvent> eventQueue = new CopyOnWriteArrayList<>();
    private final Object eventLock = new Object();

    // Periodic monitoring distribution
    private Thread monitoringThread;
    private final AtomicBoolean monitoringRunning = new AtomicBoolean(false);

    // Statistics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong errorCounter = new AtomicLong(0);

    // State management
    private final AtomicReference<ComponentState> state = new AtomicReference<>(ComponentState.CONFIGURED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    // Status tracking
    private volatile byte lastStatusByte = 0;
    private volatile long lastCenterFrequencyHz = 0;

    /**
     * Creates a new TCP monitoring distributor with the specified settings.
     * This constructor creates and owns its own TcpServer instance.
     *
     * @param settings The TCP distribution settings
     * @throws IllegalArgumentException if settings is null
     */
    public TcpMonitoringDistributor(TcpSettings settings) {
        this(settings, null);
    }

    /**
     * Creates a new TCP monitoring distributor with the specified settings and server.
     * If server is provided, it will be shared with this distributor. If null,
     * a new server will be created.
     *
     * @param settings The TCP distribution settings
     * @param server   The TCP server to use, or null to create a new one
     * @throws IllegalArgumentException if settings is null
     */
    public TcpMonitoringDistributor(TcpSettings settings, TcpServer server) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        this.settings = settings;
        this.serverOwned = (server == null);
        this.server = (server != null) ? server : new TcpServer(settings);
        this.encoder = new TcpMessageEncoder(settings.sourceId(), settings.maxMessageBytes());
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor(
                "tcp",
                "JSatDecode TCP Monitoring Distributor",
                "1",
                "Distributes monitoring snapshots and events to TCP clients using JTDP v1 protocol"
        );
    }

    @Override
    public void publishMonitoring(MonitoringSnapshot snapshot) {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.RUNNING) {
            if (currentState == ComponentState.FAILED) {
                throw new IllegalStateException("Distributor failed", failure.get());
            }
            throw new IllegalStateException("Distributor is not running: " + currentState);
        }

        if (snapshot == null) {
            throw new IllegalArgumentException("Snapshot cannot be null");
        }

        messagesReceived.incrementAndGet();

        synchronized (snapshotLock) {
            lastSnapshot = snapshot;
        }

        // Extract status information
        updateStatusFromSnapshot(snapshot);

        // Send immediately to all clients
        sendMonitoringSnapshot(snapshot);
    }

    /**
     * Updates status byte and center frequency from a monitoring snapshot.
     *
     * @param snapshot The monitoring snapshot
     */
    private void updateStatusFromSnapshot(MonitoringSnapshot snapshot) {
        int carrierState = TcpConstants.STATE_UNKNOWN;
        int symbolState = TcpConstants.STATE_UNKNOWN;
        int frameState = TcpConstants.STATE_UNKNOWN;
        int snrAvailable = TcpConstants.SNR_UNAVAILABLE;

        Object carrierObj = snapshot.values().get("carrier_state");
        if (carrierObj instanceof Number) {
            carrierState = ((Number) carrierObj).intValue();
        }

        Object symbolObj = snapshot.values().get("symbol_state");
        if (symbolObj instanceof Number) {
            symbolState = ((Number) symbolObj).intValue();
        }

        Object frameObj = snapshot.values().get("frame_state");
        if (frameObj instanceof Number) {
            frameState = ((Number) frameObj).intValue();
        }

        Object snrObj = snapshot.values().get("snr_db");
        if (snrObj instanceof Number) {
            float snr = ((Number) snrObj).floatValue();
            if (Float.isFinite(snr)) {
                snrAvailable = TcpConstants.SNR_AVAILABLE;
            }
        }

        lastStatusByte = TcpConstants.encodeStatus(carrierState, symbolState, frameState, snrAvailable);

        Object freqObj = snapshot.values().get("center_frequency_hz");
        if (freqObj instanceof Number) {
            lastCenterFrequencyHz = ((Number) freqObj).longValue();
        }
    }

    /**
     * Sends a monitoring snapshot to all connected clients.
     *
     * @param snapshot The monitoring snapshot
     */
    private void sendMonitoringSnapshot(MonitoringSnapshot snapshot) {
        try {
            byte[] message = encoder.encodeMonitoring(snapshot, lastStatusByte, lastCenterFrequencyHz);
            if (message != null) {
                server.broadcast(message);
                messagesSent.incrementAndGet();
            }
        } catch (Exception e) {
            errorCounter.incrementAndGet();
            failure.compareAndSet(null, e);
        }
    }

    @Override
    public void publishEvent(RuntimeEvent event) {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.RUNNING) {
            if (currentState == ComponentState.FAILED) {
                throw new IllegalStateException("Distributor failed", failure.get());
            }
            throw new IllegalStateException("Distributor is not running: " + currentState);
        }

        if (event == null) {
            throw new IllegalArgumentException("Event cannot be null");
        }

        messagesReceived.incrementAndGet();

        synchronized (eventLock) {
            eventQueue.add(event);
        }

        // Send immediately to all clients
        sendEvent(event);
    }

    /**
     * Sends an event to all connected clients.
     *
     * @param event The runtime event
     */
    private void sendEvent(RuntimeEvent event) {
        try {
            byte[] message = encoder.encodeEvent(event, lastStatusByte, lastCenterFrequencyHz);
            if (message != null) {
                server.broadcast(message);
                messagesSent.incrementAndGet();
            }
        } catch (Exception e) {
            errorCounter.incrementAndGet();
            failure.compareAndSet(null, e);
        }
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        return new DistributionSnapshot(
                isRunning(),
                server != null && server.isRunning(),
                messagesReceived.get(),
                messagesSent.get(),
                0, // messagesDropped
                errorCounter.get(),
                eventQueue.size(),
                settings.maxClients(),
                System.nanoTime()
        );
    }

    @Override
    public void start() throws Exception {
        if (state.get() != ComponentState.CONFIGURED) {
            throw new IllegalStateException("Distributor is one-shot: current state = " + state.get());
        }

        state.set(ComponentState.STARTING);

        try {
            // Start the TCP server only if we own it
            if (serverOwned) {
                server.start();
            }

            // Start periodic monitoring thread
            monitoringRunning.set(true);
            monitoringThread = new Thread(this::monitoringLoop, "jsatdecode-tcp-monitoring");
            monitoringThread.setDaemon(true);
            monitoringThread.start();

            state.set(ComponentState.RUNNING);

        } catch (Exception e) {
            state.set(ComponentState.FAILED);
            failure.set(e);
            stop();
            throw e;
        }
    }

    /**
     * The monitoring loop that sends periodic snapshots.
     */
    private void monitoringLoop() {
        try {
            while (monitoringRunning.get() && state.get() == ComponentState.RUNNING) {
                // Send current snapshot if available
                MonitoringSnapshot snapshot;
                synchronized (snapshotLock) {
                    snapshot = lastSnapshot;
                }

                if (snapshot != null) {
                    sendMonitoringSnapshot(snapshot);
                }

                // Wait for the configured interval
                long intervalMs = settings.monitoringIntervalMs();
                for (int i = 0; i < intervalMs / 100 && monitoringRunning.get(); i++) {
                    LockSupport.parkNanos(100_000_000); // 100ms
                }
            }
        } catch (Exception e) {
            errorCounter.incrementAndGet();
            failure.compareAndSet(null, e);
        }
    }

    @Override
    public void stop() {
        if (state.get() == ComponentState.CLOSED || state.get() == ComponentState.STOPPED) {
            return;
        }

        // Signal monitoring thread to stop
        monitoringRunning.set(false);

        // Stop the server only if we own it
        if (serverOwned) {
            try {
                server.stop();
            } catch (Exception e) {
                // Ignore
            }
        }

        // Wait for monitoring thread to finish
        if (monitoringThread != null && monitoringThread.isAlive()) {
            try {
                monitoringThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (state.get() != ComponentState.FAILED) {
            state.set(ComponentState.STOPPED);
        }
    }

    @Override
    public void close() {
        stop();
        state.set(ComponentState.CLOSED);
    }

    @Override
    public ComponentState state() {
        return state.get();
    }

    /**
     * Returns the underlying TCP server.
     *
     * @return The TCP server instance
     */
    public TcpServer getServer() {
        return server;
    }

    /**
     * Gets the settings used by this distributor.
     *
     * @return The TCP settings
     */
    public TcpSettings getSettings() {
        return settings;
    }

    /**
     * Gets the number of messages received.
     *
     * @return The message count
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    /**
     * Gets the number of messages sent.
     *
     * @return The message count
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }

    /**
     * Gets the first failure, if any.
     *
     * @return The first failure, or null
     */
    public Throwable getFailure() {
        return failure.get();
    }

    @Override
    public String toString() {
        return "TcpMonitoringDistributor[" +
                "state=" + state.get() + ", " +
                "messagesReceived=" + messagesReceived.get() + ", " +
                "messagesSent=" + messagesSent.get() + "]";
    }
}
