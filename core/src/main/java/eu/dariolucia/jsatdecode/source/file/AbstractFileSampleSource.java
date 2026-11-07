package eu.dariolucia.jsatdecode.source.file;

import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleException;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.core.monitoring.MetricsRegistry;
import eu.dariolucia.jsatdecode.core.monitoring.MonitoringStore;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Shared little-endian float32 stereo file engine. One owned worker delivers
 * immutable blocks serially to the functional sink; observers only pull metrics.
 * Lifecycle mutations belong to a serialized management caller. State/monitoring
 * reads and requestStop are thread-safe. Start is one-shot; restart is unsupported.
 * Stop/close interrupt pacing or file reads and join the worker; sink code must
 * cooperate with interruption. No channel is retained before start or after completion.
 */
public abstract class AbstractFileSampleSource implements SampleSource {
    private final Object lifecycle = new Object();
    private final SourceDescriptor descriptor;
    private final FileSourceSettings settings;
    private final long dataOffset, dataBytes, selectedEnd;
    private final double sampleRate;
    private final CountDownLatch completion = new CountDownLatch(1);
    private final MonitoringStore monitoring;
    private final MonitoringStore.LongMetric samples, blocks, cycles, position;
    private final MonitoringStore.BooleanMetric eof;
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile boolean stopRequested, started, finished;
    private long startUtcNanos;
    private volatile long startNanos, endNanos;
    private volatile Throwable failure;
    private SampleSink sink;
    private Thread worker;

    /**
     * Validates a contiguous sample region and builds fixed monitoring storage.
     * Subclasses must validate their file encoding before calling this constructor.
     *
     * @param descriptor non-null source identity
     * @param settings   non-null validated immutable file settings
     * @param dataOffset nonnegative payload byte offset
     * @param dataBytes  payload bytes, a nonnegative multiple of eight
     * @param sampleRate positive finite complex samples per second
     * @throws ConfigurationException for invalid or truncated payload/rate/range
     */
    protected AbstractFileSampleSource(SourceDescriptor descriptor, FileSourceSettings settings,
                                       long dataOffset, long dataBytes, double sampleRate) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (dataOffset < 0 || dataBytes < 0 || dataBytes % 8 != 0 || !Double.isFinite(sampleRate) || sampleRate <= 0) {
            throw new ConfigurationException("Invalid float32 IQ payload geometry or source.sample_rate");
        }
        try {
            if (Math.addExact(dataOffset, dataBytes) > Files.size(settings.file())) {
                throw new ConfigurationException("Truncated source payload");
            }
        } catch (IOException | ArithmeticException e) {
            throw new ConfigurationException("Invalid source file length", e);
        }
        this.dataOffset = dataOffset;
        this.dataBytes = dataBytes;
        this.sampleRate = sampleRate;
        selectedEnd = settings.endSample(dataBytes / 8);
        // Register only during setup; producer updates use cached primitive handles.
        MetricsRegistry registry = new MetricsRegistry(12);
        samples = registry.registerLong(metric("source.samples", MonitoringType.LONG, "samples", "Successfully delivered samples, including repeats"), 0);
        blocks = registry.registerLong(metric("source.blocks", MonitoringType.LONG, "blocks", "Successfully delivered blocks"), 0);
        cycles = registry.registerLong(metric("source.cycles", MonitoringType.LONG, "cycles", "Completed selected-segment passes"), 0);
        position = registry.registerLong(metric("source.position", MonitoringType.LONG, "samples", "Next recording sample index"), settings.startOffset());
        eof = registry.registerBoolean(metric("source.eof", MonitoringType.BOOLEAN, "", "Nonrepeating selected segment completed"), false);
        registry.registerLong(metric("source.total_samples", MonitoringType.LONG, "samples", "Recording payload length"), dataBytes / 8);
        registry.registerLong(metric("source.selected_samples", MonitoringType.LONG, "samples", "Selected segment length"), selectedEnd - settings.startOffset());
        registry.registerDouble(metric("source.sample_rate", MonitoringType.DOUBLE, "Hz", "Complex sample rate"), sampleRate);
        registry.registerDouble(metric("source.center_frequency", MonitoringType.DOUBLE, "Hz", "Configured center frequency metadata"), settings.centerFrequency());
        registry.registerDouble(metric("source.elapsed_s", MonitoringType.DOUBLE, "s", "Processing wall time, including pacing and sink work"), 0);
        registry.registerDouble(metric("source.realtime_factor", MonitoringType.DOUBLE, "", "Delivered signal seconds divided by wall seconds"), 0);
        monitoring = registry.build();
    }

    /**
     * Returns immutable implementation metadata.
     *
     * @return source descriptor
     */
    @Override
    public final SourceDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Returns the Java in-memory layout, independently of file encoding.
     *
     * @return interleaved complex float32
     */
    @Override
    public final SampleFormat sampleFormat() {
        return SampleFormat.COMPLEX_FLOAT32_INTERLEAVED;
    }

    /**
     * Installs a functional consumer while CONFIGURED, before any start/stop.
     *
     * @param sink non-null synchronous sample consumer; may retain immutable blocks
     * @throws IllegalStateException unless CONFIGURED
     */
    @Override
    public final void setSampleSink(SampleSink sink) {
        synchronized (lifecycle) {
            if (state != ComponentState.CONFIGURED) throw new IllegalStateException("Sink wiring requires CONFIGURED");
            this.sink = Objects.requireNonNull(sink, "sink");
        }
    }

    /**
     * Starts a one-shot worker with bounded reusable byte/float staging buffers.
     *
     * @throws LifecycleException unless CONFIGURED with a sink installed
     * @throws SourceException    if opening or starting fails; state becomes FAILED
     */
    @Override
    public final void start() {
        // Validate lifecycle ownership, then acquire resources before publishing RUNNING.
        synchronized (lifecycle) {
            if (state != ComponentState.CONFIGURED || sink == null)
                throw new LifecycleException("Start requires CONFIGURED and an installed sink");
            state = ComponentState.STARTING;
            FileChannel channel = null;
            try {
                channel = FileChannel.open(settings.file(), StandardOpenOption.READ);
                if (channel.size() < dataOffset + dataBytes)
                    throw new EOFException("Source was truncated before start");
                ByteBuffer bytes = ByteBuffer.allocate(settings.blockSamples() * 8).order(ByteOrder.LITTLE_ENDIAN);
                float[] iq = new float[settings.blockSamples() * 2];
                FileChannel owned = channel;
                worker = new Thread(() -> run(owned, bytes, iq), "jsatdecode-source-" + descriptor.id());
                startNanos = System.nanoTime();
                startUtcNanos = eu.dariolucia.jsatdecode.util.UtcTime.nowNanos();
                started = true;
                state = ComponentState.RUNNING;
                worker.start();
            } catch (IOException | RuntimeException | Error e) {
                if (channel != null) {
                    try {
                        channel.close();
                    } catch (IOException close) {
                        e.addSuppressed(close);
                    }
                }
                failure = e;
                endNanos = System.nanoTime();
                finished = true;
                state = ComponentState.FAILED;
                completion.countDown();
                throw new SourceException("Cannot start " + descriptor.id() + " source", e);
            }
        }
    }

    /**
     * Requests cancellation without waiting or locking; callable from a sink.
     * An active read/pacing wait checks this at its next boundary (at most 100 ms
     * of pacing). Use stop on the management thread to interrupt and join.
     */
    public final void requestStop() {
        stopRequested = true;
    }

    /**
     * Cancels and joins processing, preserving the caller's interrupt status.
     * Idempotent, including before start and after failure/EOF. FAILED is retained.
     *
     * @throws LifecycleException if called on the delivery worker; use requestStop there
     */
    @Override
    public final void stop() {
        Thread thread;
        synchronized (lifecycle) {
            if (Thread.currentThread() == worker) throw new LifecycleException("Use requestStop from a sample sink");
            stopRequested = true;
            if (state == ComponentState.CONFIGURED) {
                state = ComponentState.STOPPING;
                state = ComponentState.STOPPED;
                completion.countDown();
            } else if (state == ComponentState.RUNNING) state = ComponentState.STOPPING;
            thread = worker;
            if (thread != null && thread.isAlive()) thread.interrupt();
        }
        boolean interrupted = false;
        if (thread != null) {
            while (thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    /**
     * Stops, joins and enters terminal CLOSED state; safe to repeat.
     *
     * @throws LifecycleException if called on the delivery worker
     */
    @Override
    public final void close() {
        stop();
        synchronized (lifecycle) {
            state = ComponentState.CLOSED;
        }
    }

    /**
     * Returns safely published lifecycle state.
     *
     * @return current state
     */
    @Override
    public final ComponentState state() {
        return state;
    }

    /**
     * Waits for EOF, cancellation or failure with resources released.
     * Stop/close additionally join the worker; this wait can time out before start.
     *
     * @param timeout nonnegative finite timeout representable in nanoseconds
     * @return true if processing/resources have completed
     * @throws InterruptedException     if the waiting caller is interrupted
     * @throws IllegalArgumentException for negative or overflowing timeouts
     */
    public final boolean awaitCompletion(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative completion timeout");
        try {
            return completion.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Completion timeout too large", e);
        }
    }

    /**
     * Exposes startup/worker failure for management without callbacks or logging.
     *
     * @return recorded failure, empty on successful EOF/cancellation
     */
    public final Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Materializes metrics only on the observer caller; never holds a producer lock.
     *
     * @return immutable weakly consistent snapshot with live/frozen elapsed time
     */
    @Override
    public final MonitoringSnapshot monitoringSnapshot() {
        var values = new LinkedHashMap<>(monitoring.monitoringSnapshot().values());
        double elapsed = started ? Math.max(0, ((finished ? endNanos : System.nanoTime()) - startNanos) / 1e9) : 0;
        values.put("source.elapsed_s", elapsed);
        values.put("source.realtime_factor", elapsed > 0 ? (Long) values.get("source.samples") / sampleRate / elapsed : 0.0);
        return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(), values);
    }

    /**
     * Returns the fixed source metric schema.
     *
     * @return immutable descriptors
     */
    @Override
    public final Collection<MonitoringDescriptor> monitoringDescriptors() {
        return monitoring.monitoringDescriptors();
    }

    private void run(FileChannel channel, ByteBuffer bytes, float[] iq) {
        Throwable problem = null;
        try (channel) {
            deliver(channel, bytes, iq);
        } catch (InterruptedException e) {
            if (!stopRequested) problem = e;
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException | Error e) {
            if (!stopRequested || !(e instanceof AsynchronousCloseException)) problem = e;
        } finally {
            endNanos = System.nanoTime();
            finished = true;
            synchronized (lifecycle) {
                failure = problem;
                if (problem != null) state = ComponentState.FAILED;
                else {
                    state = ComponentState.STOPPING;
                    state = ComponentState.STOPPED;
                }
            }
            completion.countDown();
        }
    }

    private void deliver(FileChannel channel, ByteBuffer bytes, float[] iq) throws IOException, InterruptedException {
        long index = settings.startOffset();
        long delivered = 0;
        while (!stopRequested) {
            // Rewind only the selected segment; empty repeated ranges were rejected at setup.
            if (index == selectedEnd) {
                if (!settings.repeat()) {
                    eof.set(true);
                    return;
                }
                index = settings.startOffset();
                position.set(index);
            }
            int count = (int) Math.min(settings.blockSamples(), selectedEnd - index);
            long nextDelivered = Math.addExact(delivered, count);
            bytes.clear().limit(count * 8);
            readFully(channel, bytes, dataOffset + index * 8);
            bytes.flip();
            for (int n = 0; n < count * 2; n++) iq[n] = bytes.getFloat();
            if (settings.realtime()) pace(nextDelivered / sampleRate);
            if (stopRequested) return;
            // The immutable API block owns its sample copy; reuse staging only after delivery.
            long timestamp = Math.addExact(startUtcNanos, (long) (delivered / sampleRate * 1e9));
            sink.accept(new ComplexSampleBlock(iq, count, index, timestamp, sampleRate));
            delivered = nextDelivered;
            index += count;
            samples.set(delivered);
            blocks.addAndGet(1);
            position.set(index);
            if (index == selectedEnd) cycles.addAndGet(1);
        }
    }

    private void pace(double targetSeconds) throws InterruptedException {
        while (!stopRequested) {
            double remaining = targetSeconds - (System.nanoTime() - startNanos) / 1e9;
            if (remaining <= 0) return;
            TimeUnit.NANOSECONDS.sleep(Math.max(1, (long) Math.min(100_000_000, remaining * 1e9)));
        }
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset);
            if (read < 0) throw new EOFException("Source was truncated during processing");
            if (read == 0) throw new IOException("File read made no progress");
            offset += read;
        }
    }

    private static MonitoringDescriptor metric(String key, MonitoringType type, String unit, String description) {
        return new MonitoringDescriptor(key, type, unit, description);
    }
}
