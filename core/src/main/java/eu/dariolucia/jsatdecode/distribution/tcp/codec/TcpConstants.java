package eu.dariolucia.jsatdecode.distribution.tcp.codec;

/**
 * Constants for the JTDP v1 TCP Binary Distribution Protocol.
 *
 * <p>This class defines all protocol constants including magic bytes, message types,
 * fixed message sizes, and field offsets as specified in the protocol documentation.
 *
 * <h2>Message Header</h2>
 * <pre>
 *   Offset  Bytes  Type       Field
 *   0       1      uint8      Magic (0xA5)
 *   1       1      uint8      Type (0-2)
 *   2       2      uint16     Source ID
 *   4       4      uint32     Total message length (including header)
 * </pre>
 *
 * <h2>Message Types</h2>
 * <ul>
 *   <li>0 - Frame batch</li>
 *   <li>1 - Monitoring snapshot</li>
 *   <li>2 - Event notification</li>
 * </ul>
 *
 * @see TcpMessageHeader
 * @see TcpMessageDecoder
 * @see TcpMessageEncoder
 */
public final class TcpConstants {

    /**
     * Protocol magic byte that identifies the start of a message.
     * All messages MUST start with this byte.
     */
    public static final byte MAGIC = (byte) 0xA5;

    /**
     * Length of the message header in bytes.
     * All messages have exactly 8 bytes of header.
     */
    public static final int HEADER_LENGTH = 8;

    /**
     * Message type for frame batch messages.
     * Type 0 messages contain one or more frame records.
     */
    public static final byte TYPE_FRAME_BATCH = 0;

    /**
     * Message type for monitoring snapshot messages.
     * Type 1 messages contain a monitoring status snapshot.
     */
    public static final byte TYPE_MONITORING = 1;

    /**
     * Message type for event notification messages.
     * Type 2 messages contain a runtime event notification.
     */
    public static final byte TYPE_EVENT = 2;

    /**
     * Minimum valid message length (header only).
     * Any message with length &lt; 8 is invalid.
     */
    public static final int MIN_MESSAGE_LENGTH = HEADER_LENGTH;

    /**
     * Fixed size of a monitoring message in bytes (including header).
     * Monitoring messages are always exactly 40 bytes.
     */
    public static final int MONITORING_MESSAGE_LENGTH = 40;

    /**
     * Fixed size of an event message in bytes (including header).
     * Event messages are always exactly 25 bytes.
     */
    public static final int EVENT_MESSAGE_LENGTH = 25;

    /**
     * Fixed overhead per frame record in bytes.
     * Each frame record has 1 byte quality + 8 bytes timestamp + 4 bytes length = 13 bytes.
     */
    public static final int FRAME_RECORD_OVERHEAD = 13;

    /**
     * Sentinel value for unavailable carrier offset.
     * Value: -2,147,483,648 (0x80000000)
     */
    public static final int CARRIER_OFFSET_UNAVAILABLE = Integer.MIN_VALUE;

    // Frame quality constants

    /**
     * Frame quality: good.
     * Frame was successfully decoded and validated.
     */
    public static final byte FRAME_QUALITY_GOOD = 0;

    /**
     * Frame quality: bad.
     * Frame contains errors or failed validation.
     */
    public static final byte FRAME_QUALITY_BAD = 1;

    /**
     * Frame quality: unknown.
     * Frame quality cannot be determined.
     */
    public static final byte FRAME_QUALITY_UNKNOWN = 2;

    // Status byte bit positions and masks

    /**
     * Bit mask for carrier state (bits 7-6).
     */
    public static final int STATUS_CARRIER_MASK = 0xC0;

    /**
     * Bit shift for carrier state.
     */
    public static final int STATUS_CARRIER_SHIFT = 6;

    /**
     * Bit mask for symbol state (bits 5-4).
     */
    public static final int STATUS_SYMBOL_MASK = 0x30;

    /**
     * Bit shift for symbol state.
     */
    public static final int STATUS_SYMBOL_SHIFT = 4;

    /**
     * Bit mask for frame state (bits 3-2).
     */
    public static final int STATUS_FRAME_MASK = 0x0C;

    /**
     * Bit shift for frame state.
     */
    public static final int STATUS_FRAME_SHIFT = 2;

    /**
     * Bit mask for SNR availability (bits 1-0).
     */
    public static final int STATUS_SNR_MASK = 0x03;

    /**
     * Bit shift for SNR availability.
     */
    public static final int STATUS_SNR_SHIFT = 0;

    // State values

    /**
     * State value: searching.
     */
    public static final int STATE_SEARCHING = 0;

    /**
     * State value: locked.
     */
    public static final int STATE_LOCKED = 1;

    /**
     * State value: unlocked.
     */
    public static final int STATE_UNLOCKED = 2;

    /**
     * State value: unknown.
     */
    public static final int STATE_UNKNOWN = 3;

    // SNR availability values

    /**
     * SNR availability: unavailable.
     */
    public static final int SNR_UNAVAILABLE = 0;

    /**
     * SNR availability: available.
     */
    public static final int SNR_AVAILABLE = 1;

    // Event codes

    /**
     * Event code: decoder started.
     */
    public static final byte EVENT_DECODER_STARTED = 0;

    /**
     * Event code: decoder stopped.
     */
    public static final byte EVENT_DECODER_STOPPED = 1;

    /**
     * Event code: carrier state changed.
     */
    public static final byte EVENT_CARRIER_STATE_CHANGED = 2;

    /**
     * Event code: symbol state changed.
     */
    public static final byte EVENT_SYMBOL_STATE_CHANGED = 3;

    /**
     * Event code: frame state changed.
     */
    public static final byte EVENT_FRAME_STATE_CHANGED = 4;

    /**
     * Event code: decoder error.
     */
    public static final byte EVENT_DECODER_ERROR = 5;

    /**
     * Event code: distribution overrun.
     */
    public static final byte EVENT_DISTRIBUTION_OVERRUN = 6;

    // Reserved type range: 0x03-0xEF
    /**
     * First reserved message type.
     */
    public static final byte TYPE_RESERVED_MIN = 0x03;

    /**
     * Last reserved message type.
     */
    public static final byte TYPE_RESERVED_MAX = (byte) 0xEF;

    // Future protocol control range: 0xF0-0xFF
    /**
     * First future protocol control message type.
     */
    public static final byte TYPE_FUTURE_MIN = (byte) 0xF0;

    /**
     * Last future protocol control message type.
     */
    public static final byte TYPE_FUTURE_MAX = (byte) 0xFF;

    // Reserved event code range
    /**
     * First reserved event code.
     */
    public static final byte EVENT_RESERVED_MIN = 7;

    /**
     * Last reserved event code.
     */
    public static final byte EVENT_RESERVED_MAX = (byte) 255;

    /**
     * Private constructor to prevent instantiation.
     */
    private TcpConstants() {
        // Utility class - no instantiation
    }

    /**
     * Checks if a message type is valid for v1.
     *
     * @param type the message type to check
     * @return true if the type is valid (0, 1, or 2)
     */
    public static boolean isValidType(byte type) {
        return type == TYPE_FRAME_BATCH || type == TYPE_MONITORING || type == TYPE_EVENT;
    }

    /**
     * Checks if a message type is in the reserved range.
     *
     * @param type the message type to check
     * @return true if the type is in the reserved range (0x03-0xEF)
     */
    public static boolean isReservedType(byte type) {
        return (type & 0xFF) >= (TYPE_RESERVED_MIN & 0xFF) && (type & 0xFF) <= (TYPE_RESERVED_MAX & 0xFF);
    }

    /**
     * Checks if a message type is in the future protocol control range.
     *
     * @param type the message type to check
     * @return true if the type is in the future range (0xF0-0xFF)
     */
    public static boolean isFutureType(byte type) {
        return (type & 0xFF) >= (TYPE_FUTURE_MIN & 0xFF);
    }

    /**
     * Checks if an event code is valid for v1.
     *
     * @param code the event code to check
     * @return true if the code is valid (0-6)
     */
    public static boolean isValidEventCode(byte code) {
        return (code & 0xFF) <= 6;
    }

    /**
     * Checks if an event code is in the reserved range.
     *
     * @param code the event code to check
     * @return true if the code is reserved (7-255)
     */
    public static boolean isReservedEventCode(byte code) {
        return (code & 0xFF) >= 7;
    }

    /**
     * Encodes a status byte from individual state values.
     *
     * @param carrierState   carrier state (0-3)
     * @param symbolState    symbol state (0-3)
     * @param frameState     frame state (0-3)
     * @param snrAvailable   SNR availability (0 or 1)
     * @return encoded status byte
     */
    public static byte encodeStatus(int carrierState, int symbolState, int frameState, int snrAvailable) {
        return (byte) ((carrierState << STATUS_CARRIER_SHIFT) |
                (symbolState << STATUS_SYMBOL_SHIFT) |
                (frameState << STATUS_FRAME_SHIFT) |
                (snrAvailable << STATUS_SNR_SHIFT));
    }

    /**
     * Decodes the carrier state from a status byte.
     *
     * @param statusByte the status byte
     * @return carrier state (0-3)
     */
    public static int decodeCarrierState(byte statusByte) {
        return (statusByte & STATUS_CARRIER_MASK) >>> STATUS_CARRIER_SHIFT;
    }

    /**
     * Decodes the symbol state from a status byte.
     *
     * @param statusByte the status byte
     * @return symbol state (0-3)
     */
    public static int decodeSymbolState(byte statusByte) {
        return (statusByte & STATUS_SYMBOL_MASK) >>> STATUS_SYMBOL_SHIFT;
    }

    /**
     * Decodes the frame state from a status byte.
     *
     * @param statusByte the status byte
     * @return frame state (0-3)
     */
    public static int decodeFrameState(byte statusByte) {
        return (statusByte & STATUS_FRAME_MASK) >>> STATUS_FRAME_SHIFT;
    }

    /**
     * Decodes the SNR availability from a status byte.
     *
     * @param statusByte the status byte
     * @return SNR availability (0 or 1)
     */
    public static int decodeSnrAvailable(byte statusByte) {
        return (statusByte & STATUS_SNR_MASK) >>> STATUS_SNR_SHIFT;
    }
}
