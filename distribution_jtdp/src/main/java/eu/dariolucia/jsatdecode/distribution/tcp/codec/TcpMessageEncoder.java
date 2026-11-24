package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Objects;

/**
 * Encodes JTDP v1 messages into byte buffers for transmission.
 *
 * <p>This class provides methods to encode frame batch, monitoring, and event messages
 * following the JTDP v1 wire format specification. All encoding uses network byte
 * order (big-endian) as required by the protocol.
 *
 * <p>Usage example:
 * <pre>{@code
 * TcpMessageEncoder encoder = new TcpMessageEncoder(sourceId, maxMessageBytes);
 * byte[] frameBatch = encoder.encodeFrameBatch(frames);
 * byte[] monitoring = encoder.encodeMonitoring(monitoringMessage);
 * byte[] event = encoder.encodeEvent(eventMessage);
 * }</pre>
 */
public final class TcpMessageEncoder {

    private final int sourceId;
    private final int maxMessageBytes;

    /**
     * Creates a new message encoder with the specified source identifier and maximum message size.
     *
     * @param sourceId         The antenna/decoder identifier (0-65535)
     * @param maxMessageBytes  The maximum allowed message size in bytes
     * @throws IllegalArgumentException if sourceId is out of range or maxMessageBytes is too small
     */
    public TcpMessageEncoder(int sourceId, int maxMessageBytes) {
        if (sourceId < 0 || sourceId > 65535) {
            throw new IllegalArgumentException("Source ID must be between 0 and 65535: " + sourceId);
        }
        if (maxMessageBytes < TcpConstants.MONITORING_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Max message bytes must be at least " +
                    TcpConstants.MONITORING_MESSAGE_LENGTH + ": " + maxMessageBytes);
        }
        this.sourceId = sourceId;
        this.maxMessageBytes = maxMessageBytes;
    }

    /**
     * Gets the source identifier used by this encoder.
     *
     * @return The source identifier
     */
    public int getSourceId() {
        return sourceId;
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
     * Encodes a frame batch message.
     *
     * <p>All supplied frames are encoded in order. An oversized batch is rejected as a whole.
     *
     * @param frames  The list of frame records to encode
     * @return A byte array containing the encoded message, or null for an empty list
     * @throws IllegalArgumentException if frames list is null
     */
    public byte[] encodeFrameBatch(List<FrameRecord> frames) {
        Objects.requireNonNull(frames, "frames cannot be null");

        if (frames.isEmpty()) {
            return null; // No empty frame batches
        }

        // Calculate total payload size
        long payloadSize = 0;
        for (FrameRecord frame : frames) {
            payloadSize += Objects.requireNonNull(frame, "frame").recordLength();
            if (payloadSize > maxMessageBytes - TcpConstants.HEADER_LENGTH) {
                throw new IllegalArgumentException("Frame batch exceeds maximum message size " + maxMessageBytes);
            }
        }
        int totalPayloadSize = (int) payloadSize;

        int messageLength = TcpConstants.HEADER_LENGTH + totalPayloadSize;
        ByteBuffer buffer = ByteBuffer.allocate(messageLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Write header
        TcpMessageHeader header = TcpMessageHeader.frameBatch(sourceId, totalPayloadSize);
        header.encode(buffer);

        // Write frame records
        for (FrameRecord frame : frames) {
            frame.encode(buffer);
        }

        return buffer.array();
    }

    /**
     * Encodes a single frame record as a frame batch message.
     *
     * @param frame  The frame record to encode
     * @return A byte array containing the encoded message
     * @throws IllegalArgumentException if frame is null
     */
    public byte[] encodeFrameBatch(FrameRecord frame) {
        Objects.requireNonNull(frame, "frame cannot be null");
        return encodeFrameBatch(List.of(frame));
    }

    /**
     * Encodes a monitoring snapshot message.
     *
     * @param message  The monitoring message to encode
     * @return A byte array containing the encoded message
     * @throws IllegalArgumentException if message is null
     */
    public byte[] encodeMonitoring(MonitoringMessage message) {
        Objects.requireNonNull(message, "message cannot be null");

        ByteBuffer buffer = ByteBuffer.allocate(TcpConstants.MONITORING_MESSAGE_LENGTH);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Write header
        TcpMessageHeader header = TcpMessageHeader.monitoring(sourceId);
        header.encode(buffer);

        // Write payload
        message.encodePayload(buffer);

        return buffer.array();
    }

    /**
     * Encodes an event message.
     *
     * @param message  The event message to encode
     * @return A byte array containing the encoded message
     * @throws IllegalArgumentException if message is null
     */
    public byte[] encodeEvent(EventMessage message) {
        Objects.requireNonNull(message, "message cannot be null");

        ByteBuffer buffer = ByteBuffer.allocate(TcpConstants.EVENT_MESSAGE_LENGTH);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Write header
        TcpMessageHeader header = TcpMessageHeader.event(sourceId);
        header.encode(buffer);

        // Write payload
        message.encodePayload(buffer);

        return buffer.array();
    }

    /**
     * Creates a monitoring message from a MonitoringSnapshot and encodes it.
     *
     * <p>This method extracts the relevant fields from a JSatDecode MonitoringSnapshot
     * and converts them to a JTDP v1 monitoring message format.
     *
     * @param snapshot  The monitoring snapshot to encode
     * @param statusByte The current status byte
     * @param centerFrequencyHz The center frequency in Hz
     * @return A byte array containing the encoded message
     * @throws IllegalArgumentException if snapshot is null
     */
    public byte[] encodeMonitoring(
            eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot snapshot,
            byte statusByte,
            long centerFrequencyHz) {

        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        return encodeMonitoring(snapshot, statusByte, centerFrequencyHz,
                TcpStatus.counter(snapshot, "distribution.tcp.frames_accepted", "frames_accepted"));
    }

    /** Encodes one consistent observation with the distributor's accepted-frame counter. */
    public byte[] encodeMonitoring(eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot snapshot,
                                   byte statusByte, long centerFrequencyHz, long framesAccepted) {
        Objects.requireNonNull(snapshot, "snapshot");
        float snr = TcpStatus.snr(snapshot);
        byte status = (byte) ((statusByte & 0xFC) | (Float.isFinite(snr) ? 1 : 0));
        return encodeMonitoring(new MonitoringMessage(status, centerFrequencyHz, TcpStatus.carrierOffset(snapshot), snr,
                TcpStatus.counter(snapshot, "frame.total", "total_decoded_frames"),
                TcpStatus.counter(snapshot, "frame.bad", "total_error_frames"),
                TcpStatus.counter(snapshot, "frame.good", "total_good_frames"), framesAccepted & 0xFFFFFFFFL));
    }

    /**
     * Creates an event message from a RuntimeEvent and encodes it.
     *
     * @param event  The runtime event to encode
     * @param statusByte The current status byte
     * @param centerFrequencyHz The center frequency in Hz
     * @return A byte array containing the encoded message, or null if event type is not mappable
     */
    public byte[] encodeEvent(
            eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent event,
            byte statusByte,
            long centerFrequencyHz) {

        Objects.requireNonNull(event, "event cannot be null");

        // Map event type to JTDP v1 event code
        byte eventCode = TcpStatus.mapEventCode(event.type());
        if (eventCode == -1) {
            return null; // Unmappable event type
        }

        // Create event message
        EventMessage msg = new EventMessage(eventCode, event.timestampNanos(),
                TcpStatus.eventStatus(event, statusByte), TcpStatus.eventCenterFrequency(event, centerFrequencyHz));
        return encodeEvent(msg);
    }

    /**
     * Creates a frame batch message from a list of AnnotatedFrames.
     *
     * @param frames  The list of annotated frames
     * @param quality The quality to assign to all frames
     * @return A byte array containing the encoded message, or null for an empty list
     */
    public byte[] encodeFrameBatchFromAnnotated(
            List<eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame> frames,
            byte quality) {

        Objects.requireNonNull(frames, "frames cannot be null");
        if (frames.isEmpty()) {
            return null;
        }

        // Convert to FrameRecords
        java.util.List<FrameRecord> records = frames.stream()
                .map(f -> FrameRecord.fromAnnotatedFrame(f, quality))
                .toList();

        return encodeFrameBatch(records);
    }
}
