package eu.dariolucia.jsatdecode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class FileSourceFixtures {
    private FileSourceFixtures() { }

    static float[] samples(int count) {
        float[] iq = new float[count * 2];
        for (int n = 0; n < count; n++) {
            iq[n * 2] = n + 0.25f;
            iq[n * 2 + 1] = -n - 0.75f;
        }
        return iq;
    }

    static byte[] floats(float[] iq) {
        ByteBuffer bytes = ByteBuffer.allocate(iq.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : iq) bytes.putInt(Float.floatToRawIntBits(value));
        return bytes.array();
    }

    static byte[] cs16(float[] iq) {
        ByteBuffer bytes = ByteBuffer.allocate(iq.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : iq) {
            // Convert float in [-1.0, 1.0] to signed 16-bit integer
            int scaled = (int) Math.round(value * 32768.0);
            short clamped = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, scaled));
            bytes.putShort(clamped);
        }
        return bytes.array();
    }

    static byte[] format(int rate, boolean extensible) {
        ByteBuffer fmt = ByteBuffer.allocate(extensible ? 40 : 16).order(ByteOrder.LITTLE_ENDIAN);
        fmt.putShort((short) (extensible ? 0xfffe : 3)).putShort((short) 2);
        fmt.putInt(rate).putInt(rate * 8).putShort((short) 8).putShort((short) 32);
        if (extensible) {
            fmt.putShort((short) 22).putShort((short) 32).putInt(3);
            fmt.put(new byte[]{3, 0, 0, 0, 0, 0, 16, 0, (byte) 128, 0, 0, (byte) 170, 0, 56, (byte) 155, 113});
        }
        return fmt.array();
    }

    static byte[] chunk(String id, byte[] data) {
        ByteBuffer bytes = ByteBuffer.allocate(8 + data.length + (data.length & 1)).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(id.getBytes(StandardCharsets.US_ASCII)).putInt(data.length).put(data);
        return bytes.array();
    }

    static byte[] riff(byte[]... chunks) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes("WAVE".getBytes(StandardCharsets.US_ASCII));
        for (byte[] chunk : chunks) body.writeBytes(chunk);
        ByteBuffer bytes = ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(body.size()).put(body.toByteArray());
        return bytes.array();
    }

    static byte[] wav(float[] iq, int rate) {
        return riff(chunk("fmt ", format(rate, false)), chunk("data", floats(iq)));
    }

    static byte[] rf64Header(long dataBytes, long samples, int rate) {
        ByteBuffer bytes = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RF64".getBytes(StandardCharsets.US_ASCII)).putInt(-1);
        bytes.put("WAVEds64".getBytes(StandardCharsets.US_ASCII)).putInt(28);
        bytes.putLong(72 + dataBytes).putLong(dataBytes).putLong(samples).putInt(0);
        bytes.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).put(format(rate, false));
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(-1);
        return bytes.array();
    }

    static byte[] riffHeader(long dataBytes, int rate) {
        ByteBuffer bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt((int) (36 + dataBytes));
        bytes.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).put(format(rate, false));
        bytes.put("data".getBytes(StandardCharsets.US_ASCII)).putInt((int) dataBytes);
        return bytes.array();
    }

    static void writeSparse(Path path, long dataOffset, long dataBytes, byte[] header, float[] tail) throws IOException {
        // SPARSE applies only when creating a new file; tests read a tiny tail, not the hole.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            if (header.length != 0) writeFully(channel, ByteBuffer.wrap(header), 0);
            writeFully(channel, ByteBuffer.wrap(floats(tail)), dataOffset + dataBytes - tail.length * 4L);
        }
    }

    static void writeSparseCs16(Path path, long dataOffset, long dataBytes, byte[] header, float[] tail) throws IOException {
        // SPARSE applies only when creating a new file; tests read a tiny tail, not the hole.
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            if (header.length != 0) writeFully(channel, ByteBuffer.wrap(header), 0);
            // CS16: 4 bytes per complex sample
            writeFully(channel, ByteBuffer.wrap(cs16(tail)), dataOffset + dataBytes - tail.length * 4L);
        }
    }

    static void writeFully(FileChannel channel, ByteBuffer buffer, long offset) throws IOException {
        while (buffer.hasRemaining()) {
            int count = channel.write(buffer, offset);
            if (count <= 0) throw new IOException("Fixture write made no progress");
            offset += count;
        }
    }

    static Path temporaryDirectory(String prefix) throws IOException {
        Path root = Path.of("target", "file-source-fixtures").toAbsolutePath();
        Files.createDirectories(root);
        return Files.createTempDirectory(root, prefix);
    }

    static void clean(Path directory) throws IOException {
        // All fixtures are direct children of this uniquely created test directory.
        try (var entries = Files.list(directory)) {
            for (Path entry : entries.toList()) Files.delete(entry);
        }
        Files.delete(directory);
    }
}
