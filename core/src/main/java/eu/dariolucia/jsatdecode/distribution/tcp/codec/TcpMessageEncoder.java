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
 * byte[] frameBatch = encoder.encodeFrameBatch(frames, quality);
 * byte[] monitoring = encoder.encodeMonitoring(snapshot);
 * byte[] event = encoder.encodeEvent(event);
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
        if (maxMessageBytes < TcpConstants.MIN_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Max message bytes must be at least " +
                    TcpConstants.MIN_MESSAGE_LENGTH + ": " + maxMessageBytes);
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
     * <p>The frames are concatenated into a single message. If the total size would
     * exceed maxMessageBytes, only as many frames as possible are included, starting
     * from the first frame. At least one frame is always included if the input is non-empty.
     *
     * @param frames  The list of frame records to encode
     * @return A byte array containing the encoded message, or null if no frames fit
     * @throws IllegalArgumentException if frames list is null
     */
    public byte[] encodeFrameBatch(List<FrameRecord> frames) {
        Objects.requireNonNull(frames, "frames cannot be null");

        if (frames.isEmpty()) {
            return null; // No empty frame batches
        }

        // Calculate total payload size
        int totalPayloadSize = 0;
        int framesToInclude = 0;
        for (FrameRecord frame : frames) {
            int frameSize = frame.recordLength();
            if (totalPayloadSize + frameSize > maxMessageBytes - TcpConstants.HEADER_LENGTH) {
                break;
            }
            totalPayloadSize += frameSize;
            framesToInclude++;
        }

        if (framesToInclude == 0) {
            return null; // First frame alone exceeds max size
        }

        int messageLength = TcpConstants.HEADER_LENGTH + totalPayloadSize;
        ByteBuffer buffer = ByteBuffer.allocate(messageLength);
        buffer.order(ByteOrder.BIG_ENDIAN);

        // Write header
        TcpMessageHeader header = TcpMessageHeader.frameBatch(sourceId, totalPayloadSize);
        header.encode(buffer);

        // Write frame records
        for (int i = 0; i < framesToInclude; i++) {
            frames.get(i).encode(buffer);
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

        // Extract values from snapshot
        long totalDecodedFrames = getLongValue(snapshot, "total_decoded_frames", 0L);
        long totalErrorFrames = getLongValue(snapshot, "total_error_frames", 0L);
        long totalGoodFrames = getLongValue(snapshot, "total_good_frames", 0L);
        long framesAccepted = getLongValue(snapshot, "frames_accepted", 0L);

        // Get carrier offset and SNR
        int carrierOffsetHz = TcpConstants.CARRIER_OFFSET_UNAVAILABLE;
        float snrDb = Float.NaN;

        Object carrierOffsetObj = snapshot.values().get("carrier_offset_hz");
        if (carrierOffsetObj instanceof Number) {
            carrierOffsetHz = ((Number) carrierOffsetObj).intValue();
        }

        Object snrObj = snapshot.values().get("snr_db");
        if (snrObj instanceof Number) {
            snrDb = ((Number) snrObj).floatValue();
        }

        // Check SNR availability
        int snrAvailable = Float.isFinite(snrDb) ? TcpConstants.SNR_AVAILABLE : TcpConstants.SNR_UNAVAILABLE;

        // Extract state values
        int carrierState = getIntValue(snapshot, "carrier_state", TcpConstants.STATE_UNKNOWN);
        int symbolState = getIntValue(snapshot, "symbol_state", TcpConstants.STATE_UNKNOWN);
        int frameState = getIntValue(snapshot, "frame_state", TcpConstants.STATE_UNKNOWN);

        // Encode status byte
        byte encodedStatus = TcpConstants.encodeStatus(carrierState, symbolState, frameState, snrAvailable);

        // Create monitoring message
        MonitoringMessage msg = MonitoringMessage.create(
                carrierState, symbolState, frameState, snrAvailable,
                centerFrequencyHz, carrierOffsetHz, snrDb,
                totalDecodedFrames, totalErrorFrames, totalGoodFrames, framesAccepted
        );

        return encodeMonitoring(msg);
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
        byte eventCode = mapEventCode(event.type());
        if (eventCode == -1) {
            return null; // Unmappable event type
        }

        // Create event message
        EventMessage msg = new EventMessage(eventCode, event.timestampNanos(), statusByte, centerFrequencyHz);
        return encodeEvent(msg);
    }

    private long getLongValue(eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot snapshot,
                               String key, long defaultValue) {
        Object value = snapshot.values().get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return defaultValue;
    }

    private int getIntValue(eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot snapshot,
                             String key, int defaultValue) {
        Object value = snapshot.values().get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    /**
     * Maps a JSatDecode event type to a JTDP v1 event code.
     *
     * @param type The event type string
     * @return The event code, or -1 if not mappable
     */
    private byte mapEventCode(String type) {
        if (type == null) {
            return -1;
        }
        switch (type.toLowerCase()) {
            case "decoder.started":
            case "decoder_started":
                return TcpConstants.EVENT_DECODER_STARTED;
            case "decoder.stopped":
            case "decoder_stopped":
                return TcpConstants.EVENT_DECODER_STOPPED;
            case "carrier.locked":
            case "carrier.unlocked":
            case "carrier_state_changed":
                return TcpConstants.EVENT_CARRIER_STATE_CHANGED;
            case "symbol.locked":
            case "symbol.unlocked":
            case "symbol_state_changed":
                return TcpConstants.EVENT_SYMBOL_STATE_CHANGED;
            case "frame.locked":
            case "frame.unlocked":
            case "frame_state_changed":
                return TcpConstants.EVENT_FRAME_STATE_CHANGED;
            case "decoder.error":
            case "decoder_error":
                return TcpConstants.EVENT_DECODER_ERROR;
            case "distribution.overrun":
            case "distribution_overrun":
                return TcpConstants.EVENT_DISTRIBUTION_OVERRUN;
            default:
                return -1; // Unmappable
        }
    }

    /**
     * Creates a frame batch message from a list of AnnotatedFrames.
     *
     * @param frames  The list of annotated frames
     * @param quality The quality to assign to all frames
     * @return A byte array containing the encoded message, or null if no frames fit
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
