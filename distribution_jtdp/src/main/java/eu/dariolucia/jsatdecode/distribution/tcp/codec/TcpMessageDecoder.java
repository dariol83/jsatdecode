package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Incremental, boundary-preserving JTDP v1 decoder. A protocol error ends the current session. */
public final class TcpMessageDecoder {
    private final int maxMessageBytes;
    private final ByteBuffer buffer;
    private final ArrayDeque<TcpMessage> messages = new ArrayDeque<>();
    private int expectedLength;
    private TcpMessageHeader header;

    public TcpMessageDecoder(int maxMessageBytes) {
        if (maxMessageBytes < TcpConstants.HEADER_LENGTH) {
            throw new IllegalArgumentException("Maximum message size must be at least 8 bytes");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.buffer = ByteBuffer.allocate(maxMessageBytes).order(ByteOrder.BIG_ENDIAN);
    }

    public int getMaxMessageBytes() { return maxMessageBytes; }

    public int feed(byte[] bytes) {
        return bytes == null ? 0 : feed(bytes, 0, bytes.length);
    }

    public synchronized int feed(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        int end = offset + length;
        try {
            while (offset < end) {
                int target = expectedLength == 0 ? TcpConstants.HEADER_LENGTH : expectedLength;
                int copy = Math.min(target - buffer.position(), end - offset);
                buffer.put(bytes, offset, copy);
                offset += copy;
                if (buffer.position() != target) continue;

                if (expectedLength == 0) {
                    header = TcpMessageHeader.decode(buffer.duplicate().flip());
                    long wireLength = header.unsignedLength();
                    if (wireLength > maxMessageBytes) {
                        throw new ProtocolException("Message length " + wireLength + " exceeds maximum " + maxMessageBytes);
                    }
                    if (!header.isValidLengthForType()) {
                        throw new ProtocolException("Invalid length " + wireLength + " for type " + (header.type() & 255));
                    }
                    expectedLength = (int) wireLength;
                }
                if (buffer.position() == expectedLength) {
                    TcpMessage message = decodeCompleteMessage();
                    clearPartial();
                    if (message != null) messages.add(message);
                }
            }
            return length;
        } catch (RuntimeException ex) {
            clearPartial();
            // Completed preceding messages remain available; no incomplete bytes survive the error.
            if (ex instanceof ProtocolException protocol) throw protocol;
            throw new ProtocolException("Malformed JTDP message: " + ex.getMessage(), ex);
        }
    }

    private TcpMessage decodeCompleteMessage() {
        ByteBuffer payload = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
        payload.flip().position(TcpConstants.HEADER_LENGTH);
        if (header.isFrameBatch()) {
            List<FrameRecord> frames = new ArrayList<>();
            while (payload.hasRemaining()) {
                frames.add(FrameRecord.decode(payload, maxMessageBytes - TcpConstants.HEADER_LENGTH));
            }
            if (frames.isEmpty()) throw new ProtocolException("Empty frame batch");
            return new TcpMessage(header, frames);
        }
        if (header.isMonitoring()) return new TcpMessage(header, MonitoringMessage.decodePayload(payload));
        if (header.isEvent()) {
            // Reserved codes have the same validated fixed layout, and are intentionally ignored.
            if (!TcpConstants.isValidEventCode(payload.get(payload.position()))) return null;
            return new TcpMessage(header, EventMessage.decodePayload(payload));
        }
        byte[] raw = new byte[payload.remaining()];
        payload.get(raw);
        return new TcpMessage(header, raw);
    }

    private void clearPartial() {
        buffer.clear();
        expectedLength = 0;
        header = null;
    }

    public synchronized TcpMessage poll() { return messages.poll(); }
    public synchronized boolean hasNext() { return !messages.isEmpty(); }
    public synchronized int pendingBytes() { return buffer.position(); }
    public synchronized void reset() {
        clearPartial();
        messages.clear();
    }

    public static TcpMessage decodeSingle(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length < TcpConstants.HEADER_LENGTH) throw new IllegalArgumentException("Message too short");
        TcpMessageHeader header;
        try {
            header = TcpMessageHeader.decode(bytes);
        } catch (IllegalArgumentException ex) {
            throw new ProtocolException("Invalid header", ex);
        }
        if (header.unsignedLength() != bytes.length) {
            throw new ProtocolException("Input does not contain exactly one message: " + header.unsignedLength() + " != " + bytes.length);
        }
        TcpMessageDecoder decoder = new TcpMessageDecoder(bytes.length);
        decoder.feed(bytes);
        return decoder.poll();
    }
}
