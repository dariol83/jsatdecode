package eu.dariolucia.jsatdecode.source.fmcomms2;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/* One producer (acquisition), one consumer (disk). Lifecycle is owned by source management.
   Volatile tail publishes the slot and preceding sample accounting; volatile head publishes
   reclamation. Slots need no per-entry atomics, wrappers, allocation or producer-side locks.
   A submitting flag allows exceptional consumer cleanup to quiesce an in-flight offer. */
final class Cf32Recorder {
    enum State { STARTING, RECORDING, DRAINING, COMPLETE, INCOMPLETE, FAILED }
    @FunctionalInterface interface FileFactory { SeekableByteChannel open(Path file) throws IOException; }
    static final FileFactory FILES = path -> FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);

    final RecordingSettings settings;
    final Path file;
    final long startTimeNanos;
    private final Consumer<Throwable> fatal;
    private final Runnable completed;
    private final int wakeThreshold;
    private final CountDownLatch done = new CountDownLatch(1);
    private ComplexSampleBlock[] queue;
    private ByteBuffer buffer;
    private SeekableByteChannel channel;
    private Thread worker;
    private volatile long head, tail, consumedBytes;
    volatile long acceptedSamples, writtenBytes, highWaterBytes;
    private volatile boolean accepting, submitting, ended, parked;
    private boolean successful;
    volatile State state = State.STARTING;
    volatile Throwable failure;

    Cf32Recorder(RecordingSettings settings, long center, int rate, double gain, Instant started,
                 Consumer<Throwable> fatal, Runnable completed) {
        this.settings = settings;
        this.file = settings.file(center, rate, gain, started);
        this.startTimeNanos = Math.addExact(Math.multiplyExact(started.getEpochSecond(), 1_000_000_000L), started.getNano());
        this.fatal = fatal;
        this.completed = completed;
        this.wakeThreshold = Math.max(1, Math.min(32, settings.slots() / 2));
    }

    void start(FileFactory files) throws IOException {
        try {
            // Setup stays on management; neither allocation nor file creation can pace DSP.
            queue = new ComplexSampleBlock[settings.slots()];
            buffer = ByteBuffer.allocateDirect(settings.writeBytes()).order(ByteOrder.LITTLE_ENDIAN);
            Files.createDirectories(settings.folder());
            channel = files.open(file);
            worker = new Thread(this::run, "jsatdecode-cf32-writer");
            accepting = true;
            state = State.RECORDING;
            worker.start();
        } catch (IOException | RuntimeException | Error e) {
            accepting = false;
            remember(e);
            closeChannel();
            release();
            signalCompleted();
            throw e;
        }
    }

    void offer(ComplexSampleBlock block) {
        submitting = true;
        try {
            if (!accepting) return;
            if (block.complexSamples() == 0) return;
            long next = tail;
            if (block.complexSamples() > settings.blockSamples() || next - head == queue.length) {
                fail(new IOException("CF32 recording queue overflow: " + file));
                return;
            }
            queue[(int) (next % queue.length)] = block;
            acceptedSamples += block.complexSamples();
            tail = next + 1;
            highWaterBytes = Math.max(highWaterBytes, Math.max(0, acceptedSamples * 8 - consumedBytes));
            // Batch wakeups as well as disk writes. A timed consumer poll covers
            // sparse input; tiny queues still wake on their first published block.
            if (parked && next + 1 - head >= wakeThreshold) LockSupport.unpark(worker);
        } finally {
            submitting = false;
        }
    }

    // Called only once the producer is quiescent; callbacks never join a writer.
    void finish(boolean successful) {
        this.successful = successful;
        accepting = false;
        if (failure == null) state = State.DRAINING;
        ended = true;
        LockSupport.unpark(worker);
    }

    private void run() {
        try {
            while (!ended || head != tail) {
                long next = head;
                if (next == tail) {
                    parked = true;
                    if (!ended && head == tail) LockSupport.parkNanos(1_000_000L);
                    parked = false;
                    continue;
                }
                int slot = (int) (next % queue.length);
                ComplexSampleBlock block = queue[slot];
                queue[slot] = null;
                consumedBytes += block.complexSamples() * 8L;
                head = next + 1;
                serialize(block);
            }
            flush();
        } catch (Throwable e) {
            fail(e);
            // Never resume after a failed write: only a contiguous prefix is salvageable.
            try {
                long aligned = channel.position() & ~7L;
                channel.truncate(aligned);
                writtenBytes = aligned;
            } catch (Throwable repair) {
                remember(repair);
            }
        } finally {
            accepting = false;
            while (submitting) LockSupport.parkNanos(100_000L);
            closeChannel();
            release();
            state = failure != null ? State.FAILED : successful ? State.COMPLETE : State.INCOMPLETE;
            try {
                if (failure != null) System.getLogger(Cf32Recorder.class.getName()).log(System.Logger.Level.ERROR,
                        "CF32 recording incomplete: " + file, failure);
            } finally {
                signalCompleted();
            }
        }
    }

    private void serialize(ComplexSampleBlock block) throws IOException {
        for (int n = 0; n < block.complexSamples(); n++) {
            if (buffer.remaining() < 8) flush();
            buffer.putInt(Float.floatToRawIntBits(block.i(n)));
            buffer.putInt(Float.floatToRawIntBits(block.q(n)));
        }
    }

    private void flush() throws IOException {
        buffer.flip();
        long idleSince = 0;
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer);
            if (count == 0) {
                long now = System.nanoTime();
                if (idleSince == 0) idleSince = now;
                if (now - idleSince >= 1_000_000_000L || Thread.currentThread().isInterrupted())
                    throw new IOException("CF32 file write made no progress: " + file);
                LockSupport.parkNanos(1_000_000L);
            } else {
                idleSince = 0;
                writtenBytes = Math.addExact(writtenBytes, count);
            }
        }
        buffer.clear();
    }

    private void fail(Throwable e) {
        boolean first = remember(e);
        accepting = false;
        ended = true;
        LockSupport.unpark(worker);
        if (first && settings.policy() == RecordingSettings.Policy.FAIL_SESSION) fatal.accept(e);
    }

    private synchronized boolean remember(Throwable e) {
        if (failure == null) {
            failure = e;
            state = State.FAILED;
            return true;
        }
        if (failure != e) failure.addSuppressed(e);
        return false;
    }

    private void closeChannel() {
        if (channel != null) try {
            channel.close();
        } catch (Throwable e) {
            if (remember(e) && settings.policy() == RecordingSettings.Policy.FAIL_SESSION) fatal.accept(e);
        }
    }

    private void release() {
        queue = null;
        buffer = null;
        consumedBytes = acceptedSamples * 8;
    }

    private void signalCompleted() {
        try { completed.run(); } finally { done.countDown(); }
    }

    long queueBytes() {
        return Math.max(0, acceptedSamples * 8 - consumedBytes);
    }

    void join() {
        boolean interrupted = false;
        if (worker != null) while (worker.isAlive()) try {
            worker.join();
        } catch (InterruptedException e) { interrupted = true; }
        if (interrupted) Thread.currentThread().interrupt();
    }

    boolean await(java.time.Duration timeout) throws InterruptedException {
        return done.await(timeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
    }
}
