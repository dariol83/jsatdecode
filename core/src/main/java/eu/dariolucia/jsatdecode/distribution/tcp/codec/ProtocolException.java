package eu.dariolucia.jsatdecode.distribution.tcp.codec;

/**
 * Exception thrown when a protocol violation is detected.
 *
 * <p>This exception is thrown by the message decoder when it encounters:
 * <ul>
 *   <li>Invalid magic byte</li>
 *   <li>Invalid message length</li>
 *   <li>Invalid message type</li>
 *   <li>Malformed message payload</li>
 *   <li>Truncated message</li>
 * </ul>
 *
 * <p>The exception contains a detailed error message describing the protocol violation.
 * In most cases, the connection should be closed when this exception is thrown.
 */
public class ProtocolException extends RuntimeException {

    /**
     * Creates a new protocol exception with the specified message.
     *
     * @param message The error message describing the protocol violation
     */
    public ProtocolException(String message) {
        super(message);
    }

    /**
     * Creates a new protocol exception with the specified message and cause.
     *
     * @param message The error message describing the protocol violation
     * @param cause   The underlying cause of this exception
     */
    public ProtocolException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates a new protocol exception from a cause.
     *
     * @param cause The underlying cause of this exception
     */
    public ProtocolException(Throwable cause) {
        super(cause);
    }

    /**
     * Creates a protocol exception for an invalid magic byte.
     *
     * @param expected The expected magic byte
     * @param actual   The actual magic byte received
     * @return A new protocol exception
     */
    public static ProtocolException invalidMagic(byte expected, byte actual) {
        return new ProtocolException(
                String.format("Invalid magic byte: expected 0x%02X, got 0x%02X",
                        expected & 0xFF, actual & 0xFF)
        );
    }

    /**
     * Creates a protocol exception for an invalid message length.
     *
     * @param length   The invalid length value
     * @param expected The expected length or description
     * @return A new protocol exception
     */
    public static ProtocolException invalidLength(int length, String expected) {
        return new ProtocolException(
                String.format("Invalid message length: %d, %s", length, expected)
        );
    }

    /**
     * Creates a protocol exception for a message that exceeds the maximum size.
     *
     * @param length     The message length
     * @param maxLength  The maximum allowed length
     * @return A new protocol exception
     */
    public static ProtocolException messageTooLarge(int length, int maxLength) {
        return new ProtocolException(
                String.format("Message too large: %d bytes exceeds maximum %d bytes",
                        length, maxLength)
        );
    }

    /**
     * Creates a protocol exception for an invalid message type.
     *
     * @param type The invalid type value
     * @return A new protocol exception
     */
    public static ProtocolException invalidType(byte type) {
        return new ProtocolException(
                String.format("Invalid message type: 0x%02X", type & 0xFF)
        );
    }

    /**
     * Creates a protocol exception for a truncated message.
     *
     * @param expected The expected message length
     * @param actual   The actual bytes received
     * @return A new protocol exception
     */
    public static ProtocolException truncatedMessage(int expected, int actual) {
        return new ProtocolException(
                String.format("Truncated message: expected %d bytes, got %d bytes",
                        expected, actual)
        );
    }
}
