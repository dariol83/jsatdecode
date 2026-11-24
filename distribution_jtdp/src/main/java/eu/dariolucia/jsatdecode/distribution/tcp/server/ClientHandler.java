package eu.dariolucia.jsatdecode.distribution.tcp.server;

import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * An independently queued connection. Reservations include the message currently
 * being written. No socket operation is performed by an enqueue caller.
 */
public final class ClientHandler {
    private final TcpServer server;
    private final long clientId;
    private final Socket socket;
    private final OutputStream outputStream;
    private final int maxQueueBytes;
    private final int maxMessageBytes;
    private final Object queueLock = new Object();
    private final ArrayDeque<byte[]> messageQueue = new ArrayDeque<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long queueBytes;
    private boolean draining;
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong bytesSent = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final long connectTimeNanos = System.nanoTime();
    private volatile long lastActivityNanos = connectTimeNanos;

    /**
     * The policy and source arguments are retained for source compatibility:
     * producer overflow policies never apply to network queues.
     */
    public ClientHandler(TcpServer server, long clientId, Socket socket,
                         int maxQueueBytes, int maxMessageBytes,
                         OverflowPolicy overflowPolicy, int sourceId) throws IOException {
        if (maxQueueBytes <= 0) {
            throw new IllegalArgumentException("Client queue bytes must be positive");
        }
        this.server = server;
        this.clientId = clientId;
        this.socket = socket;
        this.outputStream = socket.getOutputStream();
        this.maxQueueBytes = maxQueueBytes;
        this.maxMessageBytes = maxMessageBytes;
    }

    public long getClientId() { return clientId; }
    public Socket getSocket() { return socket; }
    public String getRemoteAddress() { return String.valueOf(socket.getRemoteSocketAddress()); }
    public boolean isClosed() { return closed.get(); }

    /** Enqueues one complete immutable message, or disconnects on byte overflow. */
    public void enqueueMessage(byte[] message) {
        if (message == null || message.length == 0) {
            return;
        }
        boolean overflow;
        synchronized (queueLock) {
            if (closed.get() || draining) {
                return;
            }
            overflow = message.length > maxQueueBytes - queueBytes;
            if (!overflow) {
                messageQueue.addLast(message);
                queueBytes += message.length;
                lastActivityNanos = System.nanoTime();
                queueLock.notifyAll();
            } else {
                // Close under the reservation lock: no competing enqueue can slip in.
                markClosed();
            }
        }
        if (overflow) {
            finishClose();
        }
    }

    /** Runs the writer; a separate reader detects EOF even while writes stall. */
    public void run() {
        Thread.ofVirtual().name("jsatdecode-tcp-reader-" + clientId).start(this::readLoop);
        try {
            while (!closed.get()) {
                byte[] message;
                synchronized (queueLock) {
                    while (messageQueue.isEmpty() && !closed.get() && !draining) {
                        queueLock.wait();
                    }
                    if (closed.get() || messageQueue.isEmpty()) {
                        break;
                    }
                    message = messageQueue.removeFirst();
                }
                // Keep the reservation until the entire write and flush finish.
                outputStream.write(message);
                outputStream.flush();
                synchronized (queueLock) {
                    if (!closed.get()) {
                        queueBytes -= message.length;
                    }
                    queueLock.notifyAll();
                }
                messagesSent.incrementAndGet();
                bytesSent.addAndGet(message.length);
                server.recordMessageSent(message.length);
                lastActivityNanos = System.nanoTime();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            recordFailure();
        } finally {
            close();
        }
    }

    private void readLoop() {
        try {
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[1024];
            // Avoid allocating a receive decoder for peers that never transmit.
            TcpMessageDecoder decoder = null;
            while (!closed.get()) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                if (count > 0) {
                    if (decoder == null) {
                        decoder = new TcpMessageDecoder(maxMessageBytes);
                    }
                    decoder.feed(buffer, 0, count);
                    while (decoder.poll() != null) {
                        // JTDP v1 has no client-to-server application stream.
                    }
                    lastActivityNanos = System.nanoTime();
                }
            }
        } catch (IOException | RuntimeException e) {
            recordFailure();
        } finally {
            close();
        }
    }

    private void recordFailure() {
        if (!closed.get()) {
            errors.incrementAndGet();
            server.recordError();
        }
    }

    /** Prevents new messages and finishes already accepted messages. */
    void beginDrain() {
        synchronized (queueLock) {
            draining = true;
            queueLock.notifyAll();
        }
    }

    /** Waits against the server's shared monotonic shutdown deadline. */
    void awaitDrained(long deadlineNanos) throws InterruptedException {
        synchronized (queueLock) {
            long remaining;
            while (queueBytes > 0 && !closed.get() &&
                    (remaining = deadlineNanos - System.nanoTime()) > 0) {
                queueLock.wait(remaining / 1_000_000, (int) (remaining % 1_000_000));
            }
        }
    }

    /** Detaches immediately; socket close runs away from producer threads. */
    public void close() {
        boolean changed;
        synchronized (queueLock) {
            changed = markClosed();
        }
        if (changed) {
            finishClose();
        }
    }

    private boolean markClosed() {
        if (!closed.compareAndSet(false, true)) {
            return false;
        }
        messageQueue.clear();
        queueBytes = 0;
        queueLock.notifyAll();
        return true;
    }

    private void finishClose() {
        server.unregisterClient(this);
        // OutputStream.close can acquire a blocked writer's lock. Closing the
        // socket wakes both I/O tasks without closing that stream separately.
        Thread.ofVirtual().name("jsatdecode-tcp-close-" + clientId).start(() -> {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best effort after the handler has already detached.
            }
        });
    }

    public long getConnectTimeNanos() { return connectTimeNanos; }
    public long getLastActivityNanos() { return lastActivityNanos; }
    public long getMessagesSent() { return messagesSent.get(); }
    public long getBytesSent() { return bytesSent.get(); }
    public long getErrors() { return errors.get(); }
    public long getQueueBytes() {
        synchronized (queueLock) { return queueBytes; }
    }

    @Override
    public String toString() {
        return "ClientHandler[id=" + clientId + ", remote=" + getRemoteAddress() +
                ", messages=" + messagesSent.get() + ", bytes=" + bytesSent.get() +
                ", closed=" + closed.get() + "]";
    }
}
