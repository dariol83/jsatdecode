package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TcpMessageEncoder} and {@link TcpMessageDecoder}.
 */
public final class TcpMessageEncoderDecoderTest {

    @Test
    public void testEncodeDecodeFrameBatch() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        // Create some frame records
        List<FrameRecord> frames = new ArrayList<>();
        frames.add(new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, new byte[] {1, 2, 3}));
        frames.add(new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 2000L, new byte[] {4, 5, 6}));
        
        byte[] encoded = encoder.encodeFrameBatch(frames);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        
        // Decode
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertNotNull(message);
        assertTrue(message.isFrameBatch());
        assertFalse(message.isMonitoring());
        assertFalse(message.isEvent());
        assertEquals(frames.size(), message.frames().size());
        
        // Check frame records
        for (int i = 0; i < frames.size(); i++) {
            FrameRecord original = frames.get(i);
            FrameRecord decoded = message.frames().get(i);
            assertEquals(original.quality(), decoded.quality());
            assertEquals(original.timestampNanos(), decoded.timestampNanos());
            assertTrue(java.util.Arrays.equals(original.frameData(), decoded.frameData()));
        }
    }

    @Test
    public void testEncodeDecodeSingleFrame() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        FrameRecord frame = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, new byte[] {1, 2, 3, 4, 5});
        byte[] encoded = encoder.encodeFrameBatch(frame);
        assertNotNull(encoded);
        
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertTrue(message.isFrameBatch());
        assertEquals(1, message.frames().size());
        
        FrameRecord decoded = message.frames().get(0);
        assertEquals(frame.quality(), decoded.quality());
        assertEquals(frame.timestampNanos(), decoded.timestampNanos());
        assertTrue(java.util.Arrays.equals(frame.frameData(), decoded.frameData()));
    }

    @Test
    public void testEncodeDecodeMonitoring() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        MonitoringMessage monitoring = new MonitoringMessage(
                TcpConstants.encodeStatus(1, 1, 1, 1),
                1000000L,
                1000,
                25.5f,
                100,
                10,
                90,
                95
        );
        
        byte[] encoded = encoder.encodeMonitoring(monitoring);
        assertNotNull(encoded);
        assertEquals(40, encoded.length);
        
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertTrue(message.isMonitoring());
        assertFalse(message.isFrameBatch());
        assertFalse(message.isEvent());
        assertNotNull(message.monitoring());
        
        MonitoringMessage decoded = message.monitoring();
        assertEquals(monitoring.statusByte(), decoded.statusByte());
        assertEquals(monitoring.centerFrequencyHz(), decoded.centerFrequencyHz());
        assertEquals(monitoring.carrierOffsetHz(), decoded.carrierOffsetHz());
        assertEquals(monitoring.snrDb(), decoded.snrDb(), 0.001f);
        assertEquals(monitoring.totalDecodedFrames(), decoded.totalDecodedFrames());
        assertEquals(monitoring.totalErrorFrames(), decoded.totalErrorFrames());
        assertEquals(monitoring.totalGoodFrames(), decoded.totalGoodFrames());
        assertEquals(monitoring.framesAccepted(), decoded.framesAccepted());
    }

    @Test
    public void testEncodeDecodeEvent() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        EventMessage event = new EventMessage(
                TcpConstants.EVENT_DECODER_STARTED,
                1000000L,
                TcpConstants.encodeStatus(1, 1, 1, 1),
                1000000L
        );
        
        byte[] encoded = encoder.encodeEvent(event);
        assertNotNull(encoded);
        assertEquals(25, encoded.length);
        
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertTrue(message.isEvent());
        assertFalse(message.isFrameBatch());
        assertFalse(message.isMonitoring());
        assertNotNull(message.event());
        
        EventMessage decoded = message.event();
        assertEquals(event.eventCode(), decoded.eventCode());
        assertEquals(event.timestampNanos(), decoded.timestampNanos());
        assertEquals(event.statusByte(), decoded.statusByte());
        assertEquals(event.centerFrequencyHz(), decoded.centerFrequencyHz());
    }

    @Test
    public void testEncodeMonitoringWithCreate() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        byte[] encoded = encoder.encodeMonitoring(
                new eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot(
                        System.nanoTime(),
                        java.util.Map.of(
                                "carrier_state", 1,
                                "symbol_state", 1,
                                "frame_state", 1,
                                "center_frequency_hz", 1000000L,
                                "carrier_offset_hz", 1000,
                                "snr_db", 25.5,
                                "total_decoded_frames", 100L,
                                "total_error_frames", 10L,
                                "total_good_frames", 90L,
                                "frames_accepted", 95L
                        )
                ),
                TcpConstants.encodeStatus(1, 1, 1, 1),
                1000000L
        );
        
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertTrue(message.isMonitoring());
    }

    @Test
    public void testEncodeEventWithCreate() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        byte[] encoded = encoder.encodeEvent(
                new eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent(
                        System.nanoTime(),
                        eu.dariolucia.jsatdecode.api.distribution.EventSeverity.INFO,
                        "test",
                        "decoder.started",
                        "Test event",
                        java.util.Map.of()
                ),
                TcpConstants.encodeStatus(1, 1, 1, 1),
                1000000L
        );
        
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
    }

    @Test
    public void testEncodeFrameBatchFromAnnotated() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        List<eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame> frames = new ArrayList<>();
        frames.add(new eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame(
                new byte[] {1, 2, 3}, 1000L, java.util.Map.of()
        ));
        frames.add(new eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame(
                new byte[] {4, 5, 6}, 2000L, java.util.Map.of()
        ));
        
        byte[] encoded = encoder.encodeFrameBatchFromAnnotated(frames, TcpConstants.FRAME_QUALITY_GOOD);
        assertNotNull(encoded);
        assertTrue(encoded.length > 0);
        
        TcpMessage message = TcpMessageDecoder.decodeSingle(encoded);
        assertTrue(message.isFrameBatch());
        assertEquals(2, message.frames().size());
    }

    @Test
    public void testDecoderFeedMultipleMessages() {
        TcpMessageDecoder decoder = new TcpMessageDecoder(1048576);
        
        // Encode multiple messages
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        
        byte[] frameMsg = encoder.encodeFrameBatch(
                new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, new byte[] {1, 2, 3})
        );
        byte[] monitoringMsg = encoder.encodeMonitoring(
                new MonitoringMessage(
                        (byte) 0, 1000000L, 0, 0.0f, 0, 0, 0, 0
                )
        );
        byte[] eventMsg = encoder.encodeEvent(
                new EventMessage((byte) 0, 1000L, (byte) 0, 1000000L)
        );
        
        // Feed all messages at once
        byte[] allMessages = new byte[frameMsg.length + monitoringMsg.length + eventMsg.length];
        System.arraycopy(frameMsg, 0, allMessages, 0, frameMsg.length);
        System.arraycopy(monitoringMsg, 0, allMessages, frameMsg.length, monitoringMsg.length);
        System.arraycopy(eventMsg, 0, allMessages, frameMsg.length + monitoringMsg.length, eventMsg.length);
        
        decoder.feed(allMessages);
        
        // Should get all three messages
        assertTrue(decoder.hasNext());
        TcpMessage msg1 = decoder.poll();
        assertNotNull(msg1);
        assertTrue(msg1.isFrameBatch());
        
        assertTrue(decoder.hasNext());
        TcpMessage msg2 = decoder.poll();
        assertNotNull(msg2);
        assertTrue(msg2.isMonitoring());
        
        assertTrue(decoder.hasNext());
        TcpMessage msg3 = decoder.poll();
        assertNotNull(msg3);
        assertTrue(msg3.isEvent());
        
        assertFalse(decoder.hasNext());
        assertNull(decoder.poll());
    }

    @Test
    public void testDecoderFeedPartial() {
        TcpMessageDecoder decoder = new TcpMessageDecoder(1048576);
        
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        byte[] fullMessage = encoder.encodeMonitoring(
                new MonitoringMessage((byte) 0, 1000000L, 0, 0.0f, 0, 0, 0, 0)
        );
        
        // Feed first half
        int half = fullMessage.length / 2;
        decoder.feed(java.util.Arrays.copyOf(fullMessage, half));
        assertFalse(decoder.hasNext());
        
        // Feed second half
        decoder.feed(java.util.Arrays.copyOfRange(fullMessage, half, fullMessage.length));
        assertTrue(decoder.hasNext());
        
        TcpMessage message = decoder.poll();
        assertNotNull(message);
        assertTrue(message.isMonitoring());
    }

    @Test
    public void testDecoderInvalidMagic() {
        TcpMessageDecoder decoder = new TcpMessageDecoder(1048576);
        
        // Create a message with invalid magic
        byte[] invalid = new byte[40];
        invalid[0] = (byte) 0xFF; // Invalid magic
        invalid[1] = TcpConstants.TYPE_MONITORING;
        invalid[2] = 0;
        invalid[3] = 0;
        invalid[4] = 0;
        invalid[5] = 0;
        invalid[6] = 0;
        invalid[7] = 40; // Length
        
        assertThrows(ProtocolException.class, () -> {
            decoder.feed(invalid);
            decoder.poll();
        });
    }

    @Test
    public void testDecoderReset() {
        TcpMessageDecoder decoder = new TcpMessageDecoder(1048576);
        
        TcpMessageEncoder encoder = new TcpMessageEncoder(1, 1048576);
        byte[] message = encoder.encodeMonitoring(
                new MonitoringMessage((byte) 0, 1000000L, 0, 0.0f, 0, 0, 0, 0)
        );
        
        decoder.feed(message);
        assertTrue(decoder.hasNext());
        
        decoder.reset();
        assertFalse(decoder.hasNext());
        assertNull(decoder.poll());
    }

    @Test
    public void testEncoderInvalidSourceId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageEncoder(-1, 1048576);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageEncoder(65536, 1048576);
        });
    }

    @Test
    public void testEncoderInvalidMaxMessageBytes() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageEncoder(1, 7);
        });
    }
}
