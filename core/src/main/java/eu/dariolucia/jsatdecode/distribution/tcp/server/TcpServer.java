package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * JTDP listener shared by the enabled adapters. Broadcasts only reserve queue
 * space; independent virtual I/O tasks handle each client. The distribution lock
 * orders initial monitoring and normal messages without performing network I/O.
 */
public class TcpServer implements LifecycleComponent {
    private static final long SHUTDOWN_DRAIN_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final TcpSettings settings;
    private final String bindAddress;
    private final Object distributionLock = new Object();
    private volatile ServerSocket serverSocket;
    private Thread acceptorThread;
    private ExecutorService clientExecutor;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final Map<Long, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong(1);
    private final AtomicLong totalConnections = new AtomicLong();
    private final AtomicLong activeConnections = new AtomicLong();
    private final AtomicLong messagesReceived = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong framesAccepted = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile Throwable failure;
    private volatile MessageListener messageListener;
    private Supplier<byte[]> monitoringSupplier;
    private volatile Consumer<Boolean> producerOverflowListener;

    public TcpServer(TcpSettings settings, String bindAddress) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        this.settings = settings;
        this.bindAddress = bindAddress != null ? bindAddress : "0.0.0.0";
    }

    public TcpServer(TcpSettings settings) { this(settings, "0.0.0.0"); }
    public TcpSettings getSettings() { return settings; }
    public String getBindAddress() { return bindAddress; }
    public int getPort() { return settings.port(); }
    public void setMessageListener(MessageListener listener) { messageListener = listener; }

    /**
     * Supplies the current monitoring message to newly accepted connections.
     * The supplier must read a consistent observation without taking a producer
     * lock also held during broadcast. Null disables initial monitoring.
     */
    public void setMonitoringSupplier(Supplier<byte[]> supplier) {
        synchronized (distributionLock) {
            monitoringSupplier = supplier;
        }
    }

    /** Installs (or clears) the enabled monitoring adapter's overflow callback. */
    public void setProducerOverflowListener(Consumer<Boolean> listener) {
        producerOverflowListener = listener;
    }

    /** Reports a producer queue overflow; true indicates discarded data. */
    public void reportProducerOverflow(boolean discarded) {
        Consumer<Boolean> listener = producerOverflowListener;
        if (listener != null) {
            listener.accept(discarded);
        }
    }

    public void recordFramesAccepted(int count) {
        if (count < 0) {
            throw new IllegalArgumentException("Accepted frame count cannot be negative");
        }
        framesAccepted.addAndGet(count);
    }

    public long getFramesAccepted() { return framesAccepted.get(); }

    /** Enqueues one shared immutable serialized message for every current client. */
    public void broadcast(byte[] messageBytes) {
        if (messageBytes == null || messageBytes.length == 0) {
            return;
        }
        synchronized (distributionLock) {
            if (stopped.get()) {
                return;
            }
            messagesReceived.incrementAndGet();
            for (ClientHandler handler : clients.values()) {
                handler.enqueueMessage(messageBytes);
            }
        }
        MessageListener listener = messageListener;
        if (listener != null && messageBytes.length >= 2) {
            listener.onMessage(messageBytes[1], messageBytes);
        }
    }

    public int getActiveConnections() { return (int) activeConnections.get(); }

    @Override
    public synchronized void start() throws Exception {
        if (state != ComponentState.CONFIGURED) {
            throw new IllegalStateException("Server is one-shot: current state = " + state);
        }
        state = ComponentState.STARTING;
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            if (settings.receiveBufferBytes() > 0) {
                serverSocket.setReceiveBufferSize(settings.receiveBufferBytes());
            }
            serverSocket.bind(new InetSocketAddress(bindAddress, settings.port()));
            clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
            running.set(true);
            stopped.set(false);
            state = ComponentState.RUNNING;
            acceptorThread = new Thread(this::acceptLoop, "jsatdecode-tcp-acceptor");
            acceptorThread.setDaemon(false);
            acceptorThread.start();
        } catch (Exception e) {
            failure = e;
            state = ComponentState.FAILED;
            stop();
            throw new IOException("Failed to start TCP server on port " + settings.port(), e);
        }
    }

    private void acceptLoop() {
        try {
            while (running.get()) {
                Socket socket = serverSocket.accept();
                try {
                    acceptClient(socket);
                } catch (Exception e) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // Failure to clean up one peer must not stop the listener.
                    }
                    if (running.get()) {
                        errors.incrementAndGet();
                    }
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                failure = e;
                state = ComponentState.FAILED;
                errors.incrementAndGet();
            }
        } catch (RuntimeException | Error e) {
            if (running.get()) {
                failure = e;
                state = ComponentState.FAILED;
                errors.incrementAndGet();
            }
        } finally {
            running.set(false);
        }
    }

    private void acceptClient(Socket socket) throws IOException {
        configureSocket(socket);
        synchronized (distributionLock) {
            if (!running.get() || activeConnections.get() >= settings.maxClients()) {
                socket.close();
                return;
            }
            long id = nextClientId.getAndIncrement();
            ClientHandler handler = new ClientHandler(this, id, socket,
                    settings.clientQueueBytes(), settings.maxMessageBytes(),
                    settings.overflowPolicy(), settings.sourceId());
            try {
                // The new client is invisible to broadcast until its initial snapshot
                // is enqueued, so no frame/event can overtake that snapshot.
                if (monitoringSupplier != null) {
                    handler.enqueueMessage(monitoringSupplier.get());
                }
                if (handler.isClosed()) {
                    return;
                }
                registerClient(handler);
                totalConnections.incrementAndGet();
                try {
                    clientExecutor.submit(handler::run);
                } catch (RejectedExecutionException e) {
                    handler.close();
                }
            } catch (RuntimeException | Error e) {
                // A failed supplier or task submission cannot leave a reserved slot.
                handler.close();
                throw e;
            }
        }
    }

    private void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(settings.tcpNoDelay());
        socket.setKeepAlive(settings.keepalive());
        if (settings.sendBufferBytes() > 0) {
            socket.setSendBufferSize(settings.sendBufferBytes());
        }
        if (settings.receiveBufferBytes() > 0) {
            socket.setReceiveBufferSize(settings.receiveBufferBytes());
        }
        socket.setSoTimeout(0);
    }

    @Override
    public synchronized void stop() {
        if (state == ComponentState.CLOSED || state == ComponentState.STOPPED) {
            return;
        }
        List<ClientHandler> drainingClients;
        synchronized (distributionLock) {
            running.set(false);
            stopped.set(true);
            drainingClients = new ArrayList<>(clients.values());
            for (ClientHandler handler : drainingClients) {
                handler.beginDrain();
            }
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
                // Closing the listener is best effort.
            }
        }
        long deadline = System.nanoTime() + SHUTDOWN_DRAIN_NANOS;
        try {
            // A shared deadline bounds the total wait, regardless of client count.
            for (ClientHandler handler : drainingClients) {
                handler.awaitDrained(deadline);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            for (ClientHandler handler : drainingClients) {
                handler.close();
            }
        }
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }
        if (acceptorThread != null && acceptorThread != Thread.currentThread()) {
            try {
                acceptorThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (state != ComponentState.FAILED) {
            state = ComponentState.STOPPED;
        }
    }

    @Override
    public synchronized void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public ComponentState state() { return state; }

    void registerClient(ClientHandler handler) {
        synchronized (distributionLock) {
            if (clients.putIfAbsent(handler.getClientId(), handler) == null) {
                activeConnections.incrementAndGet();
            }
        }
    }

    void unregisterClient(ClientHandler handler) {
        synchronized (distributionLock) {
            if (clients.remove(handler.getClientId(), handler)) {
                activeConnections.decrementAndGet();
            }
        }
    }

    void recordMessageSent(int count) {
        messagesSent.incrementAndGet();
        bytesSent.addAndGet(count);
    }

    void recordError() { errors.incrementAndGet(); }

    public DistributionSnapshot monitoringSnapshot() {
        return new DistributionSnapshot(isRunning(), serverSocket != null && !serverSocket.isClosed(),
                messagesReceived.get(), messagesSent.get(), 0, errors.get(),
                getActiveConnections(), settings.maxClients(), System.nanoTime());
    }

    public long getTotalConnections() { return totalConnections.get(); }
    public long getMessagesSent() { return messagesSent.get(); }
    public long getBytesSent() { return bytesSent.get(); }
    public long getErrors() { return errors.get(); }
    public Throwable getFailure() { return failure; }
    public boolean isRunning() { return running.get() && !stopped.get(); }

    @FunctionalInterface
    public interface MessageListener {
        void onMessage(byte messageType, byte[] messageBytes);
    }
}
