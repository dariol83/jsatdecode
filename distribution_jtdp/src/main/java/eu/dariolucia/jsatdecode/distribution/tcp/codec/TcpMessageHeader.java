package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents the 8-byte header of a JTDP v1 message.
 *
 * <p>The header format is:
 * <pre>
 *   Offset  Bytes  Type       Field
 *   0       1      uint8      Magic (0xA5)
 *   1       1      uint8      Type (0-2)
 *   2       2      uint16     Source ID
 *   4       4      uint32     Total message length (including header)
 * </pre>
 *
 * <p>All multibyte fields are stored in network byte order (big-endian).
 *
 * @param magic       The magic byte (must be 0xA5)
 * @param type        The message type (0=frame, 1=monitoring, 2=event)
 * @param sourceId    The antenna/decoder identifier (0-65535)
 * @param length      Raw uint32 bits of the total message length; use unsignedLength() for its numeric value
 */
public record TcpMessageHeader(byte magic, byte type, int sourceId, int length) {

    /**
     * Creates a new message header with validation.
     *
     * @param magic    The magic byte (must be 0xA5)
     * @param type     The message type (0-2 for v1)
     * @param sourceId The antenna/decoder identifier (0-65535)
     * @param length   The total message length including header
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public TcpMessageHeader {
        if (magic != TcpConstants.MAGIC) {
            throw new IllegalArgumentException("Invalid magic byte: expected 0xA5, got 0x" +
                    Integer.toHexString(magic & 0xFF));
        }
        if (sourceId < 0 || sourceId > 65535) {
            throw new IllegalArgumentException("Source ID must be between 0 and 65535: " + sourceId);
        }
        if (Integer.toUnsignedLong(length) < TcpConstants.MIN_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Message length must be at least " +
                    TcpConstants.MIN_MESSAGE_LENGTH + ": " + Integer.toUnsignedLong(length));
        }
    }

    /**
     * Creates a header for a frame batch message.
     *
     * @param sourceId The antenna/decoder identifier
     * @param payloadLength The length of the payload (frame records)
     * @return A new frame batch header
     */
    public static TcpMessageHeader frameBatch(int sourceId, int payloadLength) {
        int totalLength = TcpConstants.HEADER_LENGTH + payloadLength;
        return new TcpMessageHeader(TcpConstants.MAGIC, TcpConstants.TYPE_FRAME_BATCH, sourceId, totalLength);
    }

    /**
     * Creates a header for a monitoring message.
     *
     * @param sourceId The antenna/decoder identifier
     * @return A new monitoring header
     */
    public static TcpMessageHeader monitoring(int sourceId) {
        return new TcpMessageHeader(TcpConstants.MAGIC, TcpConstants.TYPE_MONITORING, sourceId,
                TcpConstants.MONITORING_MESSAGE_LENGTH);
    }

    /**
     * Creates a header for an event message.
     *
     * @param sourceId The antenna/decoder identifier
     * @return A new event header
     */
    public static TcpMessageHeader event(int sourceId) {
        return new TcpMessageHeader(TcpConstants.MAGIC, TcpConstants.TYPE_EVENT, sourceId,
                TcpConstants.EVENT_MESSAGE_LENGTH);
    }

    /**
     * Returns the payload length (message length minus header length).
     *
     * @return The payload length in bytes
     */
    public int payloadLength() {
        return Math.toIntExact(unsignedLength() - TcpConstants.HEADER_LENGTH);
    }

    /** Returns the wire uint32 length without sign extension. The int component retains its raw bits. */
    public long unsignedLength() {
        return Integer.toUnsignedLong(length);
    }

    /**
     * Checks if this is a frame batch message.
     *
     * @return true if type is 0 (frame batch)
     */
    public boolean isFrameBatch() {
        return type == TcpConstants.TYPE_FRAME_BATCH;
    }

    /**
     * Checks if this is a monitoring message.
     *
     * @return true if type is 1 (monitoring)
     */
    public boolean isMonitoring() {
        return type == TcpConstants.TYPE_MONITORING;
    }

    /**
     * Checks if this is an event message.
     *
     * @return true if type is 2 (event)
     */
    public boolean isEvent() {
        return type == TcpConstants.TYPE_EVENT;
    }

    /**
     * Encodes this header into a byte buffer.
     *
     * @param buffer The buffer to write to (must have at least 8 bytes remaining)
     * @return The number of bytes written (always 8)
     */
    public int encode(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        buffer.put(magic);
        buffer.put(type);
        buffer.putShort((short) sourceId);
        buffer.putInt(length);
        return TcpConstants.HEADER_LENGTH;
    }

    /**
     * Encodes this header into a byte array.
     *
     * @return A new byte array containing the encoded header
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(TcpConstants.HEADER_LENGTH);
        encode(buffer);
        return buffer.array();
    }

    /**
     * Decodes a header from a byte buffer.
     *
     * @param buffer The buffer to read from (must have at least 8 bytes remaining)
     * @return A new message header
     * @throws IllegalArgumentException if the magic byte is invalid
     */
    public static TcpMessageHeader decode(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);
        byte magic = buffer.get();
        byte type = buffer.get();
        short sourceIdShort = buffer.getShort();
        int sourceId = sourceIdShort & 0xFFFF;
        int length = buffer.getInt();
        return new TcpMessageHeader(magic, type, sourceId, length);
    }

    /**
     * Decodes a header from a byte array.
     *
     * @param bytes The byte array containing the header (must be at least 8 bytes)
     * @return A new message header
     * @throws IllegalArgumentException if the array is too short or magic is invalid
     */
    public static TcpMessageHeader decode(byte[] bytes) {
        if (bytes.length < TcpConstants.HEADER_LENGTH) {
            throw new IllegalArgumentException("Header bytes too short: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return decode(buffer);
    }

    /**
     * Checks if the message length matches the expected length for its type.
     *
     * @return true if the length is valid for the message type
     */
    public boolean isValidLengthForType() {
        if (isMonitoring()) {
            return length == TcpConstants.MONITORING_MESSAGE_LENGTH;
        }
        if (isEvent()) {
            return length == TcpConstants.EVENT_MESSAGE_LENGTH;
        }
        // Frame batch can be any length >= header + at least one frame record
        return !isFrameBatch() || unsignedLength() >= TcpConstants.HEADER_LENGTH + TcpConstants.FRAME_RECORD_OVERHEAD + 1;
    }
}
