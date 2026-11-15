package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Represents a monitoring snapshot message (type 1).
 *
 * <p>The monitoring message has a fixed size of 40 bytes including the header.
 * The payload format (after the 8-byte header) is:
 * <pre>
 *   Offset  Bytes  Type       Field
 *   0       1      uint8      Status byte
 *   1       7      uint56     Center frequency in Hz (big-endian)
 *   8       4      int32      Carrier offset in Hz (signed, 0x80000000 = unavailable)
 *   12      4      binary32   SNR in dB (NaN = unavailable)
 *   16      4      uint32     Total decoded frames
 *   20      4      uint32     Total error frames
 *   24      4      uint32     Total good decoded frames
 *   28      4      uint32     Frames accepted for TCP distribution
 * </pre>
 *
 * @param statusByte           The encoded status byte
 * @param centerFrequencyHz    The center frequency in Hz (0 to 2^56-1)
 * @param carrierOffsetHz      The carrier offset in Hz (signed, Integer.MIN_VALUE = unavailable)
 * @param snrDb                The SNR in dB (NaN = unavailable)
 * @param totalDecodedFrames   Total decoded frames count
 * @param totalErrorFrames     Total error frames count
 * @param totalGoodFrames      Total good decoded frames count
 * @param framesAccepted       Frames accepted for TCP distribution
 */
public record MonitoringMessage(
        byte statusByte,
        long centerFrequencyHz,
        int carrierOffsetHz,
        float snrDb,
        long totalDecodedFrames,
        long totalErrorFrames,
        long totalGoodFrames,
        long framesAccepted
) {

    /**
     * Fixed size of monitoring message payload in bytes.
     */
    public static final int PAYLOAD_LENGTH = 32;

    /**
     * Fixed size of complete monitoring message in bytes (header + payload).
     */
    public static final int MESSAGE_LENGTH = TcpConstants.MONITORING_MESSAGE_LENGTH;

    /**
     * Creates a new monitoring message with validation.
     *
     * @param statusByte           The encoded status byte
     * @param centerFrequencyHz    The center frequency in Hz (must be non-negative)
     * @param carrierOffsetHz      The carrier offset in Hz (signed)
     * @param snrDb                The SNR in dB
     * @param totalDecodedFrames   Total decoded frames count (must be non-negative)
     * @param totalErrorFrames     Total error frames count (must be non-negative)
     * @param totalGoodFrames      Total good decoded frames count (must be non-negative)
     * @param framesAccepted       Frames accepted for TCP distribution (must be non-negative)
     * @throws IllegalArgumentException if any parameter is invalid
     */
    public MonitoringMessage {
        if (centerFrequencyHz < 0 || centerFrequencyHz >= (1L << 56)) {
            throw new IllegalArgumentException("Center frequency must be non-negative: " + centerFrequencyHz);
        }
        if (totalDecodedFrames < 0) {
            throw new IllegalArgumentException("Total decoded frames must be non-negative: " + totalDecodedFrames);
        }
        if (totalErrorFrames < 0) {
            throw new IllegalArgumentException("Total error frames must be non-negative: " + totalErrorFrames);
        }
        if (totalGoodFrames < 0) {
            throw new IllegalArgumentException("Total good frames must be non-negative: " + totalGoodFrames);
        }
        if (framesAccepted < 0) {
            throw new IllegalArgumentException("Frames accepted must be non-negative: " + framesAccepted);
        }
        // Validate counter consistency: total = good + error (modulo 2^32)
        // Note: We allow wraparound, so we don't enforce strict equality
    }

    /**
     * Creates a monitoring message from individual state values.
     *
     * @param carrierState     Carrier state (0-3)
     * @param symbolState      Symbol state (0-3)
     * @param frameState       Frame state (0-3)
     * @param snrAvailable     SNR availability (0 or 1)
     * @param centerFrequencyHz Center frequency in Hz
     * @param carrierOffsetHz   Carrier offset in Hz (use Integer.MIN_VALUE for unavailable)
     * @param snrDb             SNR in dB (use Float.NaN for unavailable)
     * @param totalDecodedFrames Total decoded frames
     * @param totalErrorFrames   Total error frames
     * @param totalGoodFrames    Total good frames
     * @param framesAccepted     Frames accepted for distribution
     * @return A new monitoring message
     */
    public static MonitoringMessage create(
            int carrierState, int symbolState, int frameState, int snrAvailable,
            long centerFrequencyHz, int carrierOffsetHz, float snrDb,
            long totalDecodedFrames, long totalErrorFrames, long totalGoodFrames, long framesAccepted) {

        byte statusByte = TcpConstants.encodeStatus(carrierState, symbolState, frameState, snrAvailable);
        return new MonitoringMessage(statusByte, centerFrequencyHz, carrierOffsetHz, snrDb,
                totalDecodedFrames, totalErrorFrames, totalGoodFrames, framesAccepted);
    }

    /**
     * Checks if SNR is available.
     * SNR is valid only if the availability bits equal 1 AND the float is finite.
     *
     * @return true if SNR is available
     */
    public boolean isSnrAvailable() {
        return TcpConstants.decodeSnrAvailable(statusByte) == TcpConstants.SNR_AVAILABLE &&
                Float.isFinite(snrDb);
    }

    /**
     * Checks if carrier offset is available.
     *
     * @return true if carrier offset is not the unavailable sentinel
     */
    public boolean isCarrierOffsetAvailable() {
        return carrierOffsetHz != TcpConstants.CARRIER_OFFSET_UNAVAILABLE;
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
     * Encodes this monitoring message into a byte buffer (payload only, no header).
     *
     * @param buffer The buffer to write to (must have at least 32 bytes remaining)
     * @return The number of bytes written (always 32)
     */
    public int encodePayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Status byte (1 byte)
        boolean available = isSnrAvailable();
        buffer.put((byte) ((statusByte & ~TcpConstants.STATUS_SNR_MASK) | (available ? 1 : 0)));

        // Center frequency (7 bytes, big-endian uint56)
        // We need to encode a 56-bit unsigned value
        byte[] freqBytes = longToUint56(centerFrequencyHz);
        buffer.put(freqBytes, 0, 7);

        // Carrier offset (4 bytes, signed int32)
        buffer.putInt(carrierOffsetHz);

        // SNR (4 bytes, IEEE-754 binary32)
        buffer.putFloat(available ? snrDb : Float.NaN);

        // Counters (4 bytes each, unsigned uint32)
        // Java doesn't have unsigned int, so we use long and cast
        buffer.putInt((int) (totalDecodedFrames & 0xFFFFFFFFL));
        buffer.putInt((int) (totalErrorFrames & 0xFFFFFFFFL));
        buffer.putInt((int) (totalGoodFrames & 0xFFFFFFFFL));
        buffer.putInt((int) (framesAccepted & 0xFFFFFFFFL));

        return PAYLOAD_LENGTH;
    }

    /**
     * Encodes this monitoring message into a byte array (payload only).
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
     * Decodes a monitoring message payload from a byte buffer.
     *
     * @param buffer The buffer to read from (must have at least 32 bytes remaining)
     * @return A new monitoring message
     */
    public static MonitoringMessage decodePayload(ByteBuffer buffer) {
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Status byte (1 byte)
        byte statusByte = buffer.get();

        // Center frequency (7 bytes, big-endian uint56)
        byte[] freqBytes = new byte[7];
        buffer.get(freqBytes, 0, 7);
        long centerFrequencyHz = uint56ToLong(freqBytes);

        // Carrier offset (4 bytes, signed int32)
        int carrierOffsetHz = buffer.getInt();

        // SNR (4 bytes, IEEE-754 binary32)
        float snrDb = buffer.getFloat();

        // Counters (4 bytes each, unsigned uint32 stored as signed int32)
        // We need to convert back to unsigned
        long totalDecodedFrames = buffer.getInt() & 0xFFFFFFFFL;
        long totalErrorFrames = buffer.getInt() & 0xFFFFFFFFL;
        long totalGoodFrames = buffer.getInt() & 0xFFFFFFFFL;
        long framesAccepted = buffer.getInt() & 0xFFFFFFFFL;

        return new MonitoringMessage(statusByte, centerFrequencyHz, carrierOffsetHz, snrDb,
                totalDecodedFrames, totalErrorFrames, totalGoodFrames, framesAccepted);
    }

    /**
     * Decodes a monitoring message payload from a byte array.
     *
     * @param bytes The byte array containing the payload (must be at least 32 bytes)
     * @return A new monitoring message
     * @throws IllegalArgumentException if the array is too short
     */
    public static MonitoringMessage decodePayload(byte[] bytes) {
        if (bytes.length < PAYLOAD_LENGTH) {
            throw new IllegalArgumentException("Monitoring payload too short: " + bytes.length);
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
        return "MonitoringMessage{" +
                "status=" + String.format("0x%02X", statusByte) +
                ", centerFreq=" + centerFrequencyHz + "Hz" +
                ", carrierOffset=" + (isCarrierOffsetAvailable() ? carrierOffsetHz + "Hz" : "unavailable") +
                ", snr=" + (isSnrAvailable() ? snrDb + "dB" : "unavailable") +
                ", decoded=" + totalDecodedFrames +
                ", errors=" + totalErrorFrames +
                ", good=" + totalGoodFrames +
                ", accepted=" + framesAccepted +
                '}';
    }
}
