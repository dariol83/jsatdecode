package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TcpConstants}.
 */
public final class TcpConstantsTest {

    @Test
    public void testMagicByte() {
        assertEquals(0xA5, TcpConstants.MAGIC & 0xFF);
    }

    @Test
    public void testHeaderLength() {
        assertEquals(8, TcpConstants.HEADER_LENGTH);
    }

    @Test
    public void testMessageTypes() {
        assertEquals(0, TcpConstants.TYPE_FRAME_BATCH);
        assertEquals(1, TcpConstants.TYPE_MONITORING);
        assertEquals(2, TcpConstants.TYPE_EVENT);
    }

    @Test
    public void testFrameQualityConstants() {
        assertEquals(0, TcpConstants.FRAME_QUALITY_GOOD);
        assertEquals(1, TcpConstants.FRAME_QUALITY_BAD);
        assertEquals(2, TcpConstants.FRAME_QUALITY_UNKNOWN);
    }

    @Test
    public void testFrameRecordOverhead() {
        assertEquals(13, TcpConstants.FRAME_RECORD_OVERHEAD);
    }

    @Test
    public void testMonitoringMessageLength() {
        assertEquals(40, TcpConstants.MONITORING_MESSAGE_LENGTH);
    }

    @Test
    public void testEventMessageLength() {
        assertEquals(25, TcpConstants.EVENT_MESSAGE_LENGTH);
    }

    @Test
    public void testCarrierOffsetUnavailable() {
        assertEquals(Integer.MIN_VALUE, TcpConstants.CARRIER_OFFSET_UNAVAILABLE);
    }

    @Test
    public void testStateConstants() {
        assertEquals(0, TcpConstants.STATE_SEARCHING);
        assertEquals(1, TcpConstants.STATE_LOCKED);
        assertEquals(2, TcpConstants.STATE_UNLOCKED);
        assertEquals(3, TcpConstants.STATE_UNKNOWN);
    }

    @Test
    public void testSnrAvailabilityConstants() {
        assertEquals(0, TcpConstants.SNR_UNAVAILABLE);
        assertEquals(1, TcpConstants.SNR_AVAILABLE);
    }

    @Test
    public void testEventCodes() {
        assertEquals(0, TcpConstants.EVENT_DECODER_STARTED);
        assertEquals(1, TcpConstants.EVENT_DECODER_STOPPED);
        assertEquals(2, TcpConstants.EVENT_CARRIER_STATE_CHANGED);
        assertEquals(3, TcpConstants.EVENT_SYMBOL_STATE_CHANGED);
        assertEquals(4, TcpConstants.EVENT_FRAME_STATE_CHANGED);
        assertEquals(5, TcpConstants.EVENT_DECODER_ERROR);
        assertEquals(6, TcpConstants.EVENT_DISTRIBUTION_OVERRUN);
        assertEquals(7, TcpConstants.EVENT_DATA_DISCARDED);
    }

    @Test
    public void testStatusBitMasks() {
        assertEquals(0xC0, TcpConstants.STATUS_CARRIER_MASK);
        assertEquals(6, TcpConstants.STATUS_CARRIER_SHIFT);
        assertEquals(0x30, TcpConstants.STATUS_SYMBOL_MASK);
        assertEquals(4, TcpConstants.STATUS_SYMBOL_SHIFT);
        assertEquals(0x0C, TcpConstants.STATUS_FRAME_MASK);
        assertEquals(2, TcpConstants.STATUS_FRAME_SHIFT);
        assertEquals(0x03, TcpConstants.STATUS_SNR_MASK);
        assertEquals(0, TcpConstants.STATUS_SNR_SHIFT);
    }

    @Test
    public void testIsValidType() {
        assertTrue(TcpConstants.isValidType(TcpConstants.TYPE_FRAME_BATCH));
        assertTrue(TcpConstants.isValidType(TcpConstants.TYPE_MONITORING));
        assertTrue(TcpConstants.isValidType(TcpConstants.TYPE_EVENT));
        assertFalse(TcpConstants.isValidType((byte) 3));
        assertFalse(TcpConstants.isValidType((byte) 100));
    }

    @Test
    public void testIsReservedType() {
        assertFalse(TcpConstants.isReservedType(TcpConstants.TYPE_FRAME_BATCH));
        assertFalse(TcpConstants.isReservedType(TcpConstants.TYPE_MONITORING));
        assertFalse(TcpConstants.isReservedType(TcpConstants.TYPE_EVENT));
        assertTrue(TcpConstants.isReservedType((byte) 0x03));
        assertTrue(TcpConstants.isReservedType((byte) 0x50));
        assertTrue(TcpConstants.isReservedType((byte) 0xEF));
        assertFalse(TcpConstants.isReservedType((byte) 0xF0));
    }

    @Test
    public void testIsFutureType() {
        assertFalse(TcpConstants.isFutureType((byte) 0x03));
        assertFalse(TcpConstants.isFutureType((byte) 0xEF));
        assertTrue(TcpConstants.isFutureType((byte) 0xF0));
        assertTrue(TcpConstants.isFutureType((byte) 0xFF));
    }

    @Test
    public void testIsValidEventCode() {
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_DECODER_STARTED));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_DECODER_STOPPED));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_CARRIER_STATE_CHANGED));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_SYMBOL_STATE_CHANGED));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_FRAME_STATE_CHANGED));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_DECODER_ERROR));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_DISTRIBUTION_OVERRUN));
        assertTrue(TcpConstants.isValidEventCode(TcpConstants.EVENT_DATA_DISCARDED));
        assertFalse(TcpConstants.isValidEventCode((byte) 8));
        assertFalse(TcpConstants.isValidEventCode((byte) 255));
    }

    @Test
    public void testIsReservedEventCode() {
        assertFalse(TcpConstants.isReservedEventCode((byte) 0));
        assertFalse(TcpConstants.isReservedEventCode((byte) 6));
        assertFalse(TcpConstants.isReservedEventCode((byte) 7));
        assertTrue(TcpConstants.isReservedEventCode((byte) 8));
        assertTrue(TcpConstants.isReservedEventCode((byte) 100));
        assertTrue(TcpConstants.isReservedEventCode((byte) 255));
    }

    @Test
    public void testEncodeStatus() {
        // Test all combinations
        for (int carrier = 0; carrier < 4; carrier++) {
            for (int symbol = 0; symbol < 4; symbol++) {
                for (int frame = 0; frame < 4; frame++) {
                    for (int snr = 0; snr < 2; snr++) {
                        byte status = TcpConstants.encodeStatus(carrier, symbol, frame, snr);
                        assertEquals(carrier, TcpConstants.decodeCarrierState(status));
                        assertEquals(symbol, TcpConstants.decodeSymbolState(status));
                        assertEquals(frame, TcpConstants.decodeFrameState(status));
                        assertEquals(snr, TcpConstants.decodeSnrAvailable(status));
                    }
                }
            }
        }
    }

    @Test
    public void testEncodeStatusBoundary() {
        // Test boundary values
        byte status = TcpConstants.encodeStatus(3, 3, 3, 1);
        assertEquals(3, TcpConstants.decodeCarrierState(status));
        assertEquals(3, TcpConstants.decodeSymbolState(status));
        assertEquals(3, TcpConstants.decodeFrameState(status));
        assertEquals(1, TcpConstants.decodeSnrAvailable(status));
    }

    @Test
    public void testDecodeAllStatusBits() {
        // Test that we can decode all bits correctly
        byte status = (byte) 0xFF;
        // With all bits set, we should get 3 for each state (since 2 bits per state)
        assertEquals(3, TcpConstants.decodeCarrierState(status));
        assertEquals(3, TcpConstants.decodeSymbolState(status));
        assertEquals(3, TcpConstants.decodeFrameState(status));
        assertEquals(3, TcpConstants.decodeSnrAvailable(status));
    }
}
