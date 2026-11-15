package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageDecoder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Handles I/O for a single TCP client connection.
 *
 * <p>Each client connection has its own handler that:
 * <ul>
 *   <li>Reads incoming data (though v1 doesn't require client-to-server messages)</li>
 *   <li>Maintains an outbound message queue with byte limit enforcement</li>
 *   <li>Sends messages to the client with backpressure handling</li>
 * </ul>
 *
 * <p>The handler implements the slow client policy by disconnecting clients that
 * exceed the configured queue byte limit.
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. Messages can be enqueued from any thread.
 * The run() method should only be called once from a single thread.
 */
public final class ClientHandler {

    private final TcpServer server;
    private final long clientId;
    private final Socket socket;
    private final OutputStream outputStream;
    private final int maxQueueBytes;

    private final ConcurrentLinkedQueue<byte[]> messageQueue = new ConcurrentLinkedQueue<>();
    private final AtomicLong queueBytes = new AtomicLong(0);
    private final AtomicBoolean closed = new AtomicBoolean(false);

    // Statistics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong bytesSent = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private volatile long connectTimeNanos;
    private volatile long lastActivityNanos;

    // Decoder for incoming messages (though v1 server doesn't expect any)
    private final TcpMessageDecoder decoder;

    /**
     * Creates a new client handler.
     *
     * @param server         The parent server
     * @param clientId       The unique client identifier
     * @param socket         The client socket
     * @param maxQueueBytes  The maximum queue size in bytes
     * @param maxMessageBytes The maximum message size
     * @throws IOException if the output stream cannot be obtained
     */
    public ClientHandler(
            TcpServer server, long clientId, Socket socket,
            int maxQueueBytes, int maxMessageBytes) throws IOException {
        this.server = server;
        this.clientId = clientId;
        this.socket = socket;
        this.outputStream = socket.getOutputStream();
        this.maxQueueBytes = maxQueueBytes;
        this.connectTimeNanos = System.nanoTime();
        this.lastActivityNanos = connectTimeNanos;
        this.decoder = new TcpMessageDecoder(maxMessageBytes);
    }

    /**
     * Gets the client identifier.
     *
     * @return The client identifier
     */
    public long getClientId() {
        return clientId;
    }

    /**
     * Gets the client socket.
     *
     * @return The client socket
     */
    public Socket getSocket() {
        return socket;
    }

    /**
     * Returns the remote address.
     *
     * @return The remote address as a string
     */
    public String getRemoteAddress() {
        if (socket != null) {
            return socket.getRemoteSocketAddress().toString();
        }
        return "unknown";
    }

    /**
     * Checks if the handler is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Enqueues a message for transmission.
     *
     * <p>If the queue byte limit would be exceeded, the message is dropped and
     * the client is disconnected according to the slow client policy.
     *
     * @param message The message bytes to send
     */
    public void enqueueMessage(byte[] message) {
        if (message == null || message.length == 0 || closed.get()) {
            return;
        }

        // Check if adding this message would exceed the queue limit
        long newQueueBytes = queueBytes.addAndGet(message.length);

        if (newQueueBytes > maxQueueBytes) {
            // Exceeded queue limit - rollback and disconnect
            queueBytes.addAndGet(-message.length);
            close();
            return;
        }

        // Add to queue
        messageQueue.offer(message);
        lastActivityNanos = System.nanoTime();
    }

    /**
     * Runs the client handler's I/O loop.
     */
    public void run() {
        try {
            // Set a read timeout on the socket to avoid blocking forever
            socket.setSoTimeout(100);

            // Main loop
            while (!closed.get()) {
                try {
                    // Try to send queued messages first
                    boolean sentSomething = sendMessages();

                    if (sentSomething) {
                        continue;
                    }

                    // No messages to send, check for incoming data
                    // Note: v1 protocol doesn't define any client-to-server messages,
                    // but we read to keep the connection alive and detect disconnections
                    if (readIncomingData()) {
                        lastActivityNanos = System.nanoTime();
                    } else {
                        // No activity, check if connection is still alive
                        if (socket != null && !socket.isClosed() && socket.isConnected()) {
                            // Connection is idle, wait a bit
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            // Connection closed by client
                            break;
                        }
                    }

                } catch (java.net.SocketTimeoutException e) {
                    // Read timeout - no data available, this is normal
                    // Do nothing, just continue the loop
                } catch (IOException e) {
                    // Connection error
                    errors.incrementAndGet();
                    server.recordError();
                    close();
                    break;
                } catch (Exception e) {
                    // Unexpected error
                    errors.incrementAndGet();
                    server.recordError();
                    close();
                    break;
                }
            }

        } catch (Exception e) {
            errors.incrementAndGet();
            server.recordError();
        } finally {
            close();
        }
    }

    /**
     * Attempts to send queued messages.
     *
     * @return true if at least one message was sent
     * @throws IOException if an I/O error occurs
     */
    private boolean sendMessages() throws IOException {
        boolean sentSomething = false;

        while (!closed.get() && !messageQueue.isEmpty()) {
            byte[] message = messageQueue.peek();
            if (message == null) {
                break;
            }

            try {
                outputStream.write(message);
                outputStream.flush();

                // Message sent successfully
                messageQueue.poll();
                queueBytes.addAndGet(-message.length);
                messagesSent.incrementAndGet();
                server.recordMessageSent(message.length);
                bytesSent.addAndGet(message.length);
                sentSomething = true;
                lastActivityNanos = System.nanoTime();

            } catch (IOException e) {
                // Write failed
                throw e;
            }
        }

        return sentSomething;
    }

    /**
     * Reads incoming data from the client.
     *
     * <p>Note: v1 protocol doesn't define any client-to-server messages,
     * but we read to keep the connection alive and detect disconnections.
     *
     * @return true if data was read, false if connection closed
     * @throws IOException if an I/O error occurs
     */
    private boolean readIncomingData() throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead = socket.getInputStream().read(buffer);

        if (bytesRead == -1) {
            // EOF - client disconnected
            return false;
        }

        if (bytesRead > 0) {
            // Feed to decoder (though we don't expect any valid messages)
            decoder.feed(buffer, 0, bytesRead);
            return true;
        }

        return false;
    }

    /**
     * Closes the client handler and cleans up resources.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
                if (socket != null && !socket.isClosed()) {
                    try {
                        socket.close();
                    } catch (IOException e) {
                        // Ignore
                    }
                }
            } finally {
                server.unregisterClient(this);
                messageQueue.clear();
            }
        }
    }

    /**
     * Gets the connect time in nanoseconds.
     *
     * @return The connect time
     */
    public long getConnectTimeNanos() {
        return connectTimeNanos;
    }

    /**
     * Gets the last activity time in nanoseconds.
     *
     * @return The last activity time
     */
    public long getLastActivityNanos() {
        return lastActivityNanos;
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
     * Gets the number of bytes sent.
     *
     * @return The byte count
     */
    public long getBytesSent() {
        return bytesSent.get();
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
     * Gets the current queue size in bytes.
     *
     * @return The queue size in bytes
     */
    public long getQueueBytes() {
        return queueBytes.get();
    }

    @Override
    public String toString() {
        return "ClientHandler[" +
                "id=" + clientId + ", " +
                "remote=" + getRemoteAddress() + ", " +
                "messages=" + messagesSent.get() + ", " +
                "bytes=" + bytesSent.get() + ", " +
                "closed=" + closed.get() +
                "]";
    }
}
