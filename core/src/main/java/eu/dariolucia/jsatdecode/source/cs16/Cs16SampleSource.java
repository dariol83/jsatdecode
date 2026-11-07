package eu.dariolucia.jsatdecode.source.cs16;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleException;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.core.monitoring.MetricsRegistry;
import eu.dariolucia.jsatdecode.core.monitoring.MonitoringStore;
import eu.dariolucia.jsatdecode.source.file.FileSourceSettings;

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
 * Deterministic interleaved little-endian signed 16-bit IQ recording source.
 * Samples are stored as signed 16-bit integers (I0,Q0,I1,Q1,...) and converted to
 * float32 in the range [-1.0, 1.0]. File format matches SatDump and SDR++ CS16 format:
 * headerless, little-endian, 4 bytes per complex sample.
 */
public final class Cs16SampleSource implements SampleSource {
    private static final float SCALE = 1.0f / 32768.0f;
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
     * @param configuration non-null source-specific settings
     * @throws ConfigurationException for invalid or truncated payload/rate/range
     */
    public Cs16SampleSource(Configuration configuration) {
        this(configuration, FileSourceSettings.read(configuration));
    }

    private Cs16SampleSource(Configuration configuration, FileSourceSettings settings) {
        this.descriptor = Cs16SourceFactory.DESCRIPTOR;
        this.settings = Objects.requireNonNull(settings, "settings");
        this.dataOffset = 0;
        this.dataBytes = validateAndGetSize(settings);
        this.sampleRate = validateRate(configuration);
        this.selectedEnd = settings.endSample(this.dataBytes / 4); // 4 bytes per complex sample
        
        // Register monitoring
        MetricsRegistry registry = new MetricsRegistry(12);
        samples = registry.registerLong(metric("source.samples", MonitoringType.LONG, "samples", "Successfully delivered samples, including repeats"), 0);
        blocks = registry.registerLong(metric("source.blocks", MonitoringType.LONG, "blocks", "Successfully delivered blocks"), 0);
        cycles = registry.registerLong(metric("source.cycles", MonitoringType.LONG, "cycles", "Completed selected-segment passes"), 0);
        position = registry.registerLong(metric("source.position", MonitoringType.LONG, "samples", "Next recording sample index"), settings.startOffset());
        eof = registry.registerBoolean(metric("source.eof", MonitoringType.BOOLEAN, "", "Nonrepeating selected segment completed"), false);
        registry.registerLong(metric("source.total_samples", MonitoringType.LONG, "samples", "Recording payload length"), this.dataBytes / 4);
        registry.registerLong(metric("source.selected_samples", MonitoringType.LONG, "samples", "Selected segment length"), selectedEnd - settings.startOffset());
        registry.registerDouble(metric("source.sample_rate", MonitoringType.DOUBLE, "Hz", "Complex sample rate"), sampleRate);
        registry.registerDouble(metric("source.center_frequency", MonitoringType.DOUBLE, "Hz", "Configured center frequency metadata"), settings.centerFrequency());
        registry.registerDouble(metric("source.elapsed_s", MonitoringType.DOUBLE, "s", "Processing wall time, including pacing and sink work"), 0);
        registry.registerDouble(metric("source.realtime_factor", MonitoringType.DOUBLE, "", "Delivered signal seconds divided by wall seconds"), 0);
        monitoring = registry.build();
    }

    private static long validateAndGetSize(FileSourceSettings settings) {
        try {
            long fileSize = Files.size(settings.file());
            if (fileSize % 4 != 0) {
                throw new ConfigurationException("CS16 file size must be a multiple of 4 bytes (2 bytes per component)");
            }
            return fileSize;
        } catch (IOException e) {
            throw new ConfigurationException("Cannot inspect CS16 source.file", e);
        }
    }

    private static double validateRate(Configuration configuration) {
        if (configuration.asMap().containsKey("type") && !configuration.getString("type").equals("cs16")) {
            throw new ConfigurationException("CS16 source.type must be cs16");
        }
        return configuration.getDouble("sample_rate");
    }

    /**
     * Returns immutable implementation metadata.
     *
     * @return source descriptor
     */
    @Override
    public SourceDescriptor descriptor() {
        return descriptor;
    }

    /**
     * Returns the Java in-memory layout.
     *
     * @return interleaved complex float32
     */
    @Override
    public SampleFormat sampleFormat() {
        return SampleFormat.COMPLEX_FLOAT32_INTERLEAVED;
    }

    /**
     * Installs a functional consumer while CONFIGURED, before any start/stop.
     *
     * @param sink non-null synchronous sample consumer; may retain immutable blocks
     * @throws IllegalStateException unless CONFIGURED
     */
    @Override
    public void setSampleSink(SampleSink sink) {
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
    public void start() {
        synchronized (lifecycle) {
            if (state != ComponentState.CONFIGURED || sink == null)
                throw new LifecycleException("Start requires CONFIGURED and an installed sink");
            state = ComponentState.STARTING;
            FileChannel channel = null;
            try {
                channel = FileChannel.open(settings.file(), StandardOpenOption.READ);
                if (channel.size() < dataOffset + dataBytes)
                    throw new EOFException("Source was truncated before start");
                // CS16: 4 bytes per complex sample (2 bytes I + 2 bytes Q)
                ByteBuffer bytes = ByteBuffer.allocate(settings.blockSamples() * 4).order(ByteOrder.LITTLE_ENDIAN);
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
    public void requestStop() {
        stopRequested = true;
    }

    /**
     * Cancels and joins processing, preserving the caller's interrupt status.
     * Idempotent, including before start and after failure/EOF.
     *
     * @throws LifecycleException if called on the delivery worker; use requestStop there
     */
    @Override
    public void stop() {
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
    public void close() {
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
    public ComponentState state() {
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
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
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
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    /**
     * Materializes metrics only on the observer caller; never holds a producer lock.
     *
     * @return immutable weakly consistent snapshot with live/frozen elapsed time
     */
    @Override
    public MonitoringSnapshot monitoringSnapshot() {
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
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
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
            // CS16: 4 bytes per complex sample
            bytes.clear().limit(count * 4);
            readFully(channel, bytes, dataOffset + index * 4);
            bytes.flip();
            // Convert signed 16-bit integers to float32 in [-1.0, 1.0] range
            for (int n = 0; n < count * 2; n++) {
                short sample = bytes.getShort();
                iq[n] = sample * SCALE;
            }
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
