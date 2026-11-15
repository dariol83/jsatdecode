package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.util.List;
import java.util.Objects;

/**
 * Represents a decoded JTDP v1 message.
 *
 * <p>A message can be one of three types:
 * <ul>
 *   <li>Frame batch: contains one or more frame records</li>
 *   <li>Monitoring: contains a monitoring snapshot</li>
 *   <li>Event: contains an event notification</li>
 * </ul>
 *
 * <p>Use the type-checking methods to determine what kind of message this is,
 * then use the appropriate getter method to access the payload.
 *
 * @param header  The message header
 * @param frames  The frame records (for frame batch messages)
 * @param monitoring The monitoring message (for monitoring messages)
 * @param event  The event message (for event messages)
 * @param rawPayload The raw payload bytes (for unknown message types)
 */
public record TcpMessage(
        TcpMessageHeader header,
        List<FrameRecord> frames,
        MonitoringMessage monitoring,
        EventMessage event,
        byte[] rawPayload
) {

    /**
     * Creates a frame batch message.
     *
     * @param header  The message header
     * @param frames  The list of frame records
     * @throws IllegalArgumentException if header type is not frame batch or frames is empty
     */
    public TcpMessage(TcpMessageHeader header, List<FrameRecord> frames) {
        this(header, frames, null, null, null);
        Objects.requireNonNull(header, "header cannot be null");
        if (!header.isFrameBatch()) {
            throw new IllegalArgumentException("Header type must be frame batch");
        }
        Objects.requireNonNull(frames, "frames cannot be null");
        if (frames.isEmpty()) {
            throw new IllegalArgumentException("Frame batch cannot be empty");
        }
    }

    /**
     * Creates a monitoring message.
     *
     * @param header      The message header
     * @param monitoring  The monitoring message
     * @throws IllegalArgumentException if header type is not monitoring
     */
    public TcpMessage(TcpMessageHeader header, MonitoringMessage monitoring) {
        this(header, null, monitoring, null, null);
        Objects.requireNonNull(header, "header cannot be null");
        if (!header.isMonitoring()) {
            throw new IllegalArgumentException("Header type must be monitoring");
        }
        Objects.requireNonNull(monitoring, "monitoring cannot be null");
    }

    /**
     * Creates an event message.
     *
     * @param header  The message header
     * @param event   The event message
     * @throws IllegalArgumentException if header type is not event
     */
    public TcpMessage(TcpMessageHeader header, EventMessage event) {
        this(header, null, null, event, null);
        Objects.requireNonNull(header, "header cannot be null");
        if (!header.isEvent()) {
            throw new IllegalArgumentException("Header type must be event");
        }
        Objects.requireNonNull(event, "event cannot be null");
    }

    /**
     * Creates a message with raw payload (for reserved/unknown types).
     *
     * @param header     The message header
     * @param rawPayload The raw payload bytes
     * @throws IllegalArgumentException if header or rawPayload is null
     */
    public TcpMessage(TcpMessageHeader header, byte[] rawPayload) {
        this(header, null, null, null, rawPayload);
        Objects.requireNonNull(header, "header cannot be null");
        Objects.requireNonNull(rawPayload, "rawPayload cannot be null");
    }

    /**
     * Checks if this is a frame batch message.
     *
     * @return true if this is a frame batch message
     */
    public boolean isFrameBatch() {
        return header.isFrameBatch();
    }

    /**
     * Checks if this is a monitoring message.
     *
     * @return true if this is a monitoring message
     */
    public boolean isMonitoring() {
        return header.isMonitoring();
    }

    /**
     * Checks if this is an event message.
     *
     * @return true if this is an event message
     */
    public boolean isEvent() {
        return header.isEvent();
    }

    /**
     * Checks if this message has a raw payload (unknown/reserved type).
     *
     * @return true if this message has raw payload
     */
    public boolean hasRawPayload() {
        return rawPayload != null;
    }

    /**
     * Gets the source identifier from the header.
     *
     * @return The source identifier
     */
    public int getSourceId() {
        return header.sourceId();
    }

    /**
     * Gets the message length from the header.
     *
     * @return The total message length in bytes
     */
    public int getLength() {
        return header.length();
    }

    /**
     * Gets the message type from the header.
     *
     * @return The message type
     */
    public byte getType() {
        return header.type();
    }

    /**
     * Gets the payload length.
     *
     * @return The payload length in bytes
     */
    public int getPayloadLength() {
        return header.payloadLength();
    }

    @Override
    public String toString() {
        if (isFrameBatch()) {
            return "TcpMessage[FRAME_BATCH, source=" + header.sourceId() + 
                    ", length=" + header.length() + 
                    ", frames=" + frames.size() + "]";
        } else if (isMonitoring()) {
            return "TcpMessage[MONITORING, source=" + header.sourceId() + 
                    ", length=" + header.length() + 
                    ", monitoring=" + monitoring + "]";
        } else if (isEvent()) {
            return "TcpMessage[EVENT, source=" + header.sourceId() + 
                    ", length=" + header.length() + 
                    ", event=" + event + "]";
        } else {
            return "TcpMessage[RAW, source=" + header.sourceId() + 
                    ", length=" + header.length() + 
                    ", type=" + (header.type() & 0xFF) + "]";
        }
    }
}
