package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Represents a single frame record within a frame batch message.
 *
 * <p>The frame record format is:
 * <pre>
 *   Offset  Bytes  Type       Field
 *   0       1      uint8      Quality (0=good, 1=bad, 2=unknown)
 *   1       8      int64      Reception timestamp (nanoseconds since Unix epoch)
 *   9       4      uint32     Frame length in bytes (F)
 *   13      F      bytes      Raw frame bytes
 * </pre>
 *
 * <p>Each frame record occupies exactly 13 + F bytes, where F is the frame length.
 *
 * @param quality        The frame quality (0=good, 1=bad, 2=unknown)
 * @param timestampNanos The reception timestamp in nanoseconds since Unix epoch
 * @param frameData     The raw frame bytes (non-null, non-empty)
 */
public record FrameRecord(byte quality, long timestampNanos, byte[] frameData) {

    /**
     * Fixed overhead per frame record in bytes.
     * Quality (1) + Timestamp (8) + Length (4) = 13 bytes.
     */
    public static final int OVERHEAD = 13;

    /**
     * Creates a new frame record with validation.
     *
     * @param quality        The frame quality (0-2)
     * @param timestampNanos The reception timestamp in nanoseconds
     * @param frameData     The raw frame bytes (must be non-null and non-empty)
     * @throws IllegalArgumentException if quality is invalid or frameData is null/empty
     */
    public FrameRecord {
        if (quality < 0 || quality > 2) {
            throw new IllegalArgumentException("Invalid quality: must be 0, 1, or 2. Got: " + quality);
        }
        Objects.requireNonNull(frameData, "frameData cannot be null");
        if (frameData.length == 0) {
            throw new IllegalArgumentException("frameData cannot be empty");
        }
        // Defensive copy - in records, modifying the parameter automatically affects the field
        frameData = frameData.clone();
    }

    /**
     * Returns the total length of this frame record in bytes.
     *
     * @return The record length (13 + frame data length)
     */
    public int recordLength() {
        return OVERHEAD + frameData.length;
    }

    /**
     * Checks if the frame quality is good.
     *
     * @return true if quality is 0
     */
    public boolean isGood() {
        return quality == TcpConstants.FRAME_QUALITY_GOOD;
    }

    /**
     * Checks if the frame quality is bad.
     *
     * @return true if quality is 1
     */
    public boolean isBad() {
        return quality == TcpConstants.FRAME_QUALITY_BAD;
    }

    /**
     * Checks if the frame quality is unknown.
     *
     * @return true if quality is 2
     */
    public boolean isUnknown() {
        return quality == TcpConstants.FRAME_QUALITY_UNKNOWN;
    }

    /**
     * Encodes this frame record into a byte buffer.
     *
     * @param buffer The buffer to write to (must have at least recordLength() bytes remaining)
     * @return The number of bytes written
     */
    public int encode(ByteBuffer buffer) {
        buffer.put(quality);
        buffer.putLong(timestampNanos);
        buffer.putInt(frameData.length);
        buffer.put(frameData);
        return recordLength();
    }

    /**
     * Encodes this frame record into a byte array.
     *
     * @return A new byte array containing the encoded frame record
     */
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(recordLength());
        encode(buffer);
        return buffer.array();
    }

    /**
     * Decodes a frame record from a byte buffer.
     *
     * @param buffer The buffer to read from
     * @param maxFrameLength Maximum allowed frame length
     * @return A new frame record
     * @throws IllegalArgumentException if the frame length is invalid or exceeds maxFrameLength
     */
    public static FrameRecord decode(ByteBuffer buffer, int maxFrameLength) {
        byte quality = buffer.get();
        long timestampNanos = buffer.getLong();
        int frameLength = buffer.getInt();

        // Validate frame length
        if (frameLength <= 0) {
            throw new IllegalArgumentException("Invalid frame length: " + frameLength);
        }
        if (maxFrameLength > 0 && frameLength > maxFrameLength) {
            throw new IllegalArgumentException("Frame length " + frameLength + 
                    " exceeds maximum " + maxFrameLength);
        }

        byte[] frameData = new byte[frameLength];
        buffer.get(frameData);

        return new FrameRecord(quality, timestampNanos, frameData);
    }

    /**
     * Decodes a frame record from a byte array starting at a given offset.
     *
     * @param bytes The byte array containing the frame record
     * @param offset The offset where the frame record starts
     * @param maxFrameLength Maximum allowed frame length
     * @return A new frame record
     * @throws IllegalArgumentException if the array is too short or frame length is invalid
     */
    public static FrameRecord decode(byte[] bytes, int offset, int maxFrameLength) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, offset, bytes.length - offset);
        return decode(buffer, maxFrameLength);
    }

    /**
     * Decodes a frame record from a byte array.
     *
     * @param bytes The byte array containing exactly one frame record
     * @param maxFrameLength Maximum allowed frame length
     * @return A new frame record
     */
    public static FrameRecord decode(byte[] bytes, int maxFrameLength) {
        return decode(bytes, 0, maxFrameLength);
    }

    /**
     * Creates a frame record from an AnnotatedFrame.
     *
     * @param frame The annotated frame
     * @param quality The frame quality
     * @return A new frame record
     */
    public static FrameRecord fromAnnotatedFrame(eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame frame, byte quality) {
        Objects.requireNonNull(frame, "frame cannot be null");
        return new FrameRecord(quality, frame.timestampNanos(), frame.data());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FrameRecord that = (FrameRecord) o;
        return quality == that.quality &&
                timestampNanos == that.timestampNanos &&
                Arrays.equals(frameData, that.frameData);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(quality, timestampNanos);
        result = 31 * result + Arrays.hashCode(frameData);
        return result;
    }

    @Override
    public String toString() {
        return "FrameRecord{" +
                "quality=" + quality +
                ", timestampNanos=" + timestampNanos +
                ", frameLength=" + frameData.length +
                '}';
    }
}
