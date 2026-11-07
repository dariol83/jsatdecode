package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/* Inspector-only recording. One acquisition producer publishes immutable block references;
   only the writer touches the channel. Management stop gates any concurrent offer before
   draining. A finite queue cannot cover an indefinitely stalled disk: overflow explicitly
   fails this recording, preserving reception and the contiguous accepted prefix. */
final class IqRecorder implements AutoCloseable {
    @FunctionalInterface
    interface FileFactory {
        SeekableByteChannel open(Path path) throws IOException;
    }

    private static final System.Logger LOG = System.getLogger(IqRecorder.class.getName());
    private static final DateTimeFormatter UTC = DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss").withZone(ZoneOffset.UTC);
    private static final int ACCEPTING = 1, SUBMITTING = 2;
    private final Path file;
    private final RecordingFormat format;
    private final ComplexSampleBlock[] queue;
    private final ByteBuffer bytes;
    private final SeekableByteChannel channel;
    private final AtomicInteger gate = new AtomicInteger(ACCEPTING);
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final AtomicBoolean failureReported = new AtomicBoolean();
    private final Thread worker;
    private final long capacityBytes;
    private volatile long head, tail, consumedMemoryBytes, acceptedMemoryBytes, acceptedBytes, writtenBytes;
    private volatile boolean ended, complete;

    static IqRecorder start(Path folder, double centerHz, int sampleRate, int gain, int blockSamples) throws IOException {
        return start(folder, centerHz, sampleRate, gain, blockSamples, RecordingFormat.CF32);
    }

    static IqRecorder start(Path folder, double centerHz, int sampleRate, int gain, int blockSamples,
                            RecordingFormat format) throws IOException {
        int slots = Math.max(2, Math.min(65536, (64 * 1024 * 1024) / Math.multiplyExact(blockSamples, 8)));
        return new IqRecorder(folder, centerHz, sampleRate, gain, Instant.now(), format, slots,
                64 * 1024 * 1024L, 1024 * 1024,
                path -> FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
    }

    // Package-private injection keeps partial writes, bounded queues and disk faults testable.
    IqRecorder(Path folder, double centerHz, int sampleRate, int gain, Instant started,
               int slots, long capacityBytes, int writeBytes, FileFactory files) throws IOException {
        this(folder, centerHz, sampleRate, gain, started, RecordingFormat.CF32, slots, capacityBytes, writeBytes, files);
    }

    IqRecorder(Path folder, double centerHz, int sampleRate, int gain, Instant started, RecordingFormat format,
               int slots, long capacityBytes, int writeBytes, FileFactory files) throws IOException {
        this.format = Objects.requireNonNull(format);
        int sampleBytes = format.bytesPerComplexSample();
        if (!Double.isFinite(centerHz) || centerHz <= 0 || sampleRate <= 0 || slots < 1 || capacityBytes < 8 ||
                writeBytes < sampleBytes || writeBytes % sampleBytes != 0) throw new IllegalArgumentException("Invalid recorder configuration");
        String center = BigDecimal.valueOf(centerHz).stripTrailingZeros().toPlainString();
        file = Objects.requireNonNull(folder).resolve(center + "_" + sampleRate + "_" + gain + "_" + UTC.format(started) + format.extension());
        this.capacityBytes = capacityBytes;
        queue = new ComplexSampleBlock[slots];
        bytes = ByteBuffer.allocateDirect(writeBytes).order(ByteOrder.LITTLE_ENDIAN);
        Files.createDirectories(folder);
        channel = Objects.requireNonNull(files.open(file));
        worker = new Thread(this::run, "jsatinspector-" + format.name().toLowerCase(java.util.Locale.ROOT) + "-writer");
        try {
            worker.start();
        } catch (RuntimeException | Error e) {
            try { channel.close(); } catch (Throwable close) { e.addSuppressed(close); }
            throw e;
        }
    }

    Path file() { return file; }
    Throwable failure() { return failure.get(); }
    boolean recording() { return (gate.get() & ACCEPTING) != 0; }
    boolean complete() { return complete; }
    long acceptedBytes() { return acceptedBytes; }
    long writtenBytes() { return writtenBytes; }

    // Management may report overflow even while the writer is stuck in disk I/O.
    void pollDiagnostics() {
        Throwable diagnostic = failure.get();
        if (diagnostic != null && failureReported.compareAndSet(false, true))
            LOG.log(System.Logger.Level.ERROR, "Incomplete " + format + " recording: " + file, diagnostic);
    }

    // Single producer; no filesystem calls, logging, waiting, copying or monitor acquisition.
    void offer(ComplexSampleBlock block) {
        if (block.complexSamples() == 0 || !gate.compareAndSet(ACCEPTING, ACCEPTING | SUBMITTING)) return;
        try {
            long size = block.complexSamples() * 8L;
            long next = tail;
            // References retain float IQ in either output format; bound that memory,
            // rather than allowing CS16 to double the retained source-block storage.
            if (next - head >= queue.length || size > capacityBytes - (acceptedMemoryBytes - consumedMemoryBytes)) {
                fail(new IOException(format + " buffer overrun: recording stopped because its disk queue is full: " + file));
                return;
            }
            queue[(int) (next % queue.length)] = block;
            acceptedMemoryBytes += size;
            acceptedBytes += block.complexSamples() * (long) format.bytesPerComplexSample();
            tail = next + 1; // Release-publishes the immutable block and accounting.
            LockSupport.unpark(worker);
        } finally {
            int previous = gate.getAndUpdate(value -> value & ~SUBMITTING);
            if ((previous & ACCEPTING) == 0) ended = true;
            LockSupport.unpark(worker);
        }
    }

    private void fail(Throwable cause) {
        failure.compareAndSet(null, cause);
        int previous = gate.getAndUpdate(value -> value & ~ACCEPTING);
        if ((previous & SUBMITTING) == 0) ended = true;
        LockSupport.unpark(worker);
    }

    private void run() {
        try {
            while (!ended || head != tail) {
                long next = head;
                if (next == tail) {
                    LockSupport.parkNanos(1_000_000L);
                    continue;
                }
                int slot = (int) (next % queue.length);
                ComplexSampleBlock block = queue[slot];
                queue[slot] = null;
                consumedMemoryBytes += block.complexSamples() * 8L;
                head = next + 1; // Producer may reuse this slot after this release.
                for (int n = 0; n < block.complexSamples(); n++) {
                    if (bytes.remaining() < format.bytesPerComplexSample()) flush();
                    format.putSample(bytes, block.i(n), block.q(n));
                }
            }
            flush();
        } catch (Throwable e) {
            fail(e);
            // A failed partial write must leave complete interleaved IQ samples.
            try {
                long aligned = channel.position() / format.bytesPerComplexSample() * format.bytesPerComplexSample();
                channel.truncate(aligned);
                writtenBytes = aligned;
            } catch (Throwable repair) {
                e.addSuppressed(repair);
            }
        } finally {
            gate.getAndUpdate(value -> value & ~ACCEPTING);
            while ((gate.get() & SUBMITTING) != 0) LockSupport.parkNanos(100_000L);
            try { channel.close(); } catch (Throwable e) { failure.compareAndSet(null, e); }
            java.util.Arrays.fill(queue, null);
            complete = failure.get() == null;
            pollDiagnostics();
        }
    }

    private void flush() throws IOException {
        bytes.flip();
        long stalledSince = 0;
        while (bytes.hasRemaining()) {
            int count = channel.write(bytes);
            if (count < 0) throw new IOException(format + " output closed during write: " + file);
            if (count == 0) {
                long now = System.nanoTime();
                if (stalledSince == 0) stalledSince = now;
                if (now - stalledSince >= 1_000_000_000L || Thread.currentThread().isInterrupted())
                    throw new IOException(format + " disk write made no progress for one second: " + file);
                LockSupport.parkNanos(1_000_000L);
            } else {
                writtenBytes += count;
                stalledSince = 0;
            }
        }
        bytes.clear();
    }

    // Management only. New submissions are rejected; every already accepted block drains.
    @Override
    public void close() {
        if (Thread.currentThread() == worker) throw new IllegalStateException("Writer cannot join itself");
        gate.getAndUpdate(value -> value & ~ACCEPTING);
        while ((gate.get() & SUBMITTING) != 0) LockSupport.parkNanos(100_000L);
        ended = true;
        LockSupport.unpark(worker);
        boolean interrupted = false;
        while (worker.isAlive()) {
            try { worker.join(); } catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
