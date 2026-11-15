package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Represents an event notification message (type 2).
 *
 * <p>The event message has a fixed size of 25 bytes including the header.
 * The payload format (after the 8-byte header) is:
 * <pre>
 *   Offset  Bytes  Type       Field
 *   0       1      uint8      Event code
 *   1       8      int64      Event timestamp in nanoseconds since Unix epoch
 *   9       1      uint8      Current status byte
 *   10      7      uint56     Center frequency in Hz (big-endian)
 * </pre>
 *
 * @param eventCode          The event code (0-6)
 * @param timestampNanos     The event timestamp in nanoseconds
 * @param statusByte         The current status byte at event time
 * @param centerFrequencyHz  The center frequency in Hz at event time
 */
public record EventMessage(
        byte eventCode,
        long timestampNanos,
        byte statusByte,
        long centerFrequencyHz
) {

    /**
     * Fixed size of event message payload in bytes.
     */
    public static final int PAYLOAD_LENGTH = 17;

    /**
     * Fixed size of complete event message in bytes (header + payload).
     */
    public static final int MESSAGE_LENGTH = TcpConstants.EVENT_MESSAGE_LENGTH;

    /**
     * Creates a new event message with validation.
     *
     * @param eventCode          The event code (0-6)
     * @param timestampNanos     The event timestamp in nanoseconds
     * @param statusByte         The current status byte
     * @param centerFrequencyHz  The center frequency in Hz (must be non-negative)
     * @throws IllegalArgumentException if eventCode is invalid or centerFrequencyHz is negative
     */
    public EventMessage {
        if (!TcpConstants.isValidEventCode(eventCode)) {
            throw new IllegalArgumentException("Invalid event code: " + (eventCode & 0xFF));
        }
        if (centerFrequencyHz < 0) {
            throw new IllegalArgumentException("Center frequency must be non-negative: " + centerFrequencyHz);
        }
    }

    /**
     * Creates an event message with individual state values.
     *
     * @param eventCode          The event code (0-6)
     * @param timestampNanos     The event timestamp in nanoseconds
     * @param carrierState       Carrier state (0-3)
     * @param symbolState        Symbol state (0-3)
     * @param frameState         Frame state (0-3)
     * @param snrAvailable       SNR availability (0 or 1)
     * @param centerFrequencyHz  The center frequency in Hz
     * @return A new event message
     */
    public static EventMessage create(
            byte eventCode, long timestampNanos,
            int carrierState, int symbolState, int frameState, int snrAvailable,
            long centerFrequencyHz) {

        byte statusByte = TcpConstants.encodeStatus(carrierState, symbolState, frameState, snrAvailable);
        return new EventMessage(eventCode, timestampNanos, statusByte, centerFrequencyHz);
    }

    /**
     * Checks if this is a decoder started event.
     *
     * @return true if event code is 0
     */
    public boolean isDecoderStarted() {
        return eventCode == TcpConstants.EVENT_DECODER_STARTED;
    }

    /**
     * Checks if this is a decoder stopped event.
     *
     * @return true if event code is 1
     */
    public boolean isDecoderStopped() {
        return eventCode == TcpConstants.EVENT_DECODER_STOPPED;
    }

    /**
     * Checks if this is a carrier state changed event.
     *
     * @return true if event code is 2
     */
    public boolean isCarrierStateChanged() {
        return eventCode == TcpConstants.EVENT_CARRIER_STATE_CHANGED;
    }

    /**
     * Checks if this is a symbol state changed event.
     *
     * @return true if event code is 3
     */
    public boolean isSymbolStateChanged() {
        return eventCode == TcpConstants.EVENT_SYMBOL_STATE_CHANGED;
    }

    /**
     * Checks if this is a frame state changed event.
     *
     * @return true if event code is 4
     */
    public boolean isFrameStateChanged() {
        return eventCode == TcpConstants.EVENT_FRAME_STATE_CHANGED;
    }

    /**
     * Checks if this is a decoder error event.
     *
     * @return true if event code is 5
     */
    public boolean isDecoderError() {
        return eventCode == TcpConstants.EVENT_DECODER_ERROR;
    }

    /**
     * Checks if this is a distribution overrun event.
     *
     * @return true if event code is 6
     */
    public boolean isDistributionOverrun() {
        return eventCode == TcpConstants.EVENT_DISTRIBUTION_OVERRUN;
    }

    /**
     * Gets the carrier state from the status byte.
     *
     * @return carrier state (0-3)
     */
    public int getCarrierState() {
        return TcpConstants.decodeCarrierState(statusByte);
    }

    /**
     * Gets the symbol state from the status byte.
     *
     * @return symbol state (0-3)
     */
    public int getSymbolState() {
        return TcpConstants.decodeSymbolState(statusByte);
    }

    /**
     * Gets the frame state from the status byte.
     *
     * @return frame state (0-3)
     */
    public int getFrameState() {
        return TcpConstants.decodeFrameState(statusByte);
    }

    /**
     * Encodes this event message into a byte buffer (payload only, no header).
     *
     * @param buffer The buffer to write to (must have at least 17 bytes remaining)
     * @return The number of bytes written (always 17)
     */
    public int encodePayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Event code (1 byte)
        buffer.put(eventCode);

        // Timestamp (8 bytes, signed int64)
        buffer.putLong(timestampNanos);

        // Status byte (1 byte)
        buffer.put(statusByte);

        // Center frequency (7 bytes, big-endian uint56)
        byte[] freqBytes = longToUint56(centerFrequencyHz);
        buffer.put(freqBytes, 0, 7);

        return PAYLOAD_LENGTH;
    }

    /**
     * Encodes this event message into a byte array (payload only).
     *
     * @return A new byte array containing the encoded payload
     */
    public byte[] toPayloadBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(PAYLOAD_LENGTH);
        buffer.order(ByteOrder.BIG_ENDIAN);
        encodePayload(buffer);
        return buffer.array();
    }

    /**
     * Decodes an event message payload from a byte buffer.
     *
     * @param buffer The buffer to read from (must have at least 17 bytes remaining)
     * @return A new event message
     */
    public static EventMessage decodePayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Event code (1 byte)
        byte eventCode = buffer.get();

        // Timestamp (8 bytes, signed int64)
        long timestampNanos = buffer.getLong();

        // Status byte (1 byte)
        byte statusByte = buffer.get();

        // Center frequency (7 bytes, big-endian uint56)
        byte[] freqBytes = new byte[7];
        buffer.get(freqBytes, 0, 7);
        long centerFrequencyHz = uint56ToLong(freqBytes);

        return new EventMessage(eventCode, timestampNanos, statusByte, centerFrequencyHz);
    }

    /**
     * Decodes an event message payload from a byte array.
     *
     * @param bytes The byte array containing the payload (must be at least 17 bytes)
     * @return A new event message
     * @throws IllegalArgumentException if the array is too short
     */
    public static EventMessage decodePayload(byte[] bytes) {
        if (bytes.length < PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Event payload too short: " + bytes.length);
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        buffer.order(ByteOrder.BIG_ENDIAN);
        return decodePayload(buffer);
    }

    /**
     * Encodes a 56-bit unsigned long to 7 bytes (big-endian).
     *
     * @param value The value to encode (must be &lt; 2^56)
     * @return 7-byte array
     */
    private static byte[] longToUint56(long value) {
        if (value < 0 || value >= (1L << 56)) {
            throw new IllegalArgumentException("Value out of range for uint56: " + value);
        }
        byte[] result = new byte[7];
        for (int i = 6; i >= 0; i--) {
            result[i] = (byte) (value & 0xFF);
            value >>>= 8;
        }
        return result;
    }

    /**
     * Decodes a 56-bit unsigned long from 7 bytes (big-endian).
     *
     * @param bytes The 7-byte array
     * @return The decoded value
     */
    private static long uint56ToLong(byte[] bytes) {
        if (bytes.length < 7) {
            throw new IllegalArgumentException("Need 7 bytes for uint56");
        }
        long result = 0;
        for (int i = 0; i < 7; i++) {
            result = (result << 8) | (bytes[i] & 0xFF);
        }
        return result;
    }

    @Override
    public String toString() {
        String eventName;
        switch (eventCode) {
            case TcpConstants.EVENT_DECODER_STARTED:
                eventName = "DECODER_STARTED";
                break;
            case TcpConstants.EVENT_DECODER_STOPPED:
                eventName = "DECODER_STOPPED";
                break;
            case TcpConstants.EVENT_CARRIER_STATE_CHANGED:
                eventName = "CARRIER_STATE_CHANGED";
                break;
            case TcpConstants.EVENT_SYMBOL_STATE_CHANGED:
                eventName = "SYMBOL_STATE_CHANGED";
                break;
            case TcpConstants.EVENT_FRAME_STATE_CHANGED:
                eventName = "FRAME_STATE_CHANGED";
                break;
            case TcpConstants.EVENT_DECODER_ERROR:
                eventName = "DECODER_ERROR";
                break;
            case TcpConstants.EVENT_DISTRIBUTION_OVERRUN:
                eventName = "DISTRIBUTION_OVERRUN";
                break;
            default:
                eventName = "UNKNOWN_" + (eventCode & 0xFF);
        }
        return "EventMessage{" +
                "event=" + eventName +
                ", timestamp=" + timestampNanos + "ns" +
                ", centerFreq=" + centerFrequencyHz + "Hz" +
                ", status=" + String.format("0x%02X", statusByte) +
                '}';
    }
}
