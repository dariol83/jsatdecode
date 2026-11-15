package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FrameRecord}.
 */
public final class FrameRecordTest {

    @Test
    public void testGoodFrame() {
        byte[] frameData = new byte[] {1, 2, 3, 4, 5};
        FrameRecord record = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData);

        assertEquals(TcpConstants.FRAME_QUALITY_GOOD, record.quality());
        assertEquals(1000L, record.timestampNanos());
        assertTrue(Arrays.equals(frameData, record.frameData()));
        assertTrue(record.isGood());
        assertFalse(record.isBad());
        assertFalse(record.isUnknown());
        assertEquals(13 + 5, record.recordLength());
    }

    @Test
    public void testBadFrame() {
        byte[] frameData = new byte[] {1, 2, 3};
        FrameRecord record = new FrameRecord(TcpConstants.FRAME_QUALITY_BAD, 2000L, frameData);

        assertEquals(TcpConstants.FRAME_QUALITY_BAD, record.quality());
        assertEquals(2000L, record.timestampNanos());
        assertTrue(Arrays.equals(frameData, record.frameData()));
        assertFalse(record.isGood());
        assertTrue(record.isBad());
        assertFalse(record.isUnknown());
    }

    @Test
    public void testUnknownQualityFrame() {
        byte[] frameData = new byte[] {1};
        FrameRecord record = new FrameRecord(TcpConstants.FRAME_QUALITY_UNKNOWN, 3000L, frameData);

        assertEquals(TcpConstants.FRAME_QUALITY_UNKNOWN, record.quality());
        assertFalse(record.isGood());
        assertFalse(record.isBad());
        assertTrue(record.isUnknown());
    }

    @Test
    public void testInvalidQuality() {
        byte[] frameData = new byte[] {1};
        assertThrows(IllegalArgumentException.class, () -> {
            new FrameRecord((byte) 3, 1000L, frameData);
        });
        assertThrows(IllegalArgumentException.class, () -> {
            new FrameRecord((byte) -1, 1000L, frameData);
        });
    }

    @Test
    public void testNullFrameData() {
        assertThrows(NullPointerException.class, () -> {
            new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, null);
        });
    }

    @Test
    public void testEmptyFrameData() {
        assertThrows(IllegalArgumentException.class, () -> {
            new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, new byte[0]);
        });
    }

    @Test
    public void testEncodeDecode() {
        byte[] frameData = new byte[100];
        for (int i = 0; i < 100; i++) {
            frameData[i] = (byte) i;
        }

        FrameRecord original = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1234567890L, frameData);
        byte[] bytes = original.toBytes();
        assertEquals(13 + 100, bytes.length);

        FrameRecord decoded = FrameRecord.decode(bytes, 1000000);
        assertEquals(original.quality(), decoded.quality());
        assertEquals(original.timestampNanos(), decoded.timestampNanos());
        assertTrue(Arrays.equals(original.frameData(), decoded.frameData()));
    }

    @Test
    public void testEncodeDecodeWithMaxLength() {
        byte[] frameData = new byte[1000];
        Arrays.fill(frameData, (byte) 0xAA);

        FrameRecord original = new FrameRecord(TcpConstants.FRAME_QUALITY_BAD, 9876543210L, frameData);
        byte[] bytes = original.toBytes();

        FrameRecord decoded = FrameRecord.decode(bytes, 1000);
        assertEquals(original.quality(), decoded.quality());
        assertEquals(original.timestampNanos(), decoded.timestampNanos());
        assertTrue(Arrays.equals(original.frameData(), decoded.frameData()));
    }

    @Test
    public void testEncodeDecodeFromAnnotatedFrame() {
        eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame frame = 
                new eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame(
                        new byte[] {1, 2, 3}, 5000L, java.util.Map.of()
                );

        FrameRecord record = FrameRecord.fromAnnotatedFrame(frame, TcpConstants.FRAME_QUALITY_GOOD);
        assertEquals(TcpConstants.FRAME_QUALITY_GOOD, record.quality());
        assertEquals(5000L, record.timestampNanos());
        assertTrue(Arrays.equals(new byte[] {1, 2, 3}, record.frameData()));
    }

    @Test
    public void testEncodeToBuffer() {
        byte[] frameData = new byte[] {0x01, 0x02, 0x03};
        FrameRecord record = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData);

        ByteBuffer buffer = ByteBuffer.allocate(16);
        int bytesWritten = record.encode(buffer);
        assertEquals(16, bytesWritten);

        // Flip buffer for reading
        buffer.flip();
        FrameRecord decoded = FrameRecord.decode(buffer, 100);
        assertEquals(record.quality(), decoded.quality());
        assertEquals(record.timestampNanos(), decoded.timestampNanos());
        assertTrue(Arrays.equals(record.frameData(), decoded.frameData()));
    }

    @Test
    public void testDecodeFromBufferWithOffset() {
        byte[] frameData = new byte[] {0x01, 0x02};
        FrameRecord original = new FrameRecord(TcpConstants.FRAME_QUALITY_BAD, 2000L, frameData);
        byte[] bytes = original.toBytes();

        // Create a buffer with some prefix bytes
        byte[] bufferBytes = new byte[20];
        Arrays.fill(bufferBytes, (byte) 0);
        System.arraycopy(bytes, 0, bufferBytes, 5, bytes.length);

        FrameRecord decoded = FrameRecord.decode(bufferBytes, 5, 100);
        assertEquals(original.quality(), decoded.quality());
        assertEquals(original.timestampNanos(), decoded.timestampNanos());
        assertTrue(Arrays.equals(original.frameData(), decoded.frameData()));
    }

    @Test
    public void testEqualsAndHashCode() {
        byte[] frameData1 = new byte[] {1, 2, 3};
        byte[] frameData2 = new byte[] {1, 2, 3};
        byte[] frameData3 = new byte[] {1, 2, 4};

        FrameRecord record1 = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData1);
        FrameRecord record2 = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData2);
        FrameRecord record3 = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData3);

        assertEquals(record1, record2);
        assertEquals(record1.hashCode(), record2.hashCode());
        assertNotEquals(record1, record3);

        // Different timestamp
        FrameRecord record4 = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 2000L, frameData1);
        assertNotEquals(record1, record4);

        // Different quality
        FrameRecord record5 = new FrameRecord(TcpConstants.FRAME_QUALITY_BAD, 1000L, frameData1);
        assertNotEquals(record1, record5);
    }

    @Test
    public void testToString() {
        byte[] frameData = new byte[] {1, 2, 3};
        FrameRecord record = new FrameRecord(TcpConstants.FRAME_QUALITY_GOOD, 1000L, frameData);
        String str = record.toString();
        assertNotNull(str);
        assertTrue(str.contains("FrameRecord"));
    }

    @Test
    public void testOverheadConstant() {
        assertEquals(13, FrameRecord.OVERHEAD);
    }
}
