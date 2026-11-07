package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleException;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringDescriptor;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringType;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.util.UtcTime;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Native Java network FMComms2/3/4 and PlutoPlus receiver. Uses TCP IIOD legacy
 * commands plus binary scan data, implemented with Java sockets and XML only;
 * no libiio/JNI/JNA/native library or helper process is loaded. See
 * docs/fmcomms2-source.md for protocol references, settings and qualification.
 *
 * <p>Decisions: one selected RF receiver matches the existing single-complex-stream
 * source API. RX1 is ADC voltage0/1; RX2 is voltage2/3. PRINT metadata determines
 * channel-mask numbers, byte order, shifts and signed widths. Digital samples
 * are normalized by 2^(bits-1), matching GNU Radio's /2048 for signed 12-bit ADC
 * data. The returned mask must match: other-client union masks are rejected,
 * never silently misinterpreted as IQ. Only Off/File/Keep FIR modes are supported;
 * Auto/Design are AD9361 filter-design algorithms, not IIOD protocol operations.
 * Hardware configuration is setup-only and remains on the device after shutdown.
 * Explicit fpga_decimation=2/4/8/16 requests the ADC-side sampling_frequency at
 * PHY rate divided by that factor; sample_rate remains the PHY rate. Advertised
 * available rates are checked when present, followed by exact ADC and PHY
 * readback before OPEN. Firmware without that capability fails startup: there
 * is no software decimation or fallback. Standard HDL may support only 1/8.
 * All delivered sample metadata, timestamps and durations use the output rate.
 *
 * <p>The management owner wires the sink and starts/configures hardware. One
 * worker owns subsequent READBUF and synchronous sample delivery; immutable
 * blocks may be retained by the sink. Reusable bounded byte/float staging has
 * no per-sample objects. Normal acquisition has no observation callback, FFT,
 * logging or UI work. Opt-in count-only diagnostics discard raw samples and
 * invoke a bounded counting callback per whole READBUF instead; no conversion,
 * recording or sink delivery occurs. Monitoring only reads cached fields. Decoding throughput
 * must keep up with live input; IIOD provides no hardware timestamp/loss guarantee.
 * No software throttle/reconnect hides overruns or discontinuities.
 *
 * <p>Stop closes the socket to cancel blocked I/O and joins the worker from a
 * management thread. A sample callback must use requestStop instead. Disconnect
 * releases daemon-side buffers, including when interrupted in a binary body;
 * sending CLOSE into an unfinished READBUF transaction would corrupt framing.
 * Timeouts/disconnects/protocol errors/sink failures enter FAILED, never normal
 * EOF. Original failure survives close. Lifecycle is one-shot and serialized by
 * the owner; concurrent stop may cancel startup. Polling never takes its lock.
 *
 * <p>Optional recording retains the existing immutable blocks in a bounded SPSC
 * queue. A separate worker writes little-endian CF32 with no filesystem work or
 * extra IQ copy on acquisition. STOP_RECORDING isolates recording failures;
 * FAIL_SESSION retains a source failure and cancels acquisition. Management
 * completion includes writer drain/close. See docs/iq-recording.md for settings,
 * filename UTC/identity, AGC gain semantics and playback.
 *
 */
public final class Fmcomms2SampleSource implements SampleSource {
    static final SourceDescriptor DESCRIPTOR = new SourceDescriptor("fmcomms2", "FMComms2 / PlutoPlus IIOD network", "1",
            "Pure Java TCP IIOD AD9361 complex IQ source");
    private final Object lifecycle = new Object();
    private final Fmcomms2Settings settings;
    private final CountDownLatch completion = new CountDownLatch(1);
    private volatile ComponentState state = ComponentState.CONFIGURED;
    private volatile IiodClient client;
    private volatile boolean stopRequested, connected;
    private volatile Throwable failure;
    private volatile long samples, blocks, overruns, lastTimestamp, startNanos, endNanos;
    private volatile double center;
    private final int rate;
    private volatile String device = "";
    private volatile Thread worker;
    private SampleSink sink;
    private final Clock recordingClock;
    private final Cf32Recorder.FileFactory recordingFiles;
    private final ReadDiagnostics readDiagnostics;
    private volatile Cf32Recorder recorder;
    private boolean acquisitionFinished, recordingFinished = true;

    /**
     * Validates unprefixed settings and an optional bounded FIR file without
     * contacting hardware or starting threads.
     *
     * @param configuration source section, including optional type=fmcomms2
     * @throws eu.dariolucia.jsatdecode.api.config.ConfigurationException for unsupported/invalid settings
     */
    public Fmcomms2SampleSource(Configuration configuration) {
        this(configuration, Clock.systemUTC(), Cf32Recorder.FILES, null);
    }

    /**
     * Validates settings and selects a count-only diagnostic run without network
     * access or starting threads. The supplied log belongs exclusively to this
     * one-shot source. Successful whole READBUF responses are logged and passed
     * to its counting listener on the acquisition worker, then discarded without
     * IQ conversion, immutable sample blocks, recording or sample-sink callbacks.
     * Configured recording is bypassed in this mode. A sink must still be wired
     * before start to preserve the source lifecycle contract, but is never called.
     *
     * <p>Start/stop/close belong on a management thread, never the Swing EDT.
     * The listener may use requestStop; management stop joins before log getters
     * may be inspected. The ordinary constructor retains normal sample delivery.
     *
     * @param configuration source section, including optional type=fmcomms2
     * @param diagnostics non-null preallocated log and bounded counting listener
     * @throws eu.dariolucia.jsatdecode.api.config.ConfigurationException for unsupported/invalid settings
     * @throws NullPointerException if diagnostics is null
     */
    public Fmcomms2SampleSource(Configuration configuration, ReadDiagnostics diagnostics) {
        this(configuration, Clock.systemUTC(), Cf32Recorder.FILES, Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    // Package-private dependencies allow deterministic startup and storage-failure tests.
    Fmcomms2SampleSource(Configuration configuration, Clock recordingClock, Cf32Recorder.FileFactory recordingFiles) {
        this(configuration, recordingClock, recordingFiles, null);
    }

    private Fmcomms2SampleSource(Configuration configuration, Clock recordingClock,
                               Cf32Recorder.FileFactory recordingFiles, ReadDiagnostics diagnostics) {
        settings = Fmcomms2Settings.read(configuration);
        rate = settings.sampleRate() / settings.decimation();
        center = settings.frequency();
        this.recordingClock = Objects.requireNonNull(recordingClock);
        this.recordingFiles = Objects.requireNonNull(recordingFiles);
        readDiagnostics = diagnostics;
    }

    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public SampleFormat sampleFormat() {
        return SampleFormat.COMPLEX_FLOAT32_INTERLEAVED;
    }

    @Override
    public void setSampleSink(SampleSink sink) {
        synchronized (lifecycle) {
            if (state != ComponentState.CONFIGURED) throw new LifecycleException("Sink wiring requires CONFIGURED");
            this.sink = Objects.requireNonNull(sink);
        }
    }

    @Override
    public void start() {
        IiodClient owned;
        synchronized (lifecycle) {
            if (state != ComponentState.CONFIGURED || sink == null)
                throw new LifecycleException("Start requires CONFIGURED with sample sink");
            // A test may be cancelled after publishing its source but before
            // management reaches start. Do not configure hardware in that case.
            if (stopRequested) {
                finishAcquisition();
                return;
            }
            state = ComponentState.STARTING;
            client = owned = new IiodClient();
            startNanos = System.nanoTime();
        }
        try {
            // requestStop deliberately does not take lifecycle. If it raced
            // with the client assignment above, it may have seen a null client;
            // observe its flag again before initiating socket/hardware work.
            if (stopRequested) {
                owned.close();
                finishAcquisition();
                return;
            }
            owned.connect(settings);
            zero(owned, "TIMEOUT " + settings.serverTimeout());
            IioContext context = IioContext.parse(owned.contextXml());
            IioContext.Device adc = context.device(settings.adc()), phy = context.device(settings.phy());
            IioContext.Layout layout = adc.layout(settings.receiver());
            configure(owned, phy);
            verifyAdcRate(owned, adc);
            if (owned.readLong(phy.rxAttribute(settings.receiver(), "sampling_frequency")) != settings.sampleRate())
                throw new IOException("FPGA rate configuration changed the PHY sample rate; expected " + settings.sampleRate() + " Hz");
            zero(owned, "SET " + adc.id() + " BUFFERS_COUNT " + settings.kernelBuffers());
            if (readDiagnostics == null && settings.recording().enabled() && !stopRequested) {
                Cf32Recorder recording = new Cf32Recorder(settings.recording(), (long) center, rate, settings.gain(),
                        recordingClock.instant(), this::recordingFailed, this::recordingCompleted);
                synchronized (lifecycle) {
                    recordingFinished = false;
                    recorder = recording;
                }
                recording.start(recordingFiles);
            }
            // Finish potentially slow file setup before enabling hardware buffers.
            zero(owned, "OPEN " + adc.id() + " " + settings.bufferSize() + " " + layout.mask());
            synchronized (lifecycle) {
                if (stopRequested) {
                    owned.close();
                    finishAcquisition();
                    return;
                }
                device = adc.id();
                connected = true;
                worker = new Thread(() -> run(owned, adc.id(), layout), "jsatdecode-source-fmcomms2");
                state = ComponentState.RUNNING;
                worker.start();
            }
        } catch (IOException | RuntimeException | Error e) {
            owned.close();
            connected = false;
            if (!stopRequested || !(e instanceof IOException)) recordFailure(e);
            finishAcquisition();
            if (failure != null)
                throw new SourceException("Cannot start FMComms2 source at " + settings.uri() + ": " + e.getMessage(), e);
        }
    }

    private void configure(IiodClient connection, IioContext.Device phy) throws IOException {
        int rx = settings.receiver();
        // Resolve every required attribute before any configuration write, and
        // always use XML-advertised names (firmware may expose shared attributes).
        String sampleRate = phy.rxAttribute(rx, "sampling_frequency"), bandwidth = phy.rxAttribute(rx, "rf_bandwidth"),
                port = phy.rxAttribute(rx, "rf_port_select"), gainMode = phy.rxAttribute(rx, "gain_control_mode"),
                gain = settings.gainMode().equals("manual") ? phy.rxAttribute(rx, "hardwaregain") : null,
                quadrature = phy.rxAttribute(rx, "quadrature_tracking_en"), rfDc = phy.rxAttribute(rx, "rf_dc_offset_tracking_en"),
                bbDc = phy.rxAttribute(rx, "bb_dc_offset_tracking_en"), lo = phy.channelAttribute("altvoltage0", true, "frequency"),
                fir = settings.filterSource().equals("Keep") ? null : phy.firEnable(),
                firConfig = settings.filterSource().equals("File") ? phy.attribute("filter_fir_config") : null;
        if (settings.filterSource().equals("File")) {
            // Move out of low-rate FIR operation before disabling/replacing FIR.
            connection.write(sampleRate, "3000000");
            connection.write(fir, "0");
            connection.write(firConfig, settings.filter());
            connection.write(fir, "1");
        }
        connection.write(sampleRate, Integer.toString(settings.sampleRate()));
        connection.write(bandwidth, Integer.toString(settings.bandwidth()));
        if (settings.filterSource().equals("Off")) connection.write(fir, "0");
        connection.write(port, settings.rfPort());
        connection.write(lo, Long.toString(settings.frequency()));
        connection.write(gainMode, settings.gainMode());
        if (gain != null) connection.write(gain, Double.toString(settings.gain()));
        connection.write(quadrature, settings.quadrature() ? "1" : "0");
        connection.write(rfDc, settings.rfDc() ? "1" : "0");
        connection.write(bbDc, settings.bbDc() ? "1" : "0");
        long actualRate = connection.readLong(sampleRate), actualCenter = connection.readLong(lo);
        if (actualRate != settings.sampleRate())
            throw new IOException("Hardware sample rate " + actualRate + " differs from requested " + settings.sampleRate());
        if (actualCenter < 70000000 || actualCenter > 6000000000L)
            throw new IOException("Invalid hardware LO frequency readback");
        center = actualCenter;
    }

    private static void zero(IiodClient connection, String command) throws IOException {
        if (connection.command(command) != 0) throw new IOException("Unexpected IIOD response to " + command);
    }

    private void verifyAdcRate(IiodClient connection, IioContext.Device adc) throws IOException {
        try {
            configureAdcRate(connection, adc);
        } catch (IOException e) {
            throw new IOException("Cannot configure native FPGA decimation x" + settings.decimation() +
                    " (PHY " + settings.sampleRate() + " Hz, output " + rate + " Hz): " + e.getMessage() +
                    "; hardware/firmware must expose and support the requested ADC sampling_frequency; no software fallback", e);
        }
    }

    private void configureAdcRate(IiodClient connection, IioContext.Device adc) throws IOException {
        String attribute;
        try {
            attribute = adc.rxAttribute(settings.receiver() * 2, "sampling_frequency");
        } catch (IOException notAdvertised) {
            if (settings.decimation() != 1)
                throw new IOException("ADC sampling_frequency attribute is missing", notAdvertised);
            return; // Plain HDL without a separate ADC clock retains the PHY rate.
        }
        String available = null;
        try {
            available = adc.rxAttribute(settings.receiver() * 2, "sampling_frequency_available");
        } catch (IOException absent) { /* Older firmware may expose only a writable rate; require exact readback. */ }
        if (available != null) {
            String values = connection.readText(available);
            boolean supported = false;
            for (String value : values.split("\\s+")) {
                long candidate;
                try {
                    candidate = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid ADC sampling_frequency_available: " + values, e);
                }
                if (candidate == rate) supported = true;
            }
            if (!supported)
                throw new IOException("Requested ADC rate " + rate + " Hz is unsupported; advertised rates: " + values);
        }
        // Program the firmware-exposed ADC clock, never emulate a missing factor.
        // Factor one resets a decimator left enabled by another client.
        if (connection.readLong(attribute) != rate) connection.write(attribute, Integer.toString(rate));
        long actual = connection.readLong(attribute);
        if (actual != rate)
            throw new IOException("ADC rate readback " + actual + " Hz differs from requested " + rate + " Hz");
    }

    private void run(IiodClient connection, String device, IioContext.Layout layout) {
        Cf32Recorder recording = recorder;
        ReadDiagnostics diagnostics = readDiagnostics;
        try {
            byte[] raw = new byte[Math.multiplyExact(settings.bufferSize(), 4)];
            float[] iq = diagnostics == null ? new float[Math.multiplyExact(settings.blockSamples(), 2)] : null;
            long anchor = 0, firstCompletionNanos = 0;
            boolean first = true;
            while (!stopRequested) {
                int bytes = connection.readBuffer(device, layout.mask(), raw);
                if (bytes % 4 != 0) throw new IOException("IIOD sample body ends inside a complex scan");
                if (diagnostics != null) {
                    long completionNanos = System.nanoTime();
                    if (first) {
                        firstCompletionNanos = completionNanos;
                        first = false;
                    }
                    diagnostics.record(UtcTime.nowNanos(), completionNanos - firstCompletionNanos, bytes / 4);
                    continue;
                }
                if (first) {
                    anchor = Math.subtractExact(UtcTime.nowNanos(), (long) (bytes / 4.0 / rate * 1e9));
                    first = false;
                }
                for (int offset = 0; offset < bytes && !stopRequested; ) {
                    int count = Math.min(settings.blockSamples(), (bytes - offset) / 4);
                    layout.convert(raw, offset, count, iq);
                    long timestamp = Math.addExact(anchor, (long) (samples / (double) rate * 1e9));
                    ComplexSampleBlock block = new ComplexSampleBlock(iq, count, samples, timestamp, rate);
                    if (recording != null) recording.offer(block);
                    sink.accept(block);
                    samples = Math.addExact(samples, count);
                    blocks = Math.incrementExact(blocks);
                    lastTimestamp = timestamp;
                    offset += count * 4;
                }
            }
        } catch (Throwable e) {
            if (!stopRequested || !(e instanceof IOException)) {
                recordFailure(e);
                if (e instanceof IiodClient.RemoteException remote && remote.errno == 75) overruns++;
            }
        } finally {
            connection.close();
            connected = false;
            finishAcquisition();
        }
    }

    private void recordFailure(Throwable e) {
        synchronized (lifecycle) {
            if (failure == null) failure = e;
            else if (failure != e) failure.addSuppressed(e);
            state = ComponentState.FAILED;
        }
    }

    private void recordingFailed(Throwable e) {
        recordFailure(new SourceException("CF32 recording failed: " + recorder.file, e));
        requestStop();
    }

    private void recordingCompleted() {
        synchronized (lifecycle) {
            recordingFinished = true;
            completeIfReady();
        }
    }

    private void finishAcquisition() {
        endNanos = System.nanoTime();
        Cf32Recorder recording = recorder;
        if (recording != null) recording.finish(failure == null);
        synchronized (lifecycle) {
            acquisitionFinished = true;
            if (failure == null) state = ComponentState.STOPPING;
            completeIfReady();
        }
    }

    // Only the last of acquisition and recording may publish successful completion.
    private void completeIfReady() {
        if (acquisitionFinished && recordingFinished) {
            state = failure == null ? ComponentState.STOPPED : ComponentState.FAILED;
            completion.countDown();
        }
    }

    /**
     * Cancels acquisition without joining. Safe from a sample callback or the
     * count-only diagnostic read listener; closes the socket so pending network
     * reads also terminate promptly. If called while CONFIGURED, the next start
     * finishes as STOPPED without connecting or configuring hardware.
     */
    public void requestStop() {
        stopRequested = true;
        IiodClient owned = client;
        if (owned != null) owned.close();
    }

    @Override
    public void stop() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Use requestStop from sample callback");
        synchronized (lifecycle) {
            if (state == ComponentState.CLOSED) return;
            if (state == ComponentState.CONFIGURED) {
                state = ComponentState.STOPPED;
                completion.countDown();
                return;
            }
            if (state == ComponentState.STARTING || state == ComponentState.RUNNING) state = ComponentState.STOPPING;
        }
        requestStop();
        boolean interrupted = false;
        while (true) try {
            completion.await();
            break;
        } catch (InterruptedException e) {
            interrupted = true;
        }
        Thread owned = worker;
        if (owned != null) while (owned.isAlive()) try {
            owned.join();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        Cf32Recorder recording = recorder;
        if (recording != null) recording.join();
        if (interrupted) Thread.currentThread().interrupt();
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
     * Waits for acquisition and recording finalization without driving runtime
     * supervision. Accepted recording blocks are drained before success is reported.
     *
     * @param timeout nonnegative wait budget
     * @return true if terminated; false if the budget expires
     * @throws InterruptedException if the caller is interrupted
     */
    public boolean awaitCompletion(Duration timeout) throws InterruptedException {
        if (timeout.isNegative()) throw new IllegalArgumentException("Negative timeout");
        return completion.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Returns the first retained startup/acquisition/sink or fatal recording
     * failure, including after close. STOP_RECORDING errors remain independent
     * in source.recording.* monitoring and do not fail acquisition.
     *
     * @return failure, or empty after intentional cancellation
     */
    public Optional<Throwable> failure() {
        return Optional.ofNullable(failure);
    }

    @Override
    public MonitoringSnapshot monitoringSnapshot() {
        long now = System.nanoTime();
        double elapsed = startNanos == 0 ? 0 : Math.max(0, ((endNanos == 0 ? now : endNanos) - startNanos) * 1e-9);
        Map<String, Object> values = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("source.active", isRunning() && !stopRequested), Map.entry("source.connected", connected),
                Map.entry("source.uri", settings.uri()), Map.entry("source.device", device), Map.entry("source.sample_rate", (double) rate),
                Map.entry("source.phy_sample_rate", (double) settings.sampleRate()), Map.entry("source.fpga_decimation", settings.decimation()),
                Map.entry("source.center_frequency", center), Map.entry("source.rx_channel", settings.receiver() + 1),
                Map.entry("source.samples", samples), Map.entry("source.blocks", blocks), Map.entry("source.elapsed_s", elapsed),
                Map.entry("source.realtime_factor", elapsed > 0 ? samples / (double) rate / elapsed : 0.0),
                Map.entry("source.reported_overruns", overruns), Map.entry("source.last_block_time_ns", lastTimestamp),
                Map.entry("source.errors", failure == null ? 0L : 1L), Map.entry("source.failure", failure == null ? "" : failure.toString()),
                Map.entry("source.timestamp_source", "host_anchored_sample_clock")));
        Cf32Recorder recording = recorder;
        RecordingSettings rs = settings.recording();
        values.put("source.recording.enabled", rs.enabled());
        values.put("source.recording.state", recording == null ? (rs.enabled() ? "CONFIGURED" : "DISABLED") : recording.state.name());
        values.put("source.recording.active", recording != null && recording.state == Cf32Recorder.State.RECORDING);
        values.put("source.recording.file", recording == null ? "" : recording.file.toString());
        values.put("source.recording.start_time_ns", recording == null ? 0L : recording.startTimeNanos);
        values.put("source.recording.samples_accepted", recording == null ? 0L : recording.acceptedSamples);
        long written = recording == null ? 0L : recording.writtenBytes;
        values.put("source.recording.samples_written", written / 8);
        values.put("source.recording.bytes_written", written);
        values.put("source.recording.queue_bytes", recording == null ? 0L : recording.queueBytes());
        values.put("source.recording.queue_capacity_bytes", rs.enabled() ? rs.slots() * rs.blockSamples() * 8L : 0L);
        values.put("source.recording.queue_slots", rs.enabled() ? rs.slots() : 0);
        values.put("source.recording.queue_high_water_bytes", recording == null ? 0L : recording.highWaterBytes);
        values.put("source.recording.errors", recording == null || recording.failure == null ? 0L : 1L);
        values.put("source.recording.failure", recording == null || recording.failure == null ? "" : recording.failure.toString());
        values.put("source.recording.complete", recording != null && recording.state == Cf32Recorder.State.COMPLETE);
        values.put("source.recording.rx_channel", settings.receiver() + 1);
        values.put("source.recording.configured_gain", settings.gain());
        values.put("source.recording.gain_mode", settings.gainMode());
        values.put("source.recording.failure_policy", rs.policy().name());
        return new MonitoringSnapshot(UtcTime.nowNanos(), values);
    }

    @Override
    public Collection<MonitoringDescriptor> monitoringDescriptors() {
        return DESCRIPTORS;
    }

    private static MonitoringDescriptor metric(String key, MonitoringType type, String unit, String description) {
        return new MonitoringDescriptor(key, type, unit, description);
    }

    private static final List<MonitoringDescriptor> DESCRIPTORS = List.of(
            metric("source.active", MonitoringType.BOOLEAN, "", "Actively receiving until intentional stop/failure"),
            metric("source.connected", MonitoringType.BOOLEAN, "", "Socket configured and open"),
            metric("source.uri", MonitoringType.STRING, "", "Configured network URI"),
            metric("source.device", MonitoringType.STRING, "", "Discovered stream device ID"),
            metric("source.sample_rate", MonitoringType.DOUBLE, "Hz", "Verified complex sample rate"),
            metric("source.phy_sample_rate", MonitoringType.DOUBLE, "Hz", "Requested and startup-verified PHY sample rate"),
            metric("source.fpga_decimation", MonitoringType.INTEGER, "", "Requested and startup-verified native FPGA decimation factor"),
            metric("source.center_frequency", MonitoringType.DOUBLE, "Hz", "Read-back receive LO frequency"),
            metric("source.rx_channel", MonitoringType.INTEGER, "", "Selected receiver, one-based"),
            metric("source.samples", MonitoringType.LONG, "samples", "Complex samples delivered successfully"),
            metric("source.blocks", MonitoringType.LONG, "blocks", "Immutable blocks delivered successfully"),
            metric("source.elapsed_s", MonitoringType.DOUBLE, "s", "Monotonic elapsed time including startup and sink work"),
            metric("source.realtime_factor", MonitoringType.DOUBLE, "", "Delivered signal seconds divided by elapsed wall time"),
            metric("source.reported_overruns", MonitoringType.LONG, "errors", "IIOD EOVERFLOW responses; not a count of hidden hardware FIFO losses"),
            metric("source.last_block_time_ns", MonitoringType.LONG, "ns", "Latest delivered block UTC epoch; zero before first delivery"),
            metric("source.errors", MonitoringType.LONG, "errors", "Retained terminal source failure count (0 or 1)"),
            metric("source.failure", MonitoringType.STRING, "", "First failure diagnostic, retained after close"),
            metric("source.timestamp_source", MonitoringType.STRING, "", "UTC sample-clock estimate; not hardware/capture timestamps"),
            metric("source.recording.enabled", MonitoringType.BOOLEAN, "", "Configured IQ recording enablement"),
            metric("source.recording.state", MonitoringType.STRING, "", "Recording lifecycle, retained independently of source state"),
            metric("source.recording.active", MonitoringType.BOOLEAN, "", "Accepting IQ blocks for recording"),
            metric("source.recording.file", MonitoringType.STRING, "", "CF32 destination; empty before recording setup"),
            metric("source.recording.start_time_ns", MonitoringType.LONG, "ns", "Recording start in UTC epoch nanoseconds"),
            metric("source.recording.samples_accepted", MonitoringType.LONG, "samples", "Complex samples accepted by the recorder"),
            metric("source.recording.samples_written", MonitoringType.LONG, "samples", "Complete complex samples written; no power-loss guarantee"),
            metric("source.recording.bytes_written", MonitoringType.LONG, "bytes", "Bytes written to the file channel"),
            metric("source.recording.queue_bytes", MonitoringType.LONG, "bytes", "Queued IQ payload, excluding serialization and in-flight block"),
            metric("source.recording.queue_capacity_bytes", MonitoringType.LONG, "bytes", "Maximum queued payload at configured block size"),
            metric("source.recording.queue_slots", MonitoringType.INTEGER, "blocks", "Bounded reference queue capacity"),
            metric("source.recording.queue_high_water_bytes", MonitoringType.LONG, "bytes", "Highest observed queued payload"),
            metric("source.recording.errors", MonitoringType.LONG, "errors", "Retained recording failure count (0 or 1)"),
            metric("source.recording.failure", MonitoringType.STRING, "", "Retained recording failure diagnostic"),
            metric("source.recording.complete", MonitoringType.BOOLEAN, "", "Finalized complete recording after intentional source stop"),
            metric("source.recording.rx_channel", MonitoringType.INTEGER, "", "Selected recorded receiver, one-based"),
            metric("source.recording.configured_gain", MonitoringType.DOUBLE, "dB", "Configured numeric gain; not actual varying AGC gain"),
            metric("source.recording.gain_mode", MonitoringType.STRING, "", "Selected receiver gain-control mode"),
            metric("source.recording.failure_policy", MonitoringType.STRING, "", "Recording-only or session failure policy"));
}
