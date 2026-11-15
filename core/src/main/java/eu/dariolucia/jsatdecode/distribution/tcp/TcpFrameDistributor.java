package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.FrameRecord;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpConstants;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TCP-based frame distributor implementation for JTDP v1.
 *
 * <p>This class implements the {@link FrameDistributor} interface, providing
 * a TCP server that distributes frames to connected clients using the JTDP v1
 * binary protocol. The distributor batches frames according to the configured
 * parameters before sending them to clients.
 *
 * <h2>Batching Behavior</h2>
 * <p>Frames are accumulated in a buffer and automatically flushed when:
 * <ul>
 *   <li>The maximum number of frames ({@code max_frame_buffer}) is reached</li>
 *   <li>The maximum latency ({@code max_frame_latency_s}) since the first frame in the batch is exceeded</li>
 *   <li>The next frame would exceed the maximum message size</li>
 *   <li>Shutdown is requested</li>
 * </ul>
 *
 * <p>Each frame batch message contains one or more frame records. Each record includes:
 * <ul>
 *   <li>Quality byte (0=good, 1=bad, 2=unknown)</li>
 *   <li>Reception timestamp (nanoseconds since Unix epoch)</li>
 *   <li>Frame length (bytes)</li>
 *   <li>Raw frame bytes</li>
 * </ul>
 *
 * <h2>Thread Safety</h2>
 * <p>This class is thread-safe. The {@link #distribute(AnnotatedFrame)} method
 * can be called from any thread.
 *
 * <h2>Integration</h2>
 * <p>This distributor integrates with JSatDecode's distribution framework via
 * the {@link TcpFrameDistributorFactory} mechanism. It can be selected by setting
 * the configuration property {@code distribution.frames.type=tcp}.
 *
 * @see TcpFrameDistributorFactory
 * @see TcpServer
 * @see FrameDistributor
 */
public final class TcpFrameDistributor implements FrameDistributor {

    private final TcpSettings settings;
    private final TcpServer server;
    private final TcpMessageEncoder encoder;
    private final boolean serverOwned;

    // Frame batching state
    private final CopyOnWriteArrayList<AnnotatedFrame> frameBuffer = new CopyOnWriteArrayList<>();
    private volatile long firstFrameTimeNanos = 0;

    // Statistics
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong errorCounter = new AtomicLong(0);

    // State management
    private final AtomicReference<ComponentState> state = new AtomicReference<>(ComponentState.CONFIGURED);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();

    /**
     * Creates a new TCP frame distributor with the specified settings.
     * This constructor creates and owns its own TcpServer instance.
     *
     * @param settings The TCP distribution settings
     * @throws IllegalArgumentException if settings is null
     */
    public TcpFrameDistributor(TcpSettings settings) {
        this(settings, null);
    }

    /**
     * Creates a new TCP frame distributor with the specified settings and server.
     * If server is provided, it will be shared with this distributor. If null,
     * a new server will be created.
     *
     * @param settings The TCP distribution settings
     * @param server   The TCP server to use, or null to create a new one
     * @throws IllegalArgumentException if settings is null
     */
    public TcpFrameDistributor(TcpSettings settings, TcpServer server) {
        if (settings == null) {
            throw new IllegalArgumentException("Settings cannot be null");
        }
        this.settings = settings;
        this.serverOwned = (server == null);
        this.server = (server != null) ? server : new TcpServer(settings);
        this.encoder = new TcpMessageEncoder(settings.sourceId(), settings.maxMessageBytes());
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor(
                "tcp",
                "JSatDecode TCP Frame Distributor",
                "1",
                "Distributes frames to TCP clients using JTDP v1 protocol"
        );
    }

    @Override
    public void distribute(AnnotatedFrame frame) {
        ComponentState currentState = state.get();
        if (currentState != ComponentState.RUNNING) {
            if (currentState == ComponentState.FAILED) {
                throw new IllegalStateException("Distributor failed", failure.get());
            }
            throw new IllegalStateException("Distributor is not running: " + currentState);
        }

        if (frame == null) {
            throw new IllegalArgumentException("Frame cannot be null");
        }

        messagesReceived.incrementAndGet();

        // Add frame to buffer and check if we need to flush
        boolean needsFlush = false;

        synchronized (frameBuffer) {
            long now = System.nanoTime();

            // Check if frame would fit in message
            int estimatedSize = estimateBatchSizeWithFrame(frame);
            if (estimatedSize > settings.maxMessageBytes()) {
                // Frame is too large
                droppedFrames.incrementAndGet();
                throw new IllegalArgumentException(
                        "Frame too large: " + frame.data().length + " bytes exceeds max message size"
                );
            }

            // Add frame to buffer
            frameBuffer.add(frame);

            // Set first frame time if this is the first frame
            if (firstFrameTimeNanos == 0) {
                firstFrameTimeNanos = now;
            }

            // Check frame count trigger (after adding the frame)
            if (frameBuffer.size() >= settings.maxFrameBuffer()) {
                needsFlush = true;
            }

            // Check latency trigger
            if (firstFrameTimeNanos > 0) {
                long elapsedNanos = now - firstFrameTimeNanos;
                long elapsedMillis = elapsedNanos / 1_000_000;
                if (elapsedMillis >= settings.maxFrameLatencyMs()) {
                    needsFlush = true;
                }
            }

            // Flush if needed
            if (needsFlush) {
                flushFrameBuffer();
            }
        }
    }

    /**
     * Estimates the size of the current frame buffer plus an additional frame.
     *
     * @param additionalFrame The additional frame to include in the estimate
     * @return The estimated message size in bytes
     */
    private int estimateBatchSizeWithFrame(AnnotatedFrame additionalFrame) {
        int size = TcpConstants.HEADER_LENGTH;
        for (AnnotatedFrame f : frameBuffer) {
            // Each frame record: 1 byte quality + 8 bytes timestamp + 4 bytes length + frame data
            size += 1 + 8 + 4 + f.data().length;
        }
        // Add the additional frame
        size += 1 + 8 + 4 + additionalFrame.data().length;
        return size;
    }

    /**
     * Flushes the current frame buffer to all connected clients.
     */
    private void flushFrameBuffer() {
        List<AnnotatedFrame> framesToSend;

        synchronized (frameBuffer) {
            if (frameBuffer.isEmpty()) {
                return;
            }

            framesToSend = new ArrayList<>(frameBuffer);
            frameBuffer.clear();
            firstFrameTimeNanos = 0;
        }

        // Encode and send
        try {
            byte[] message = encoder.encodeFrameBatchFromAnnotated(framesToSend, TcpConstants.FRAME_QUALITY_GOOD);
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
                droppedFrames.get(),
                errorCounter.get(),
                frameBuffer.size(),
                settings.maxFrameBuffer(),
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

            state.set(ComponentState.RUNNING);

        } catch (Exception e) {
            state.set(ComponentState.FAILED);
            failure.set(e);
            stop();
            throw e;
        }
    }

    @Override
    public void stop() {
        if (state.get() == ComponentState.CLOSED || state.get() == ComponentState.STOPPED) {
            return;
        }

        // Flush any remaining frames
        flushFrameBuffer();

        // Stop the server only if we own it
        if (serverOwned) {
            try {
                server.stop();
            } catch (Exception e) {
                // Ignore
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
     * Gets the number of dropped frames.
     *
     * @return The dropped frame count
     */
    public long getDroppedFrames() {
        return droppedFrames.get();
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
        return "TcpFrameDistributor[" +
                "state=" + state.get() + ", " +
                "frames=" + frameBuffer.size() + ", " +
                "messagesReceived=" + messagesReceived.get() + ", " +
                "messagesSent=" + messagesSent.get() + "]";
    }
}
