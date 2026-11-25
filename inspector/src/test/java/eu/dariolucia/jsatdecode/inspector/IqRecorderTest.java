package eu.dariolucia.jsatdecode.inspector;

import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained inspector storage contract tests; no SDR or external dependencies. */
public final class IqRecorderTest {
    private static final Instant START = Instant.parse("2026-11-03T11:22:33.123456789Z");
    private static int checks;

    /** Runs exact-byte, stop-race, overflow and disk-failure checks. */
    public static void main(String[] arguments) throws Exception { run(); }

    /** Executes the independent recording suite. */
    public static void run() throws Exception {
        Path folder = Files.createTempDirectory("jsatinspector-recording-test-");
        try {
            exactBytes(folder);
            exactCs16Bytes(folder);
            for (RecordingFormat format : RecordingFormat.values()) diskFailure(folder, format);
            overflow(folder);
            cs16MemoryOverflow(folder);
            invalidBufferAlignment(folder);
            concurrentStop(folder);
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("jsatinspector-") && t.getName().endsWith("-writer")),
                    "Every disk worker terminates");
            System.out.println("JSatInspector IQ recorder: " + checks + " checks passed");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    private static ComplexSampleBlock block(float... iq) {
        return new ComplexSampleBlock(iq, iq.length / 2, 0, 0, 500000);
    }

    private static IqRecorder recorder(Path folder, int slots, int writeBytes, IqRecorder.FileFactory files) throws IOException {
        return new IqRecorder(folder, 137100000, 500000, 42, START, slots, 1048576, writeBytes, files);
    }

    private static void exactBytes(Path folder) throws Exception {
        float[] samples = {1, -1, -0f, Float.intBitsToFloat(0x7fc01234), Float.MIN_VALUE, Float.MAX_VALUE};
        MemoryFile memory = new MemoryFile();
        memory.maxWrite = 3;
        IqRecorder recorder = recorder(folder, 16, 16, path -> memory);
        check(recorder.file().getFileName().toString().equals("137100000_500000_42_20261103T112233.cf32"),
                "CF32 filename contains center/rate/gain and UTC recording start through seconds only");
        recorder.offer(block(samples));
        recorder.close();
        ByteBuffer expected = ByteBuffer.allocate(samples.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) expected.putInt(Float.floatToRawIntBits(sample));
        check(Arrays.equals(expected.array(), memory.data.toByteArray()), "Little-endian exact raw IEEE float bits survive partial writes");
        check(recorder.complete() && recorder.failure() == null && recorder.writtenBytes() == samples.length * 4,
                "Management close drains, flushes and reports exact completion");
        check(!memory.open, "Writer closes the channel");
        recorder.close();
        recorder.offer(block(2, 3));
        check(recorder.acceptedBytes() == samples.length * 4, "Closed recorder rejects late callbacks");

        IqRecorder real = new IqRecorder(folder, 137100000.5, 500000, 42, START, 4, 1024, 32,
                path -> Files.newByteChannel(path, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE));
        real.offer(block(0.25f, -0.5f));
        real.close();
        check(real.file().getFileName().toString().startsWith("137100000.5_"), "Numeric center retains fractional precision when provided");
        try {
            new IqRecorder(folder, 137100000.5, 500000, 42, START, 4, 1024, 32,
                    path -> Files.newByteChannel(path, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE));
            throw new AssertionError("Existing recording was replaced");
        } catch (FileAlreadyExistsException expectedFailure) {
            check(Files.size(real.file()) == 8, "CREATE_NEW never overwrites a previous recording");
        }
    }

    private static void exactCs16Bytes(Path folder) throws Exception {
        float[] samples = {0, -0f, 0.5f, -0.5f, 1, -1, 2, -2,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NaN, Float.intBitsToFloat(0x7fc01234),
                Float.MAX_VALUE, -Float.MAX_VALUE, Float.MIN_VALUE, -Float.MIN_VALUE,
                1 / 65536f, -1 / 65536f, 3 / 65536f, -3 / 65536f, Math.nextDown(1f), Math.nextUp(-1f)};
        short[] quantized = {0, 0, 16384, -16384, 32767, -32768, 32767, -32768,
                32767, -32768, 0, 0, 32767, -32768, 0, 0, 1, 0, 2, -1, 32767, -32768};
        MemoryFile memory = new MemoryFile();
        memory.maxWrite = 3;
        IqRecorder recorder = new IqRecorder(folder, 137100000, 500000, 42, START,
                RecordingFormat.CS16, 16, 1048576, 12, path -> memory);
        check(recorder.file().getFileName().toString().equals("137100000_500000_42_20261103T112233.cs16"),
                "CS16 uses the identical seconds-only filename pattern with its own extension");
        recorder.offer(block(samples));
        recorder.close();
        ByteBuffer expected = ByteBuffer.allocate(quantized.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short sample : quantized) expected.putShort(sample);
        check(Arrays.equals(expected.array(), memory.data.toByteArray()),
                "CS16 is interleaved little-endian, rounded and saturated, including NaN, infinities and rounding limits");
        check(recorder.complete() && recorder.failure() == null && recorder.acceptedBytes() == quantized.length * 2 &&
                        recorder.writtenBytes() == quantized.length * 2,
                "CS16 accepted/written byte counts reflect its four-byte complex samples");
    }

    private static void diskFailure(Path folder, RecordingFormat format) throws Exception {
        MemoryFile memory = new MemoryFile();
        memory.maxWrite = 3;
        memory.failAt = format.bytesPerComplexSample() + 3;
        IqRecorder recorder = new IqRecorder(folder, 137100000, 500000, 42, START,
                format, 4, 1048576, 16, path -> memory);
        recorder.offer(block(1, 2, 3, 4, 5, 6, 7, 8));
        recorder.close();
        check(!recorder.complete() && recorder.failure() instanceof IOException, format + " disk write failure is retained independently of acquisition");
        long expected = memory.failAt / format.bytesPerComplexSample() * format.bytesPerComplexSample();
        check(memory.length == expected && recorder.writtenBytes() == expected, format + " failed partial write leaves only complete complex samples");
    }

    private static void overflow(Path folder) throws Exception {
        MemoryFile memory = new MemoryFile();
        memory.entered = new CountDownLatch(1);
        memory.release = new CountDownLatch(1);
        IqRecorder recorder = recorder(folder, 1, 8, path -> memory);
        recorder.offer(block(1, 2, 3, 4));
        check(memory.entered.await(5, TimeUnit.SECONDS), "Disk worker reaches deliberately stalled write");
        recorder.offer(block(5, 6));
        recorder.offer(block(7, 8));
        check(recorder.failure() != null && !recorder.recording(), "Full bounded queue immediately stops recording");
        check(recorder.acceptedBytes() == 24, "Overflow rejects the failing block and preserves accepted prefix");
        memory.release.countDown();
        recorder.close();
        check(recorder.writtenBytes() == 24 && !recorder.complete(), "Overflow drains the accepted prefix but reports incomplete recording");
    }

    private static void cs16MemoryOverflow(Path folder) throws Exception {
        MemoryFile memory = new MemoryFile();
        memory.entered = new CountDownLatch(1);
        memory.release = new CountDownLatch(1);
        IqRecorder recorder = new IqRecorder(folder, 137100000, 500000, 42, START,
                RecordingFormat.CS16, 4, 16, 4, path -> memory);
        recorder.offer(block(0.1f, 0.2f, 0.3f, 0.4f));
        check(memory.entered.await(5, TimeUnit.SECONDS), "CS16 writer reaches deliberately stalled disk");
        recorder.offer(block(0.5f, 0.6f, 0.7f, 0.8f));
        recorder.offer(block(0.9f, 1));
        check(recorder.failure() != null && !recorder.recording() && recorder.acceptedBytes() == 16,
                "CS16 queue bounds retained float memory rather than its smaller serialized size");
        memory.release.countDown();
        recorder.close();
        check(recorder.writtenBytes() == 16 && !recorder.complete(),
                "CS16 memory overflow drains the accepted serialized prefix");
    }

    private static void invalidBufferAlignment(Path folder) throws Exception {
        for (RecordingFormat format : RecordingFormat.values()) {
            for (int size : new int[]{format.bytesPerComplexSample() - 1, format.bytesPerComplexSample() + 1}) {
                try {
                    new IqRecorder(folder, 137100000, 500000, 42, START, format, 4, 1024, size,
                            path -> { throw new AssertionError("Invalid alignment opened a file"); });
                    throw new AssertionError("Unaligned buffer accepted for " + format);
                } catch (IllegalArgumentException expected) {
                    checks++;
                }
            }
        }
    }

    private static void concurrentStop(Path folder) throws Exception {
        for (int iteration = 0; iteration < 12; iteration++) {
            MemoryFile memory = new MemoryFile();
            IqRecorder recorder = recorder(folder, 32768, 128, path -> memory);
            CountDownLatch first = new CountDownLatch(1);
            AtomicReference<Throwable> error = new AtomicReference<>();
            Thread producer = new Thread(() -> {
                try {
                    for (int index = 0; index < 10000; index++) {
                        recorder.offer(block(index, -index));
                        if (index == 0) first.countDown();
                    }
                } catch (Throwable e) { error.set(e); }
            }, "inspector-test-producer");
            producer.start();
            check(first.await(5, TimeUnit.SECONDS), "Concurrent producer starts");
            recorder.close();
            producer.join(5000);
            check(!producer.isAlive() && error.get() == null, "Management stop and late callbacks never race released queue storage");
            check(recorder.complete() && recorder.writtenBytes() == recorder.acceptedBytes(), "Concurrent stop drains every accepted byte");
            ByteBuffer recorded = ByteBuffer.wrap(memory.data.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
            int index = 0;
            while (recorded.hasRemaining()) {
                if (recorded.getFloat() != index || recorded.getFloat() != -index)
                    throw new AssertionError("Race changed accepted sample order at " + index);
                index++;
            }
            checks++;
        }
    }

    private static void check(boolean condition, String description) {
        checks++;
        if (!condition) throw new AssertionError(description);
    }

    private static final class MemoryFile implements SeekableByteChannel {
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        boolean open = true;
        int maxWrite = Integer.MAX_VALUE, failAt = Integer.MAX_VALUE, length;
        CountDownLatch entered, release;

        @Override public int write(ByteBuffer source) throws IOException {
            if (entered != null) {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test stalled disk timed out");
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
            }
            if (length >= failAt) throw new IOException("Injected full disk");
            int size = Math.min(Math.min(maxWrite, source.remaining()), failAt - length);
            byte[] copy = new byte[size];
            source.get(copy);
            data.writeBytes(copy);
            length += size;
            return size;
        }
        @Override public int read(ByteBuffer destination) { throw new UnsupportedOperationException(); }
        @Override public long position() { return length; }
        @Override public SeekableByteChannel position(long position) { throw new UnsupportedOperationException(); }
        @Override public long size() { return length; }
        @Override public SeekableByteChannel truncate(long size) {
            byte[] prefix = Arrays.copyOf(data.toByteArray(), (int) size);
            data.reset();
            data.writeBytes(prefix);
            length = (int) size;
            return this;
        }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
