package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;
import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageDecoder;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP server for JSatDecode distribution protocol (JTDP v1).
 *
 * <p>This server implements a multi-client TCP server that distributes messages
 * to connected clients. The server uses a thread pool for handling client connections
 * and maintains separate queues for each message type.
 *
 * <p>This implementation provides the base server functionality. Frame, monitoring,
 * and event distribution are handled by the specific distributor implementations
 * ({@link eu.dariolucia.jsatdecode.distribution.tcp.TcpFrameDistributor} and
 * {@link eu.dariolucia.jsatdecode.distribution.tcp.TcpMonitoringDistributor}).
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The accept thread and client handler threads operate
 * concurrently. All shared state is properly synchronized.
 *
 * @see ClientHandler
 */
public class TcpServer implements LifecycleComponent {

    private final TcpSettings settings;
    private final String bindAddress;
    private ServerSocket serverSocket;
    private Thread acceptorThread;
    private ExecutorService clientExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    // Client management
    private final Map<Long, ClientHandler> clients = new ConcurrentHashMap<>();
    private final AtomicLong nextClientId = new AtomicLong(1);

    // Statistics
    private final AtomicLong totalConnections = new AtomicLong(0);
    private final AtomicLong activeConnections = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile Throwable failure;

    // Message decoder (for potential incoming messages)
    private final TcpMessageDecoder decoder;

    // Message listeners for distribution
    private volatile MessageListener messageListener;

    /**
     * Creates a new TCP server with the specified settings.
     *
     * @param settings The TCP distribution settings
     * @param bindAddress The address to bind to (null or "0.0.0.0" for all interfaces)
     * @throws IllegalArgumentException if settings is null
     */
    public TcpServer(TcpSettings settings, String bindAddress) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        this.settings = settings;
        this.bindAddress = bindAddress != null ? bindAddress : "0.0.0.0";
        this.decoder = new TcpMessageDecoder(settings.maxMessageBytes());
    }

    /**
     * Creates a new TCP server with default bind address (all interfaces).
     *
     * @param settings The TCP distribution settings
     */
    public TcpServer(TcpSettings settings) {
        this(settings, "0.0.0.0");
    }

    /**
     * Gets the server settings.
     *
     * @return The immutable settings
     */
    public TcpSettings getSettings() {
        return settings;
    }

    /**
     * Gets the bind address.
     *
     * @return The bind address
     */
    public String getBindAddress() {
        return bindAddress;
    }

    /**
     * Gets the listening port.
     *
     * @return The port number
     */
    public int getPort() {
        return settings.port();
    }

    /**
     * Sets a message listener for outgoing messages.
     *
     * @param listener The message listener
     */
    public void setMessageListener(MessageListener listener) {
        this.messageListener = listener;
    }

    /**
     * Broadcasts a message to all connected clients.
     *
     * @param messageBytes The encoded message bytes
     */
    public void broadcast(byte[] messageBytes) {
        if (messageBytes == null || messageBytes.length == 0) {
            return;
        }

        messagesSent.incrementAndGet();
        bytesSent.addAndGet(messageBytes.length);

        for (ClientHandler handler : clients.values()) {
            handler.enqueueMessage(messageBytes);
        }
    }

    /**
     * Returns the number of active client connections.
     *
     * @return The number of active connections
     */
    public int getActiveConnections() {
        return (int) activeConnections.get();
    }

    // ---- Lifecycle ----

    @Override
    public void start() throws Exception {
        if (state != ComponentState.CONFIGURED) {
            throw new IllegalStateException("Server is one-shot: current state = " + state);
        }

        state = ComponentState.STARTING;

        try {
            // Create server socket
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);

            // Set socket options
            if (settings.receiveBufferBytes() > 0) {
                serverSocket.setReceiveBufferSize(settings.receiveBufferBytes());
            }

            // Bind
            InetSocketAddress address = new InetSocketAddress(bindAddress, settings.port());
            serverSocket.bind(address);

            // Create thread pool for client handlers
            int threadPoolSize = Math.max(1, settings.maxClients());
            clientExecutor = Executors.newFixedThreadPool(threadPoolSize, r -> {
                Thread t = new Thread(r, "jsatdecode-tcp-client-handler");
                t.setDaemon(true);
                return t;
            });

            // Start acceptor thread
            running.set(true);
            stopped.set(false);
            acceptorThread = new Thread(this::acceptLoop, "jsatdecode-tcp-acceptor");
            acceptorThread.setDaemon(false);
            acceptorThread.start();

            state = ComponentState.RUNNING;

        } catch (IOException e) {
            state = ComponentState.FAILED;
            failure = e;
            stop();
            throw new IOException("Failed to start TCP server on port " + settings.port(), e);
        }
    }

    /**
     * The acceptor loop that listens for new connections.
     */
    private void acceptLoop() {
        try {
            while (running.get() && !stopped.get()) {
                try {
                    // Accept new connection
                    Socket socket = serverSocket.accept();

                    // Check if we have capacity
                    if (activeConnections.get() >= settings.maxClients()) {
                        socket.close();
                        errors.incrementAndGet();
                        continue;
                    }

                    // Configure socket
                    configureSocket(socket);

                    // Create client handler
                    long clientId = nextClientId.getAndIncrement();
                    ClientHandler handler = new ClientHandler(
                            this, clientId, socket, settings.clientQueueBytes(), settings.maxMessageBytes()
                    );

                    // Add to clients map
                    clients.put(clientId, handler);
                    activeConnections.incrementAndGet();
                    totalConnections.incrementAndGet();

                    // Submit to executor
                    try {
                        clientExecutor.submit(handler::run);
                    } catch (RejectedExecutionException e) {
                        // Executor is shut down or full
                        handler.close();
                        clients.remove(clientId);
                        activeConnections.decrementAndGet();
                    }

                } catch (ClosedByInterruptException e) {
                    // Normal interruption during shutdown
                    Thread.currentThread().interrupt();
                    break;
                } catch (SocketException e) {
                    // Socket was closed
                    if (running.get()) {
                        errors.incrementAndGet();
                    }
                    break;
                } catch (IOException e) {
                    errors.incrementAndGet();
                    // Log error and continue
                }
            }
        } catch (Exception e) {
            if (running.get()) {
                failure = e;
                state = ComponentState.FAILED;
            }
        } finally {
            if (running.compareAndSet(true, false)) {
                state = ComponentState.STOPPED;
            }
        }
    }

    /**
     * Configures a newly accepted socket with the appropriate options.
     *
     * @param socket The socket to configure
     * @throws SocketException if configuration fails
     */
    private void configureSocket(Socket socket) throws SocketException {
        socket.setTcpNoDelay(settings.tcpNoDelay());
        socket.setKeepAlive(settings.keepalive());

        if (settings.sendBufferBytes() > 0) {
            socket.setSendBufferSize(settings.sendBufferBytes());
        }
        if (settings.receiveBufferBytes() > 0) {
            socket.setReceiveBufferSize(settings.receiveBufferBytes());
        }

        // Set timeout for read operations
        socket.setSoTimeout(5000); // 5 seconds
    }

    @Override
    public void stop() {
        if (state == ComponentState.CLOSED || state == ComponentState.STOPPED) {
            return;
        }

        // Signal acceptor to stop
        running.set(false);

        // Close server socket to unblock acceptor
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                // Ignore
            }
        }

        // Shutdown client executor
        if (clientExecutor != null) {
            clientExecutor.shutdownNow();
        }

        // Close all client handlers
        for (ClientHandler handler : clients.values()) {
            handler.close();
        }
        clients.clear();

        // Wait for acceptor thread to finish
        if (acceptorThread != null && acceptorThread.isAlive()) {
            try {
                acceptorThread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        if (state != ComponentState.FAILED) {
            state = ComponentState.STOPPED;
        }
        stopped.set(true);
    }

    @Override
    public void close() {
        stop();
        state = ComponentState.CLOSED;
    }

    @Override
    public ComponentState state() {
        return state;
    }

    /**
     * Registers a client handler.
     *
     * @param handler The client handler
     */
    void registerClient(ClientHandler handler) {
        clients.put(handler.getClientId(), handler);
        activeConnections.incrementAndGet();
    }

    /**
     * Unregisters a client handler.
     *
     * @param handler The client handler
     */
    void unregisterClient(ClientHandler handler) {
        clients.remove(handler.getClientId());
        activeConnections.decrementAndGet();
    }

    /**
     * Records that a message was sent.
     *
     * @param bytesSent The number of bytes sent
     */
    void recordMessageSent(int bytesSent) {
        messagesSent.incrementAndGet();
        this.bytesSent.addAndGet(bytesSent);
    }

    /**
     * Records an error.
     */
    void recordError() {
        errors.incrementAndGet();
    }

    /**
     * Returns the current distribution snapshot.
     *
     * @return An immutable snapshot of the current statistics
     */
    public DistributionSnapshot monitoringSnapshot() {
        return new DistributionSnapshot(
                isRunning(),
                serverSocket != null && !serverSocket.isClosed(),
                messagesSent.get(),
                messagesSent.get(),
                0, // messagesDropped
                errors.get(),
                (int) activeConnections.get(),
                settings.maxClients(),
                System.nanoTime()
        );
    }

    /**
     * Returns the total number of connections since start.
     *
     * @return The total connection count
     */
    public long getTotalConnections() {
        return totalConnections.get();
    }

    /**
     * Returns the total number of messages sent.
     *
     * @return The message count
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }

    /**
     * Returns the total number of bytes sent.
     *
     * @return The byte count
     */
    public long getBytesSent() {
        return bytesSent.get();
    }

    /**
     * Returns the total number of errors.
     *
     * @return The error count
     */
    public long getErrors() {
        return errors.get();
    }

    /**
     * Returns the first failure, if any.
     *
     * @return The first failure, or null
     */
    public Throwable getFailure() {
        return failure;
    }

    /**
     * Returns whether the server is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get() && !stopped.get();
    }

    /**
     * Message listener interface for outgoing messages.
     */
    @FunctionalInterface
    public interface MessageListener {
        /**
         * Called when a message is about to be sent.
         *
         * @param messageType The message type (0=frame, 1=monitoring, 2=event)
         * @param messageBytes The encoded message bytes
         */
        void onMessage(byte messageType, byte[] messageBytes);
    }
}
