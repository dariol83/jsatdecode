package eu.dariolucia.jsatdecode.distribution.zmq.client;

import eu.dariolucia.jsatdecode.util.UtcTime;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reference subscriber for the core SatDecode v1 distribution interface.
 * Configure either or both endpoints, install callbacks, then call {@link #start()}.
 * No ZMQ types occur in the public API. Construction opens no resources.
 *
 * <pre>{@code
 * try (var client = ZmqClient.builder()
 *         .framesEndpoint("tcp://localhost:5555")
 *         .monitoringEndpoint("tcp://localhost:5556")
 *         .onFrame((payload, info) -> consume(payload, info))
 *         .onMonitoring(info -> observe(info))
 *         .build()) {
 *     client.start();
 *     // Keep the owning application alive while reception is wanted.
 * }
 * }</pre>
 *
 * One owned daemon worker serializes all callbacks and socket operations for both
 * channels. Callbacks must return promptly; slow callbacks delay reception,
 * availability observations and shutdown, and can cause SUB high-water-mark loss.
 * No application delivery queue or retained history is created. Runtime exceptions
 * from callbacks are reported to the error callback and reception continues.
 * Map/list containers are recursively unmodifiable; payloads and nested byte
 * arrays are owned by the receiving callback and are never reused by this client.
 * Integers are Long, floating values Double, and CBOR null is Java null.
 *
 * Start/close are management operations. A callback can call {@link #requestStop()},
 * but cannot call close (which joins the worker). The client is one-shot.
 * Missing services reconnect automatically. PUB/SUB offers no replay or delivery
 * acknowledgement; TCP connection alone does not establish application readiness.
 * See core/docs/zmq-distribution-interface.md for the complete wire contract.
 */
public final class ZmqClient implements AutoCloseable {
    /** Independently observed subscriptions; status and event messages share one channel. */
    public enum Channel {
        /** Raw frame payload and its complete metadata envelope. */
        FRAMES,
        /** Complete status and event envelopes, distinguished by message_type. */
        MONITORING
    }

    private final Builder settings;
    private final Object lifecycle = new Object();
    private final CountDownLatch ready = new CountDownLatch(1);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private volatile Thread worker;
    private volatile boolean running, stopRequested;
    private boolean started, closed;
    private volatile ClientStatus frameStatus, monitoringStatus;

    private ZmqClient(Builder settings) {
        this.settings = new Builder(settings);
    }

    /**
     * Creates an inert builder; endpoints have no default and at least one is required.
     * @return new builder, confined to its configuring thread
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Starts the worker and waits for local socket/subscription setup. TCP connects
     * asynchronously, including when no publisher exists. The same instance cannot
     * be restarted. No callback completion or first message is awaited.
     * @throws IllegalStateException if already started/stopped/closed, setup fails,
     *                              or the management thread is interrupted
     */
    public void start() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Client is already started");
        synchronized (lifecycle) {
            if (started || closed || stopRequested) throw new IllegalStateException("Client is one-shot");
            started = true;
            worker = new Thread(this::run, "jsatdecode-zmq-client");
            worker.setDaemon(true);
            worker.start();
            try {
                ready.await();
            } catch (InterruptedException e) {
                requestStop();
                joinWorker();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted starting client", e);
            }
            if (failure.get() != null) {
                joinWorker();
                throw new IllegalStateException("Cannot start ZMQ client", failure.get());
            }
        }
    }

    /**
     * Requests cooperative shutdown without waiting or touching sockets. Safe from
     * callbacks and other threads, including before start; a request is terminal.
     */
    public void requestStop() {
        stopRequested = true;
    }

    /**
     * Returns whether setup completed and shutdown has not been requested.
     * @return safely published worker state, independent of connection/data presence
     */
    public boolean isRunning() {
        return running && !stopRequested;
    }

    /**
     * Returns the first fatal worker/setup failure, retained after close. Malformed
     * messages and callback runtime exceptions are nonfatal and use onError instead.
     * @return failure diagnostic, or empty
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure.get());
    }

    /**
     * Returns the latest immutable observation, safe from any thread. Observations
     * appear after local setup and are retained after shutdown.
     * @param channel subscription to inspect
     * @return latest status, or empty before setup/for a disabled channel
     */
    public Optional<ClientStatus> status(Channel channel) {
        Objects.requireNonNull(channel, "channel");
        return Optional.ofNullable(channel == Channel.FRAMES ? frameStatus : monitoringStatus);
    }

    /**
     * Requests stop and joins resource cleanup. Idempotent, including before start,
     * and preserves management-thread interruption. Callbacks must eventually return.
     * @throws IllegalStateException if called from a reception/status/error callback
     */
    @Override
    public void close() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Use requestStop from callbacks");
        synchronized (lifecycle) {
            closed = true;
            requestStop();
            joinWorker();
        }
    }

    private void joinWorker() {
        boolean interrupted = false;
        if (worker != null) while (worker.isAlive()) {
            try {
                worker.join();
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private void run() {
        List<Subscription> subscriptions = new ArrayList<>(2);
        Channel opening = null;
        try (ZContext context = new ZContext(1); ZMQ.Poller poller = context.createPoller(4)) {
            context.setLinger(0);
            if (settings.framesEndpoint != null) {
                opening = Channel.FRAMES;
                subscriptions.add(open(context, poller, Channel.FRAMES, settings.framesEndpoint));
            }
            if (settings.monitoringEndpoint != null) {
                opening = Channel.MONITORING;
                subscriptions.add(open(context, poller, Channel.MONITORING, settings.monitoringEndpoint));
            }
            opening = null;
            running = true;
            ready.countDown();
            for (Subscription subscription : subscriptions) publish(subscription);
            while (!stopRequested) {
                if (poller.poll(25) < 0) throw new IllegalStateException("ZMQ polling failed");
                for (Subscription subscription : subscriptions) {
                    if (stopRequested) break;
                    if (poller.pollin(subscription.monitorIndex)) monitor(subscription);
                    if (poller.pollin(subscription.dataIndex)) receive(subscription);
                    tick(subscription);
                }
            }
        } catch (Throwable e) {
            failure.compareAndSet(null, e);
            if (opening != null) reportError(opening, e);
            else for (Subscription subscription : subscriptions) reportError(subscription.channel, e);
        } finally {
            running = false;
            ready.countDown();
            for (Subscription subscription : subscriptions) {
                subscription.connected = false;
                subscription.present = false;
                subscription.byteRate = subscription.messageRate = 0;
                publish(subscription);
            }
        }
    }

    private Subscription open(ZContext context, ZMQ.Poller poller, Channel channel, String endpoint) {
        ZMQ.Socket socket = context.createSocket(SocketType.SUB);
        socket.setLinger(0);
        socket.setRcvHWM(settings.hwm);
        socket.setMaxMsgSize(settings.maxBytes);
        if (endpoint.contains("[")) socket.setIPv6(true);
        if (channel == Channel.FRAMES) {
            require(socket.subscribe("satdecode.v1.frame"), "frame subscription");
        } else {
            require(socket.subscribe("satdecode.v1.monitor.status"), "status subscription");
            require(socket.subscribe("satdecode.v1.monitor.event"), "event subscription");
        }
        String monitorEndpoint = "inproc://jsatdecode-client-" + UUID.randomUUID();
        require(socket.monitor(monitorEndpoint, ZMQ.EVENT_CONNECTED | ZMQ.EVENT_DISCONNECTED
                | ZMQ.EVENT_CLOSED), "socket monitoring");
        ZMQ.Socket monitor = context.createSocket(SocketType.PAIR);
        monitor.setLinger(0);
        require(monitor.connect(monitorEndpoint), "monitor connection");
        require(socket.connect(endpoint), "subscription connection");
        return new Subscription(channel, endpoint, socket, monitor,
                poller.register(socket, ZMQ.Poller.POLLIN), poller.register(monitor, ZMQ.Poller.POLLIN));
    }

    private static void require(boolean success, String operation) {
        if (!success) throw new IllegalStateException("Cannot configure " + operation);
    }

    private void monitor(Subscription subscription) {
        // Cap work per poll so reconnect chatter cannot starve data or the other channel.
        for (int i = 0; i < 64 && !stopRequested; i++) {
            ZMQ.Event event = ZMQ.Event.recv(subscription.monitor, ZMQ.DONTWAIT);
            if (event == null) break;
            boolean connected = event.getEvent() == ZMQ.EVENT_CONNECTED;
            if (subscription.connected != connected) {
                subscription.connected = connected;
                publish(subscription);
            }
        }
    }

    private void receive(Subscription subscription) {
        // Drain invalid multipart messages with bounded work per poll, retaining
        // at most the permitted prefix. Other channels and deadlines still run.
        for (int i = 0; i < 64; i++) {
            if (stopRequested) return;
            byte[] part = subscription.socket.recv(ZMQ.DONTWAIT);
            if (part == null) throw new IllegalStateException("Incomplete ZMQ multipart receive");
            if (subscription.partBytes <= settings.maxBytes) subscription.partBytes += part.length;
            if (subscription.partBytes > settings.maxBytes || subscription.parts.size() == 3)
                subscription.oversize = true;
            if (!subscription.oversize) subscription.parts.add(part);
            if (!subscription.socket.hasReceiveMore()) {
                deliver(subscription);
                return;
            }
        }
    }

    private void deliver(Subscription subscription) {
        List<byte[]> parts = subscription.parts;
        long bytes = subscription.partBytes;
        boolean oversize = subscription.oversize;
        subscription.parts = new ArrayList<>(3);
        subscription.partBytes = 0;
        subscription.oversize = false;
        WireDecoder.Decoded decoded;
        try {
            if (oversize) throw new IllegalArgumentException("Multipart message exceeds byte/part limit");
            decoded = WireDecoder.decode(parts, settings.maxBytes);
            if (decoded.frame() != (subscription.channel == Channel.FRAMES))
                throw new IllegalArgumentException("Topic on wrong subscription");
        } catch (IllegalArgumentException e) {
            subscription.malformed++;
            reportError(subscription.channel, e);
            return;
        }
        subscription.messages++;
        subscription.bytes += bytes;
        subscription.lastReceive = System.nanoTime();
        subscription.lastReceiveUtc = UtcTime.nowNanos();
        if (!subscription.present) {
            subscription.present = true;
            publish(subscription);
        }
        try {
            if (decoded.frame()) settings.onFrame.accept(decoded.payload(), decoded.information());
            else settings.onMonitoring.accept(decoded.information());
        } catch (RuntimeException e) {
            reportError(subscription.channel, e);
        }
    }

    private void tick(Subscription subscription) {
        long now = System.nanoTime();
        boolean expired = subscription.present && now - subscription.lastReceive >= settings.availabilityNanos;
        if (expired) subscription.present = false;
        long elapsed = now - subscription.rateTime;
        boolean periodic = elapsed >= settings.statusNanos;
        if (periodic) {
            subscription.byteRate = (subscription.bytes - subscription.rateBytes) * 1_000_000_000.0 / elapsed;
            subscription.messageRate = (subscription.messages - subscription.rateMessages) * 1_000_000_000.0 / elapsed;
            subscription.rateBytes = subscription.bytes;
            subscription.rateMessages = subscription.messages;
            subscription.rateTime = now;
        }
        if (periodic || expired) publish(subscription);
    }

    private void publish(Subscription subscription) {
        var status = new ClientStatus(subscription.channel, subscription.endpoint, isRunning(),
                subscription.connected, subscription.present, subscription.byteRate, subscription.messageRate,
                subscription.messages, subscription.bytes, subscription.malformed, UtcTime.nowNanos(),
                subscription.lastReceiveUtc);
        if (subscription.channel == Channel.FRAMES) frameStatus = status;
        else monitoringStatus = status;
        try {
            settings.onStatus.accept(status);
        } catch (RuntimeException e) {
            reportError(subscription.channel, e);
        }
    }

    private void reportError(Channel channel, Throwable error) {
        try {
            settings.onError.accept(channel, error);
        } catch (Throwable ignored) {
            // Error reporting cannot recursively fail or obstruct resource cleanup.
        }
    }

    private static final class Subscription {
        final Channel channel;
        final String endpoint;
        final ZMQ.Socket socket, monitor;
        final int dataIndex, monitorIndex;
        boolean connected, present;
        long messages, bytes, malformed, lastReceive, lastReceiveUtc;
        List<byte[]> parts = new ArrayList<>(3);
        long partBytes;
        boolean oversize;
        long rateTime = System.nanoTime(), rateMessages, rateBytes;
        double byteRate, messageRate;

        Subscription(Channel channel, String endpoint, ZMQ.Socket socket, ZMQ.Socket monitor,
                     int dataIndex, int monitorIndex) {
            this.channel = channel;
            this.endpoint = endpoint;
            this.socket = socket;
            this.monitor = monitor;
            this.dataIndex = dataIndex;
            this.monitorIndex = monitorIndex;
        }
    }

    /**
     * Thread-confined client configuration. Build snapshots this builder; later
     * changes cannot affect a previously built client. Unspecified callbacks do nothing.
     */
    public static final class Builder {
        private String framesEndpoint, monitoringEndpoint;
        private long availabilityNanos = 2_000_000_000L, statusNanos = 250_000_000L;
        private int hwm = 4096, maxBytes = 16 * 1024 * 1024;
        private BiConsumer<byte[], Map<String, Object>> onFrame = (payload, info) -> {};
        private Consumer<Map<String, Object>> onMonitoring = info -> {};
        private Consumer<ClientStatus> onStatus = status -> {};
        private BiConsumer<Channel, Throwable> onError = (channel, error) -> {};

        private Builder() {}

        private Builder(Builder other) {
            framesEndpoint = other.framesEndpoint;
            monitoringEndpoint = other.monitoringEndpoint;
            availabilityNanos = other.availabilityNanos;
            statusNanos = other.statusNanos;
            hwm = other.hwm;
            maxBytes = other.maxBytes;
            onFrame = other.onFrame;
            onMonitoring = other.onMonitoring;
            onStatus = other.onStatus;
            onError = other.onError;
        }

        /**
         * Enables frames at a concrete TCP connect endpoint.
         * @param endpoint TCP address, e.g. tcp://localhost:5555; no bind wildcards
         * @return this builder
         * @throws IllegalArgumentException if the address is invalid
         */
        public Builder framesEndpoint(String endpoint) {
            framesEndpoint = endpoint(endpoint);
            return this;
        }

        /**
         * Enables status and events on a concrete TCP connect endpoint.
         * @param endpoint TCP address, e.g. tcp://localhost:5556; no bind wildcards
         * @return this builder
         * @throws IllegalArgumentException if the address is invalid
         */
        public Builder monitoringEndpoint(String endpoint) {
            monitoringEndpoint = endpoint(endpoint);
            return this;
        }

        /**
         * Sets the inactivity period after which dataPresent becomes false.
         * Expiry is evaluated on the worker, normally within 25 ms, and can be
         * delayed by callbacks. TCP connectivity is reported independently.
         * @param timeout positive duration, at most one day; default two seconds
         * @return this builder
         */
        public Builder availabilityTimeout(Duration timeout) {
            availabilityNanos = duration(timeout);
            return this;
        }

        /**
         * Sets the rate measurement and periodic status interval. Transitions can
         * produce additional statuses carrying the most recently measured rates.
         * @param interval positive duration, at most one day; default 250 ms
         * @return this builder
         */
        public Builder statusInterval(Duration interval) {
            statusNanos = duration(interval);
            return this;
        }

        /**
         * Sets the finite SUB receive queue limit per peer. Excess traffic can be
         * discarded by ZMQ without a detectable local drop count.
         * @param messages positive message limit; default 4096
         * @return this builder
         */
        public Builder receiveHighWaterMark(int messages) {
            if (messages <= 0) throw new IllegalArgumentException("Receive HWM must be positive");
            hwm = messages;
            return this;
        }

        /**
         * Bounds accepted multipart application bytes and each transport part.
         * A part above the limit may cause transport disconnection; oversized
         * complete messages are discarded and reported through onError. This
         * does not impose a hard byte budget on transport queues: HWM counts
         * messages, and ZMQ may buffer multipart data before application validation.
         * @param bytes positive limit; default 16777216
         * @return this builder
         */
        public Builder maxMessageBytes(int bytes) {
            if (bytes <= 0) throw new IllegalArgumentException("Message limit must be positive");
            maxBytes = bytes;
            return this;
        }

        /**
         * Sets the frame callback. Information is the complete envelope, including
         * the nested metadata map; use {@link FrameKey#key()} to access wire keys.
         * @param callback nonnull serial worker callback, owning the received arrays
         * @return this builder
         */
        public Builder onFrame(BiConsumer<byte[], Map<String, Object>> callback) {
            onFrame = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /**
         * Sets the status/event callback. The complete envelope contains
         * message_type and either metrics/metric_timestamps_ns or event/severity/details.
         * @param callback nonnull serial worker callback; use {@link MonitoringKey#key()}
         * @return this builder
         */
        public Builder onMonitoring(Consumer<Map<String, Object>> callback) {
            onMonitoring = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /**
         * Sets the initial, periodic, transition and terminal status callback.
         * @param callback nonnull serial worker callback for each enabled channel
         * @return this builder
         */
        public Builder onStatus(Consumer<ClientStatus> callback) {
            onStatus = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /**
         * Sets nonfatal malformed-message/callback and fatal transport diagnostics.
         * Connection refusal while a publisher is absent is handled by reconnect,
         * without an error callback. Exceptions from this callback are contained.
         * @param callback nonnull serial worker callback with channel and cause
         * @return this builder
         */
        public Builder onError(BiConsumer<Channel, Throwable> callback) {
            onError = Objects.requireNonNull(callback, "callback");
            return this;
        }

        /**
         * Creates a resource-free client from this configuration snapshot.
         * @return new one-shot client
         * @throws IllegalStateException if neither endpoint is configured
         */
        public ZmqClient build() {
            if (framesEndpoint == null && monitoringEndpoint == null)
                throw new IllegalStateException("Configure at least one endpoint");
            return new ZmqClient(this);
        }

        private static long duration(Duration value) {
            Objects.requireNonNull(value, "duration");
            if (value.isZero() || value.isNegative() || value.compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Duration must be positive and at most one day");
            return value.toNanos();
        }

        private static String endpoint(String value) {
            Objects.requireNonNull(value, "endpoint");
            if (!value.matches("tcp://(?:[^/\\s:*\\[\\]]+|\\[[0-9a-fA-F:]+\\]):[0-9]+"))
                throw new IllegalArgumentException("Expected a concrete TCP connect endpoint: " + value);
            try {
                int port = Integer.parseInt(value.substring(value.lastIndexOf(':') + 1));
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid TCP port: " + value, e);
            }
            return value;
        }
    }
}
