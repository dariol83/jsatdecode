package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decodes JTDP v1 messages from byte buffers.
 *
 * <p>This class provides methods to decode frame batch, monitoring, and event messages
 * from the wire format. It handles partial reads, message validation, and error detection.
 *
 * <p>Usage example:
 * <pre>{@code
 * TcpMessageDecoder decoder = new TcpMessageDecoder(maxMessageBytes);
 * 
 * // Feed incoming bytes to the decoder
 * decoder.feed(bytes);
 * 
 * // Process complete messages
 * TcpMessage message;
 * while ((message = decoder.poll()) != null) {
 *     // Process message
 * }
 * }</pre>
 *
 * <p>The decoder maintains an internal buffer and handles:
 * <ul>
 *   <li>Partial message reads</li>
 *   <li>Multiple messages in a single read</li>
 *   <li>Message validation (magic, length, type)</li>
 *   <li>Protocol error detection</li>
 * </ul>
 */
public final class TcpMessageDecoder {

    private final int maxMessageBytes;
    private final ByteBuffer buffer;
    private int expectedLength;
    private TcpMessageHeader header;

    /**
     * Creates a new message decoder with the specified maximum message size.
     *
     * @param maxMessageBytes  The maximum allowed message size in bytes
     * @throws IllegalArgumentException if maxMessageBytes is too small
     */
    public TcpMessageDecoder(int maxMessageBytes) {
        if (maxMessageBytes < TcpConstants.MIN_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Max message bytes must be at least " +
                    TcpConstants.MIN_MESSAGE_LENGTH + ": " + maxMessageBytes);
        }
        this.maxMessageBytes = maxMessageBytes;
        this.buffer = ByteBuffer.allocate(maxMessageBytes);
        this.buffer.order(ByteOrder.BIG_ENDIAN);
        this.expectedLength = 0;
        this.header = null;
    }

    /**
     * Gets the maximum message size.
     *
     * @return The maximum message size in bytes
     */
    public int getMaxMessageBytes() {
        return maxMessageBytes;
    }

    /**
     * Feeds incoming bytes to the decoder.
     *
     * <p>This method copies the input bytes into the internal buffer and attempts to
     * parse complete messages. Any bytes that don't form a complete message are retained
     * in the buffer for the next call.
     *
     * @param bytes  The incoming bytes (may be null or empty)
     * @return The number of bytes consumed from the input
     * @throws IllegalArgumentException if bytes is null
     * @throws ProtocolException if a protocol error is detected
     */
    public int feed(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        return feed(bytes, 0, bytes.length);
    }

    /**
     * Feeds a portion of incoming bytes to the decoder.
     *
     * <p>This method copies the specified portion of the input bytes into the internal buffer
     * and attempts to parse complete messages.
     *
     * @param bytes    The incoming bytes (may be null or empty)
     * @param offset   The starting offset in the byte array
     * @param length   The number of bytes to read
     * @return The number of bytes consumed from the input
     * @throws IllegalArgumentException if bytes is null or offset/length are invalid
     * @throws ProtocolException if a protocol error is detected
     */
    public int feed(byte[] bytes, int offset, int length) {
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IllegalArgumentException("Invalid offset or length: offset=" + offset + ", length=" + length + ", array length=" + bytes.length);
        }

        int consumed = 0;
        int endOffset = offset + length;

        while (offset < endOffset) {
            // If we're expecting a complete message
            if (expectedLength > 0) {
                // Calculate how many bytes we need
                int remaining = expectedLength - buffer.position();
                int available = endOffset - offset;
                int toCopy = Math.min(remaining, available);

                // Copy bytes to buffer
                buffer.put(bytes, offset, toCopy);
                offset += toCopy;
                consumed += toCopy;

                // Check if we have a complete message
                if (buffer.position() >= expectedLength) {
                    // Process the complete message
                    TcpMessage message = decodeCompleteMessage();
                    if (message != null) {
                        // Reset for next message
                        buffer.clear();
                        expectedLength = 0;
                        header = null;
                        // The message is available via poll()
                        addToQueue(message);
                    }
                }
            } else {
                // We need to read the header first
                int headerBytesNeeded = TcpConstants.HEADER_LENGTH - buffer.position();
                int available = endOffset - offset;
                int toCopy = Math.min(headerBytesNeeded, available);

                // Copy bytes to buffer
                buffer.put(bytes, offset, toCopy);
                offset += toCopy;
                consumed += toCopy;

                // Check if we have a complete header
                if (buffer.position() >= TcpConstants.HEADER_LENGTH) {
                    // Save the header data by flipping temporarily
                    buffer.flip();
                    try {
                        header = TcpMessageHeader.decode(buffer);
                    } catch (IllegalArgumentException e) {
                        // Convert validation exceptions to protocol exceptions
                        throw new ProtocolException("Invalid header: " + e.getMessage(), e);
                    }
                    // Reset buffer to append mode, keeping header in buffer
                    buffer.position(buffer.limit());
                    buffer.limit(buffer.capacity());

                    // Validate the header
                    if (header.magic() != TcpConstants.MAGIC) {
                        throw new ProtocolException("Invalid magic byte: expected 0xA5, got 0x" +
                                Integer.toHexString(header.magic() & 0xFF));
                    }

                    // Check length
                    if (header.length() < TcpConstants.MIN_MESSAGE_LENGTH) {
                        throw new ProtocolException("Message length too small: " + header.length());
                    }
                    if (header.length() > maxMessageBytes) {
                        throw new ProtocolException("Message length " + header.length() +
                                " exceeds maximum " + maxMessageBytes);
                    }

                    // For known types, validate length
                    if (header.isMonitoring() && header.length() != TcpConstants.MONITORING_MESSAGE_LENGTH) {
                        throw new ProtocolException("Invalid monitoring message length: " + header.length());
                    }
                    if (header.isEvent() && header.length() != TcpConstants.EVENT_MESSAGE_LENGTH) {
                        throw new ProtocolException("Invalid event message length: " + header.length());
                    }

                    // Set expected length
                    expectedLength = header.length();

                    // If this is a known fixed-size message, we might already have enough data
                    if (buffer.position() >= expectedLength) {
                        // Process the complete message
                        TcpMessage message = decodeCompleteMessage();
                        if (message != null) {
                            // Reset for next message
                            buffer.clear();
                            expectedLength = 0;
                            header = null;
                            // The message is available via poll()
                            addToQueue(message);
                        }
                    }
                }
            }
        }

        return consumed;
    }

    /**
     * Decodes a complete message from the buffer.
     *
     * @return The decoded message, or null if not enough data
     */
    private TcpMessage decodeCompleteMessage() {
        if (header == null || buffer.position() < expectedLength) {
            return null;
        }

        // Buffer contains both header (at 0-7) and payload (at 8 to buffer.position()-1)
        // Total message length is expectedLength which includes header
        buffer.flip();
        try {
            return decodeMessage(buffer);
        } catch (ProtocolException e) {
            throw new ProtocolException("Failed to decode message of type " + header.type() +
                    ", length " + header.length(), e);
        } finally {
            // Reset buffer for next message
            buffer.clear();
        }
    }

    /**
     * Decodes a message from a complete buffer.
     *
     * @param buffer The buffer containing the complete message
     * @return The decoded message
     * @throws ProtocolException if the message is malformed
     */
    private TcpMessage decodeMessage(ByteBuffer buffer) {
        // Parse header
        TcpMessageHeader header = TcpMessageHeader.decode(buffer);

        // Decode based on type
        if (header.isFrameBatch()) {
            return decodeFrameBatchMessage(header, buffer);
        } else if (header.isMonitoring()) {
            return decodeMonitoringMessage(header, buffer);
        } else if (header.isEvent()) {
            return decodeEventMessage(header, buffer);
        } else if (TcpConstants.isReservedType(header.type())) {
            // Skip reserved types by their length
            return new TcpMessage(header, new byte[header.payloadLength()]);
        } else {
            throw new ProtocolException("Unknown message type: " + (header.type() & 0xFF));
        }
    }

    /**
     * Decodes a frame batch message.
     *
     * @param header  The message header
     * @param buffer  The buffer containing the complete message
     * @return The decoded frame batch message
     */
    private TcpMessage decodeFrameBatchMessage(TcpMessageHeader header, ByteBuffer buffer) {
        int payloadLength = header.payloadLength();
        byte[] payload = new byte[payloadLength];
        buffer.get(payload);

        // Parse frame records
        List<FrameRecord> frames = new ArrayList<>();
        int offset = 0;

        while (offset < payloadLength) {
            // Check if we have enough for a frame record header
            if (offset + FrameRecord.OVERHEAD > payloadLength) {
                throw new ProtocolException("Incomplete frame record at offset " + offset);
            }

            // Get frame length
            int frameLength = ByteBuffer.wrap(payload, offset + 9, 4).order(ByteOrder.BIG_ENDIAN).getInt();
            if (frameLength <= 0) {
                throw new ProtocolException("Invalid frame length: " + frameLength);
            }

            // Check if we have enough for the complete frame
            int recordLength = FrameRecord.OVERHEAD + frameLength;
            if (offset + recordLength > payloadLength) {
                throw new ProtocolException("Frame record exceeds message boundary at offset " + offset);
            }

            // Decode the frame record
            ByteBuffer frameBuffer = ByteBuffer.wrap(payload, offset, recordLength);
            frameBuffer.order(ByteOrder.BIG_ENDIAN);
            FrameRecord frame = FrameRecord.decode(frameBuffer, maxMessageBytes - TcpConstants.HEADER_LENGTH);
            frames.add(frame);

            offset += recordLength;
        }

        return new TcpMessage(header, frames);
    }

    /**
     * Decodes a monitoring message.
     *
     * @param header  The message header
     * @param buffer  The buffer containing the complete message
     * @return The decoded monitoring message
     */
    private TcpMessage decodeMonitoringMessage(TcpMessageHeader header, ByteBuffer buffer) {
        byte[] payload = new byte[header.payloadLength()];
        buffer.get(payload);

        MonitoringMessage monitoring = MonitoringMessage.decodePayload(payload);
        return new TcpMessage(header, monitoring);
    }

    /**
     * Decodes an event message.
     *
     * @param header  The message header
     * @param buffer  The buffer containing the complete message
     * @return The decoded event message
     */
    private TcpMessage decodeEventMessage(TcpMessageHeader header, ByteBuffer buffer) {
        byte[] payload = new byte[header.payloadLength()];
        buffer.get(payload);

        EventMessage event = EventMessage.decodePayload(payload);
        return new TcpMessage(header, event);
    }

    // Message queue for polled messages
    private final List<TcpMessage> messageQueue = new ArrayList<>();

    /**
     * Adds a message to the internal queue.
     *
     * @param message The message to add
     */
    private synchronized void addToQueue(TcpMessage message) {
        messageQueue.add(message);
    }

    /**
     * Polls for the next complete message.
     *
     * <p>This method returns the next complete message that has been decoded, or null
     * if no messages are available.
     *
     * @return The next message, or null if none available
     */
    public synchronized TcpMessage poll() {
        if (messageQueue.isEmpty()) {
            return null;
        }
        return messageQueue.remove(0);
    }

    /**
     * Checks if there are messages available to poll.
     *
     * @return true if at least one message is available
     */
    public synchronized boolean hasNext() {
        return !messageQueue.isEmpty();
    }

    /**
     * Resets the decoder to its initial state.
     *
     * <p>This clears all internal buffers and message queues. Any partial messages
     * being decoded are discarded.
     */
    public synchronized void reset() {
        buffer.clear();
        expectedLength = 0;
        header = null;
        messageQueue.clear();
    }

    /**
     * Returns the current number of pending bytes in the decoder's buffer.
     *
     * @return The number of pending bytes
     */
    public int pendingBytes() {
        return buffer.position();
    }

    /**
     * Decodes a single complete message from a byte array.
     *
     * <p>This is a convenience method for decoding a message when you already have
     * the complete message in a byte array.
     *
     * @param bytes The byte array containing a complete message
     * @return The decoded message
     * @throws ProtocolException if the message is malformed
     * @throws IllegalArgumentException if bytes is null or too short
     */
    public static TcpMessage decodeSingle(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes cannot be null");
        if (bytes.length < TcpConstants.HEADER_LENGTH) {
            throw new IllegalArgumentException("Message too short: " + bytes.length + " bytes");
        }

        // Use a reasonable max size - either the input length or a default of 1MB
        // This prevents OOM errors while still allowing decoding of large messages
        int maxSize = Math.max(bytes.length, 1048576);
        
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);

        TcpMessageDecoder decoder = new TcpMessageDecoder(maxSize);
        decoder.feed(bytes);
        TcpMessage message = decoder.poll();

        if (message == null) {
            throw new ProtocolException("No complete message found in input");
        }

        // Check for trailing bytes
        if (bytes.length > message.header().length()) {
            throw new ProtocolException("Trailing bytes after message: " + bytes.length + " > " + message.header().length());
        }

        return message;
    }
}
