package eu.dariolucia.jsatdecode.source.wav;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Validated contiguous IEEE float32 stereo IQ region in RIFF/WAVE or RF64/WAVE.
 * Parsing seeks over sample data/unknown chunks with constant working memory.
 * RF64 supports the common ds64 form without replacement-table entries.
 *
 * @param dataOffset payload byte offset
 * @param dataBytes  payload byte length, divisible by eight
 * @param sampleRate header complex sample rate in Hz
 */
public record WavHeader(long dataOffset, long dataBytes, double sampleRate) {
    private static final int RIFF = 0x46464952, RF64 = 0x34364652, WAVE = 0x45564157;
    private static final int FMT = 0x20746d66, DATA = 0x61746164, DS64 = 0x34367364;
    private static final long SENTINEL = 0xffffffffL;
    private static final byte[] FLOAT_GUID = {3, 0, 0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113};

    /**
     * Validates region metadata; parsing additionally checks file boundaries and encoding.
     *
     * @param dataOffset nonnegative payload offset
     * @param dataBytes  nonnegative eight-byte-aligned payload length
     * @param sampleRate positive finite samples per second
     * @throws IllegalArgumentException for invalid geometry or rate
     */
    public WavHeader {
        if (dataOffset < 0 || dataBytes < 0 || dataBytes % 8 != 0 ||
                dataOffset > Long.MAX_VALUE - dataBytes || !Double.isFinite(sampleRate) || sampleRate <= 0) {
            throw new IllegalArgumentException("Invalid WAV payload metadata");
        }
    }

    /**
     * Reads/validates all chunk geometry and the float32 stereo format, closing its channel.
     * Accepts fmt before/after data, padded unknown chunks and float extensible fmt.
     * Rejects multiple fmt/data chunks, unsupported encodings and malformed/truncated files.
     *
     * @param file non-null local recording path
     * @return immutable metadata with 64-bit file offsets
     * @throws IOException for invalid or unsupported WAV/RF64 contents or file I/O failure
     */
    public static WavHeader read(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            return parse(channel);
        } catch (ArithmeticException e) {
            throw new IOException("WAV chunk length overflow", e);
        }
    }

    private static WavHeader parse(FileChannel channel) throws IOException {
        ByteBuffer root = read(channel, 0, 12);
        int container = root.getInt();
        long declared = Integer.toUnsignedLong(root.getInt());
        if ((container != RIFF && container != RF64) || root.getInt() != WAVE) {
            throw new IOException("Expected little-endian RIFF/WAVE or RF64/WAVE");
        }
        long cursor = 12, end, rfData = -1, rfSamples = -1;
        // RF64 replaces sentinel sizes using its mandatory leading ds64 chunk.
        if (container == RF64) {
            if (declared != SENTINEL) throw new IOException("RF64 requires a sentinel RIFF size");
            ByteBuffer chunk = read(channel, cursor, 8);
            int id = chunk.getInt();
            long size = Integer.toUnsignedLong(chunk.getInt());
            if (id != DS64 || size < 28) throw new IOException("RF64 requires a leading ds64 chunk");
            long dsEnd = Math.addExact(cursor + 8, size);
            if (dsEnd > channel.size()) throw new EOFException("Truncated ds64 chunk");
            ByteBuffer ds = read(channel, cursor + 8, 28);
            long riffSize = ds.getLong();
            rfData = ds.getLong();
            rfSamples = ds.getLong();
            long table = Integer.toUnsignedLong(ds.getInt());
            if (riffSize < 4 || rfData < 0 || rfSamples < 0)
                throw new IOException("RF64 unsigned sizes exceed supported signed range");
            if (table != 0) throw new IOException("RF64 ds64 replacement tables are not supported");
            end = Math.addExact(8, riffSize);
            cursor = Math.addExact(dsEnd, size & 1);
        } else end = Math.addExact(8, declared);
        if (end < cursor || end > channel.size()) throw new EOFException("Invalid or truncated WAV container length");

        long dataOffset = -1, dataBytes = -1;
        double sampleRate = 0;
        // Walk headers only; checked long arithmetic avoids loading/skipping large data through RAM.
        while (cursor < end) {
            if (end - cursor < 8) throw new EOFException("Truncated WAV chunk header");
            ByteBuffer chunk = read(channel, cursor, 8);
            int id = chunk.getInt();
            long size = Integer.toUnsignedLong(chunk.getInt());
            if (container == RF64 && size == SENTINEL) {
                if (id != DATA) throw new IOException("Unsupported RF64 sentinel chunk");
                size = rfData;
            }
            long payload = cursor + 8;
            long payloadEnd = Math.addExact(payload, size);
            long next = Math.addExact(payloadEnd, size & 1);
            if (next > end) throw new EOFException("WAV chunk exceeds container boundary");
            if (id == FMT) {
                if (sampleRate != 0) throw new IOException("Multiple WAV fmt chunks are unsupported");
                sampleRate = readFormat(channel, payload, size);
            } else if (id == DATA) {
                if (dataOffset != -1) throw new IOException("Multiple WAV data chunks are unsupported");
                dataOffset = payload;
                dataBytes = size;
            } else if (id == DS64) throw new IOException("Unexpected ds64 chunk");
            cursor = next;
        }
        if (sampleRate == 0 || dataOffset == -1) throw new IOException("WAV requires fmt and data chunks");
        if (dataBytes % 8 != 0) throw new IOException("WAV data contains an incomplete float32 IQ sample");
        if (container == RF64 && (dataBytes != rfData || (rfSamples != 0 && rfSamples != dataBytes / 8))) {
            throw new IOException("RF64 ds64 data/sample counts disagree");
        }
        return new WavHeader(dataOffset, dataBytes, sampleRate);
    }

    private static double readFormat(FileChannel channel, long offset, long size) throws IOException {
        if (size < 16 || size == 17) throw new IOException("Truncated WAV fmt chunk");
        ByteBuffer fmt = read(channel, offset, (int) Math.min(size, 40));
        int encoding = Short.toUnsignedInt(fmt.getShort());
        int channels = Short.toUnsignedInt(fmt.getShort());
        long rate = Integer.toUnsignedLong(fmt.getInt());
        long byteRate = Integer.toUnsignedLong(fmt.getInt());
        int align = Short.toUnsignedInt(fmt.getShort());
        int bits = Short.toUnsignedInt(fmt.getShort());
        if (channels != 2 || bits != 32 || align != 8 || rate == 0 || byteRate != rate * 8) {
            throw new IOException("WAV requires stereo 32-bit IQ, block alignment 8 and consistent positive sample/byte rates");
        }
        int extension = size >= 18 ? Short.toUnsignedInt(fmt.getShort()) : 0;
        if (size >= 18 && 18L + extension > size) throw new IOException("Truncated WAV fmt extension");
        if (encoding == 3) return rate;
        if (encoding != 0xfffe || size < 40 || extension < 22) {
            throw new IOException("Unsupported WAV encoding: require IEEE float32 or extensible float32");
        }
        // Extensible PCM has the same width but a different subformat GUID: reject it.
        if (Short.toUnsignedInt(fmt.getShort()) != 32) throw new IOException("Extensible WAV valid bits must be 32");
        fmt.getInt(); // Channel mask does not change the first-I/second-Q convention.
        for (byte expected : FLOAT_GUID) {
            if (fmt.get() != expected) throw new IOException("Unsupported extensible WAV subformat (not IEEE float)");
        }
        return rate;
    }

    private static ByteBuffer read(FileChannel channel, long offset, int bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            int count = channel.read(buffer, offset);
            if (count < 0) throw new EOFException("Truncated WAV header");
            if (count == 0) throw new IOException("WAV header read made no progress");
            offset += count;
        }
        return buffer.flip();
    }
}
