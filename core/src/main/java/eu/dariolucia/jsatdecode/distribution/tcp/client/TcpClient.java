package eu.dariolucia.jsatdecode.distribution.tcp.client;

import eu.dariolucia.jsatdecode.distribution.tcp.TcpSettings;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.EventMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.FrameRecord;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.MonitoringMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.ProtocolException;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * Reference TCP client for JSatDecode distribution protocol (JTDP v1).
 *
 * <p>This client connects to a JSatDecode TCP distribution server and consumes
 * frames, monitoring snapshots, and runtime events. It provides:
 * <ul>
 *   <li>Message parsing and validation</li>
 *   <li>Automatic reconnection with bounded exponential backoff</li>
 *   <li>Data gap detection and session reset marking</li>
 *   <li>Thread-safe message delivery to registered listeners</li>
 * </ul>
 *
 * <h2>Usage Example</h2>
 * <pre>{@code
 * TcpClient client = new TcpClient("localhost", 6001);
 * 
 * // Register listeners
 * client.setFrameListener(frames -> processFrames(frames));
 * client.setMonitoringListener(msg -> processMonitoring(msg));
 * client.setEventListener(event -> processEvent(event));
 * client.setErrorListener(e -> logError(e));
 * 
 * // Connect
 * client.connect();
 * 
 * // Later, disconnect
 * client.disconnect();
 * }</pre>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Listeners are called from the client's I/O thread.
 * The {@link #connect()}, {@link #disconnect()}, and {@link #close()} methods
 * can be called from any thread.
 */
public final class TcpClient {

    private final String host;
    private final int port;
    private final TcpSettings settings;

    // Connection state
    private volatile Socket socket;
    private volatile InputStream inputStream;
    private Thread ioThread;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Reconnection state
    private final AtomicLong reconnectAttempt = new AtomicLong(0);
    private final AtomicLong nextReconnectTime = new AtomicLong(0);
    private final long minReconnectIntervalMs;
    private final long maxReconnectIntervalMs;

    // Message decoder
    private final TcpMessageDecoder decoder;

    // Statistics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong bytesReceived = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final AtomicLong reconnects = new AtomicLong(0);
    private final AtomicLong dataGaps = new AtomicLong(0);

    // Message listeners
    private volatile Consumer<List<FrameRecord>> frameListener;
    private volatile Consumer<MonitoringMessage> monitoringListener;
    private volatile Consumer<EventMessage> eventListener;
    private volatile Consumer<Throwable> errorListener;
    private volatile Consumer<String> statusListener;

    // Session tracking
    private final AtomicLong sessionStartTime = new AtomicLong(0);
    private final AtomicReference<String> lastError = new AtomicReference<>();

    // Buffer for reading
    private final byte[] readBuffer = new byte[65536];

    /**
     * Creates a new TCP client with the specified host and port.
     *
     * @param host The server host
     * @param port The server port
     */
    public TcpClient(String host, int port) {
        this(host, port, new TcpSettings(
                port, false, 1, 0, 0, true,
                0, 0, 0, 1_048_576, 1_048_576, 0, 
                eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy.FAIL_RUNTIME
        ), 1000L, 30000L);
    }

    /**
     * Creates a new TCP client with the specified host, port, and settings.
     *
     * @param host The server host
     * @param port The server port
     * @param settings The TCP settings
     * @param minReconnectIntervalMs Minimum reconnect interval in milliseconds
     * @param maxReconnectIntervalMs Maximum reconnect interval in milliseconds
     */
    public TcpClient(String host, int port, TcpSettings settings,
                     long minReconnectIntervalMs, long maxReconnectIntervalMs) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("Host cannot be null or empty");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Invalid port: " + port);
        }
        if (minReconnectIntervalMs <= 0) {
            throw new IllegalArgumentException("Min reconnect interval must be positive");
        }
        if (maxReconnectIntervalMs < minReconnectIntervalMs) {
            throw new IllegalArgumentException("Max reconnect interval must be >= min");
        }

        this.host = host;
        this.port = port;
        this.settings = settings != null ? settings : createDefaultSettings(port);
        this.minReconnectIntervalMs = minReconnectIntervalMs;
        this.maxReconnectIntervalMs = maxReconnectIntervalMs;
        this.decoder = new TcpMessageDecoder(this.settings.maxMessageBytes());
    }

    /**
     * Creates default settings for the client.
     *
     * @param port The port to use
     * @return Default settings
     */
    private TcpSettings createDefaultSettings(int port) {
        return new TcpSettings(
                port,
                false, // keepalive
                1,     // maxClients (not used by client)
                0,     // sendBufferBytes (not used by client)
                0,     // receiveBufferBytes
                true,  // tcpNoDelay
                0,     // monitoringIntervalMs (not used by client)
                0,     // maxFrameLatencyMs (not used by client)
                0,     // maxFrameBuffer (not used by client)
                1_048_576, // maxMessageBytes
                1_048_576, // clientQueueBytes (not used by client)
                0,     // sourceId (not used by client)
                eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy.FAIL_RUNTIME
        );
    }

    // ---- Configuration ----

    /**
     * Gets the server host.
     *
     * @return The host
     */
    public String getHost() {
        return host;
    }

    /**
     * Gets the server port.
     *
     * @return The port
     */
    public int getPort() {
        return port;
    }

    /**
     * Gets the TCP settings.
     *
     * @return The settings
     */
    public TcpSettings getSettings() {
        return settings;
    }

    // ---- Listeners ----

    /**
     * Sets the frame listener.
     *
     * @param listener The listener to call when frame batches are received
     */
    public void setFrameListener(Consumer<List<FrameRecord>> listener) {
        this.frameListener = listener;
    }

    /**
     * Sets the monitoring listener.
     *
     * @param listener The listener to call when monitoring messages are received
     */
    public void setMonitoringListener(Consumer<MonitoringMessage> listener) {
        this.monitoringListener = listener;
    }

    /**
     * Sets the event listener.
     *
     * @param listener The listener to call when event messages are received
     */
    public void setEventListener(Consumer<EventMessage> listener) {
        this.eventListener = listener;
    }

    /**
     * Sets the error listener.
     *
     * @param listener The listener to call when errors occur
     */
    public void setErrorListener(Consumer<Throwable> listener) {
        this.errorListener = listener;
    }

    /**
     * Sets the status listener.
     *
     * @param listener The listener to call when connection status changes
     */
    public void setStatusListener(Consumer<String> listener) {
        this.statusListener = listener;
    }

    // ---- Connection Management ----

    /**
     * Connects to the server.
     *
     * @throws IOException if connection fails
     */
    public void connect() throws IOException {
        if (running.get()) {
            throw new IllegalStateException("Client is already running");
        }
        if (connected.get()) {
            throw new IllegalStateException("Client is already connected");
        }

        running.set(true);
        closed.set(false);

        // Connect to server
        connectInternal();

        // Start I/O thread
        ioThread = new Thread(this::ioLoop, "jsatdecode-tcp-client-io");
        ioThread.setDaemon(true);
        ioThread.start();
    }

    /**
     * Internal connection method.
     *
     * @throws IOException if connection fails
     */
    private void connectInternal() throws IOException {
        // Create and configure socket
        socket = new Socket();
        socket.setTcpNoDelay(settings.tcpNoDelay());
        socket.setKeepAlive(settings.keepalive());

        if (settings.receiveBufferBytes() > 0) {
            socket.setReceiveBufferSize(settings.receiveBufferBytes());
        }

        // Set timeout
        socket.setSoTimeout(10000); // 10 seconds

        // Connect
        InetSocketAddress address = new InetSocketAddress(host, port);
        try {
            socket.connect(address, 5000); // 5 second timeout
        } catch (ConnectException e) {
            throw new IOException("Failed to connect to " + address, e);
        }

        inputStream = socket.getInputStream();
        connected.set(true);
        reconnectAttempt.set(0);
        sessionStartTime.set(System.nanoTime());

        // Notify status listener
        notifyStatus("Connected to " + address);
    }

    /**
     * Disconnects from the server.
     */
    public void disconnect() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        // Close socket
        closeSocket();

        // Wait for I/O thread to finish
        if (ioThread != null && ioThread.isAlive()) {
            try {
                ioThread.join(5000); // 5 second timeout
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        connected.set(false);
        notifyStatus("Disconnected");
    }

    /**
     * Closes the client and releases all resources.
     */
    public void close() {
        disconnect();
        closed.set(true);
        notifyStatus("Closed");
    }

    /**
     * Checks if the client is connected.
     *
     * @return true if connected
     */
    public boolean isConnected() {
        return connected.get();
    }

    /**
     * Checks if the client is running.
     *
     * @return true if running
     */
    public boolean isRunning() {
        return running.get();
    }

    /**
     * Checks if the client is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Closes the socket and cleans up.
     */
    private void closeSocket() {
        if (inputStream != null) {
            try {
                inputStream.close();
            } catch (IOException e) {
                // Ignore
            }
            inputStream = null;
        }

        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                // Ignore
            }
            socket = null;
        }
    }

    // ---- I/O Loop ----

    /**
     * The I/O loop that reads messages from the server.
     */
    private void ioLoop() {
        try {
            while (running.get()) {
                try {
                    if (!connected.get()) {
                        // Try to reconnect
                        if (!attemptReconnect()) {
                            // Reconnect failed, wait before retrying
                            waitForReconnect();
                        }
                        continue;
                    }

                    // Read data from socket
                    int bytesRead = inputStream.read(readBuffer);

                    if (bytesRead == -1) {
                        // EOF - server closed connection
                        handleConnectionLost("Server closed connection");
                        continue;
                    }

                    if (bytesRead > 0) {
                        // Feed data to decoder
                        bytesReceived.addAndGet(bytesRead);
                        decoder.feed(readBuffer, 0, bytesRead);

                        // Process decoded messages
                        processMessages();
                    }

                } catch (SocketException e) {
                    // Socket error - connection lost
                    handleConnectionLost("Socket error: " + e.getMessage());
                } catch (IOException e) {
                    // I/O error
                    handleConnectionLost("I/O error: " + e.getMessage());
                } catch (ProtocolException e) {
                    // Protocol error
                    handleProtocolError(e);
                } catch (Exception e) {
                    // Unexpected error
                    handleError(e);
                }
            }
        } finally {
            closeSocket();
            connected.set(false);
        }
    }

    /**
     * Attempts to reconnect to the server.
     *
     * @return true if reconnection succeeded
     */
    private boolean attemptReconnect() {
        if (!running.get()) {
            return false;
        }

        reconnectAttempt.incrementAndGet();

        try {
            connectInternal();
            reconnects.incrementAndGet();
            notifyStatus("Reconnected after " + reconnectAttempt.get() + " attempt(s)");
            return true;
        } catch (IOException e) {
            lastError.set(e.getMessage());
            notifyError(e);
            return false;
        }
    }

    /**
     * Waits before attempting to reconnect.
     */
    private void waitForReconnect() {
        if (!running.get()) {
            return;
        }

        long interval = calculateReconnectInterval();
        nextReconnectTime.set(System.currentTimeMillis() + interval);

        notifyStatus("Waiting " + interval + "ms before reconnecting");

        long start = System.currentTimeMillis();
        while (running.get() && System.currentTimeMillis() - start < interval) {
            LockSupport.parkNanos(1_000_000); // 1ms
        }
    }

    /**
     * Calculates the reconnect interval using bounded exponential backoff.
     *
     * @return The interval in milliseconds
     */
    private long calculateReconnectInterval() {
        long attempt = reconnectAttempt.get();
        // Exponential backoff: min * 2^(attempt-1), capped at max
        long interval = minReconnectIntervalMs * (1L << Math.min(attempt - 1, 20));
        return Math.min(interval, maxReconnectIntervalMs);
    }

    /**
     * Handles connection loss.
     *
     * @param message The error message
     */
    private void handleConnectionLost(String message) {
        connected.set(false);
        closeSocket();
        lastError.set(message);
        errors.incrementAndGet();
        dataGaps.incrementAndGet();
        notifyError(new IOException(message));
        notifyStatus("Connection lost: " + message);
    }

    /**
     * Handles a protocol error.
     *
     * @param e The protocol exception
     */
    private void handleProtocolError(ProtocolException e) {
        errors.incrementAndGet();
        notifyError(e);
        notifyStatus("Protocol error: " + e.getMessage());
    }

    /**
     * Handles an unexpected error.
     *
     * @param e The exception
     */
    private void handleError(Exception e) {
        errors.incrementAndGet();
        notifyError(e);
    }

    /**
     * Processes all available decoded messages.
     */
    private void processMessages() {
        TcpMessage message;
        while ((message = decoder.poll()) != null) {
            messagesReceived.incrementAndGet();

            try {
                if (message.isFrameBatch()) {
                    handleFrameBatch(message);
                } else if (message.isMonitoring()) {
                    handleMonitoring(message);
                } else if (message.isEvent()) {
                    handleEvent(message);
                } else {
                    // Unknown message type - skip
                }
            } catch (Exception e) {
                handleError(e);
            }
        }
    }

    /**
     * Handles a frame batch message.
     *
     * @param message The message
     */
    private void handleFrameBatch(TcpMessage message) {
        List<FrameRecord> frames = message.frames();
        if (frames != null && !frames.isEmpty()) {
            if (frameListener != null) {
                try {
                    frameListener.accept(frames);
                } catch (Exception e) {
                    handleError(e);
                }
            }
        }
    }

    /**
     * Handles a monitoring message.
     *
     * @param message The message
     */
    private void handleMonitoring(TcpMessage message) {
        MonitoringMessage monitoring = message.monitoring();
        if (monitoring != null) {
            if (monitoringListener != null) {
                try {
                    monitoringListener.accept(monitoring);
                } catch (Exception e) {
                    handleError(e);
                }
            }
        }
    }

    /**
     * Handles an event message.
     *
     * @param message The message
     */
    private void handleEvent(TcpMessage message) {
        EventMessage event = message.event();
        if (event != null) {
            if (eventListener != null) {
                try {
                    eventListener.accept(event);
                } catch (Exception e) {
                    handleError(e);
                }
            }
        }
    }

    // ---- Notifications ----

    /**
     * Notifies the error listener.
     *
     * @param e The exception
     */
    private void notifyError(Throwable e) {
        if (errorListener != null) {
            try {
                errorListener.accept(e);
            } catch (Exception ex) {
                // Ignore listener errors
            }
        }
    }

    /**
     * Notifies the status listener.
     *
     * @param message The status message
     */
    private void notifyStatus(String message) {
        if (statusListener != null) {
            try {
                statusListener.accept(message);
            } catch (Exception e) {
                // Ignore listener errors
            }
        }
    }

    // ---- Statistics ----

    /**
     * Gets the number of messages received.
     *
     * @return The message count
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    /**
     * Gets the number of bytes received.
     *
     * @return The byte count
     */
    public long getBytesReceived() {
        return bytesReceived.get();
    }

    /**
     * Gets the number of errors.
     *
     * @return The error count
     */
    public long getErrors() {
        return errors.get();
    }

    /**
     * Gets the number of reconnects.
     *
     * @return The reconnect count
     */
    public long getReconnects() {
        return reconnects.get();
    }

    /**
     * Gets the number of data gaps detected.
     *
     * @return The data gap count
     */
    public long getDataGaps() {
        return dataGaps.get();
    }

    /**
     * Gets the current reconnect attempt number.
     *
     * @return The reconnect attempt number
     */
    public long getReconnectAttempt() {
        return reconnectAttempt.get();
    }

    /**
     * Gets the last error message.
     *
     * @return The last error message, or null
     */
    public String getLastError() {
        return lastError.get();
    }

    /**
     * Gets the session start time in nanoseconds.
     *
     * @return The session start time
     */
    public long getSessionStartTime() {
        return sessionStartTime.get();
    }

    @Override
    public String toString() {
        return "TcpClient[" +
                "host=" + host + ", " +
                "port=" + port + ", " +
                "connected=" + connected.get() + ", " +
                "running=" + running.get() + ", " +
                "messages=" + messagesReceived.get() + ", " +
                "errors=" + errors.get() + "]";
    }
}
