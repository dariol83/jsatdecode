package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TcpMessageHeader}.
 */
public final class TcpMessageHeaderTest {

    @Test
    public void testFrameBatchHeader() {
        TcpMessageHeader header = TcpMessageHeader.frameBatch(1, 100);
        assertEquals(TcpConstants.MAGIC, header.magic());
        assertEquals(TcpConstants.TYPE_FRAME_BATCH, header.type());
        assertEquals(1, header.sourceId());
        assertEquals(8 + 100, header.length());
        assertEquals(100, header.payloadLength());
        assertTrue(header.isFrameBatch());
        assertFalse(header.isMonitoring());
        assertFalse(header.isEvent());
    }

    @Test
    public void testMonitoringHeader() {
        TcpMessageHeader header = TcpMessageHeader.monitoring(2);
        assertEquals(TcpConstants.MAGIC, header.magic());
        assertEquals(TcpConstants.TYPE_MONITORING, header.type());
        assertEquals(2, header.sourceId());
        assertEquals(40, header.length());
        assertEquals(32, header.payloadLength());
        assertFalse(header.isFrameBatch());
        assertTrue(header.isMonitoring());
        assertFalse(header.isEvent());
    }

    @Test
    public void testEventHeader() {
        TcpMessageHeader header = TcpMessageHeader.event(3);
        assertEquals(TcpConstants.MAGIC, header.magic());
        assertEquals(TcpConstants.TYPE_EVENT, header.type());
        assertEquals(3, header.sourceId());
        assertEquals(25, header.length());
        assertEquals(17, header.payloadLength());
        assertFalse(header.isFrameBatch());
        assertFalse(header.isMonitoring());
        assertTrue(header.isEvent());
    }

    @Test
    public void testInvalidMagic() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageHeader((byte) 0xFF, (byte) 0, 1, 8);
        });
    }

    @Test
    public void testInvalidSourceId() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageHeader(TcpConstants.MAGIC, (byte) 0, 65536, 8);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageHeader(TcpConstants.MAGIC, (byte) 0, -1, 8);
        });
    }

    @Test
    public void testInvalidLength() {
        assertThrows(IllegalArgumentException.class, () -> {
            new TcpMessageHeader(TcpConstants.MAGIC, (byte) 0, 1, 7);
        });
    }

    @Test
    public void testEncodeDecode() {
        TcpMessageHeader original = TcpMessageHeader.frameBatch(123, 256);
        byte[] bytes = original.toBytes();
        assertEquals(8, bytes.length);

        TcpMessageHeader decoded = TcpMessageHeader.decode(bytes);
        assertEquals(original.magic(), decoded.magic());
        assertEquals(original.type(), decoded.type());
        assertEquals(original.sourceId(), decoded.sourceId());
        assertEquals(original.length(), decoded.length());
    }

    @Test
    public void testEncodeDecodeMonitoring() {
        TcpMessageHeader original = TcpMessageHeader.monitoring(456);
        byte[] bytes = original.toBytes();

        TcpMessageHeader decoded = TcpMessageHeader.decode(bytes);
        assertEquals(original.magic(), decoded.magic());
        assertEquals(original.type(), decoded.type());
        assertEquals(original.sourceId(), decoded.sourceId());
        assertEquals(original.length(), decoded.length());
    }

    @Test
    public void testEncodeDecodeEvent() {
        TcpMessageHeader original = TcpMessageHeader.event(789);
        byte[] bytes = original.toBytes();

        TcpMessageHeader decoded = TcpMessageHeader.decode(bytes);
        assertEquals(original.magic(), decoded.magic());
        assertEquals(original.type(), decoded.type());
        assertEquals(original.sourceId(), decoded.sourceId());
        assertEquals(original.length(), decoded.length());
    }

    @Test
    public void testEncodeToBuffer() {
        TcpMessageHeader header = TcpMessageHeader.frameBatch(1, 100);
        ByteBuffer buffer = ByteBuffer.allocate(8);
        int bytesWritten = header.encode(buffer);
        assertEquals(8, bytesWritten);

        // Flip buffer for reading
        buffer.flip();
        TcpMessageHeader decoded = TcpMessageHeader.decode(buffer);
        assertEquals(header.magic(), decoded.magic());
        assertEquals(header.type(), decoded.type());
        assertEquals(header.sourceId(), decoded.sourceId());
        assertEquals(header.length(), decoded.length());
    }

    @Test
    public void testDecodeFromBuffer() {
        ByteBuffer buffer = ByteBuffer.allocate(8);
        buffer.put(TcpConstants.MAGIC);
        buffer.put(TcpConstants.TYPE_FRAME_BATCH);
        buffer.putShort((short) 123);
        buffer.putInt(100);
        buffer.flip();

        TcpMessageHeader header = TcpMessageHeader.decode(buffer);
        assertEquals(TcpConstants.MAGIC, header.magic());
        assertEquals(TcpConstants.TYPE_FRAME_BATCH, header.type());
        assertEquals(123, header.sourceId());
        assertEquals(100, header.length());
    }

    @Test
    public void testDecodeTooShort() {
        byte[] bytes = new byte[7];
        assertThrows(IllegalArgumentException.class, () -> {
            TcpMessageHeader.decode(bytes);
        });
    }

    @Test
    public void testIsValidLengthForType() {
        TcpMessageHeader frameHeader = TcpMessageHeader.frameBatch(1, 13 + 10);
        assertTrue(frameHeader.isValidLengthForType());

        TcpMessageHeader monitoringHeader = TcpMessageHeader.monitoring(1);
        assertTrue(monitoringHeader.isValidLengthForType());

        TcpMessageHeader eventHeader = TcpMessageHeader.event(1);
        assertTrue(eventHeader.isValidLengthForType());

        // Invalid monitoring length
        TcpMessageHeader invalidMonitoring = new TcpMessageHeader(
                TcpConstants.MAGIC, TcpConstants.TYPE_MONITORING, 1, 41);
        assertFalse(invalidMonitoring.isValidLengthForType());

        // Invalid event length
        TcpMessageHeader invalidEvent = new TcpMessageHeader(
                TcpConstants.MAGIC, TcpConstants.TYPE_EVENT, 1, 26);
        assertFalse(invalidEvent.isValidLengthForType());
    }

    @Test
    public void testToString() {
        TcpMessageHeader header = TcpMessageHeader.frameBatch(1, 100);
        String str = header.toString();
        assertNotNull(str);
        assertTrue(str.contains("TcpMessageHeader"));
    }
}
