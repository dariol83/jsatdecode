package eu.dariolucia.jsatdecode.distribution.tcp.codec;

import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TcpWireConformanceTest {
    private static byte[] hex(String bytes) { return HexFormat.of().parseHex(bytes.replace(" ", "")); }
    private static final byte[] FRAMES = hex("a500beef00000025 01010203040506070800000002a5ff 02fffffffffffffffe0000000100");
    private static final byte[] MONITORING = hex("a501beef00000028 6d0123456789abcd ffffff85 bfc00000 00000003 ffffffff 00000004 80000000");
    private static final byte[] EVENT = hex("a502beef00000019 070102030405060708fc00fedcba987654");

    @Test
    void everyWireLayoutMatchesIndependentBytes() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(0xBEEF, 128);
        assertArrayEquals(FRAMES, encoder.encodeFrameBatch(List.of(
                new FrameRecord((byte) 1, 0x0102030405060708L, hex("a5ff")),
                new FrameRecord((byte) 2, -2, hex("00")))));
        assertArrayEquals(MONITORING, encoder.encodeMonitoring(new MonitoringMessage((byte) 0x6D,
                0x0123456789ABCDL, -123, -1.5f, 0x100000003L, 0xFFFFFFFFL, 4, 0x80000000L)));
        assertArrayEquals(EVENT, encoder.encodeEvent(new EventMessage((byte) 7, 0x0102030405060708L,
                (byte) 0xFC, 0x00FEDCBA987654L)));
        MonitoringMessage monitoring = TcpMessageDecoder.decodeSingle(MONITORING).monitoring();
        assertEquals(-123, monitoring.carrierOffsetHz());
        assertEquals(3, monitoring.totalDecodedFrames());
        assertEquals(0xFFFFFFFFL, monitoring.totalErrorFrames());
        assertEquals(0x80000000L, monitoring.framesAccepted());
    }

    @Test
    void fragmentedAndCoalescedReadsRetainMessageBoundariesAndFrameOrder() {
        byte[] wire = ByteBuffer.allocate(FRAMES.length + MONITORING.length + EVENT.length)
                .put(FRAMES).put(MONITORING).put(EVENT).array();
        for (int fragment : new int[] {1, 3, wire.length}) {
            TcpMessageDecoder decoder = new TcpMessageDecoder(128);
            for (int offset = 0; offset < wire.length; offset += fragment) {
                decoder.feed(wire, offset, Math.min(fragment, wire.length - offset));
            }
            List<FrameRecord> frames = decoder.poll().frames();
            assertEquals(2, frames.size());
            assertArrayEquals(hex("a5ff"), frames.getFirst().frameData());
            assertEquals(-2, frames.getLast().timestampNanos());
            assertTrue(decoder.poll().isMonitoring());
            assertEquals(7, decoder.poll().event().eventCode());
            assertNull(decoder.poll());
            assertEquals(0, decoder.pendingBytes());
        }
    }

    @Test
    void skipsEveryUnrecognizedTypeAndUnknownEventsAtValidatedBoundaries() {
        for (int type = 3; type <= 255; ++type) {
            byte[] unknown = hex("a50312340000000ba500ff");
            unknown[1] = (byte) type;
            TcpMessageDecoder decoder = new TcpMessageDecoder(128);
            decoder.feed(ByteBuffer.allocate(unknown.length + EVENT.length).put(unknown).put(EVENT).array());
            assertEquals(type, decoder.poll().header().type() & 255);
            assertEquals(7, decoder.poll().event().eventCode());
            assertNull(decoder.poll());
        }
        for (int code : new int[] {8, 127, 255}) {
            byte[] unknown = EVENT.clone();
            unknown[8] = (byte) code;
            TcpMessageDecoder decoder = new TcpMessageDecoder(128);
            decoder.feed(ByteBuffer.allocate(unknown.length + MONITORING.length).put(unknown).put(MONITORING).array());
            assertTrue(decoder.poll().isMonitoring());
            assertNull(decoder.poll());
        }
    }

    @Test
    void rejectsMalformedLengthsQualityAndMagicWithoutRetainingPartialState() {
        byte[][] invalid = {
                hex("ff02000000000019"), hex("a503000000000007"), hex("a501000000000027"),
                hex("a50200000000001a"), hex("a500000000000008"), hex("a5ff000080000000"),
                hex("a5030000ffffffff"),
                hex("a5000000000000160300000000000000000000000100"),
                hex("a5000000000000160000000000000000000000000000"),
                hex("a5000000000000160000000000000000008000000000"),
                hex("a5000000000000160000000000000000000000000200")
        };
        for (byte[] bytes : invalid) {
            TcpMessageDecoder decoder = new TcpMessageDecoder(128);
            assertThrows(ProtocolException.class, () -> decoder.feed(bytes), HexFormat.of().formatHex(bytes));
            assertEquals(0, decoder.pendingBytes());
            decoder.reset();
            decoder.feed(EVENT);
            assertEquals(7, decoder.poll().event().eventCode());
        }
    }

    @Test
    void completedMessagesBeforeMalformedCoalescedInputRemainAvailable() {
        TcpMessageDecoder decoder = new TcpMessageDecoder(128);
        assertThrows(ProtocolException.class, () -> decoder.feed(ByteBuffer.allocate(EVENT.length + 8)
                .put(EVENT).put(hex("0000000000000008")).array()));
        assertTrue(decoder.poll().isEvent());
        assertEquals(0, decoder.pendingBytes());
    }

    @Test
    void helpersForceNetworkOrderAndPreserveUnsignedLengths() {
        ByteBuffer headerBuffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        new TcpMessageHeader((byte) 0xA5, (byte) 0xFF, 0xBEEF, 0x80000000).encode(headerBuffer);
        assertArrayEquals(hex("a5ffbeef80000000"), headerBuffer.array());
        TcpMessageHeader header = TcpMessageHeader.decode(headerBuffer.flip().order(ByteOrder.LITTLE_ENDIAN));
        assertEquals(0x80000000L, header.unsignedLength());
        ByteBuffer frameBuffer = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
        new FrameRecord((byte) 1, 0x0102030405060708L, hex("a5ff")).encode(frameBuffer);
        assertArrayEquals(hex("01010203040506070800000002a5ff"), frameBuffer.array());
        assertEquals(0x0102030405060708L, FrameRecord.decode(frameBuffer.flip().order(ByteOrder.LITTLE_ENDIAN), 32).timestampNanos());
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FrameRecord.decode(hex("000000000000000000ffffffff"), 128));
        assertTrue(ex.getMessage().contains("4294967295"));
    }

    @Test
    void producersCanonicalizeUnavailableSnrWhileReceiversAcceptReservedAvailability() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(0, 40);
        for (int status : new int[] {0, 1, 2, 3}) {
            for (float snr : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 2.5f}) {
                byte[] wire = encoder.encodeMonitoring(new MonitoringMessage((byte) (0xFC | status),
                        0, Integer.MIN_VALUE, snr, 0, 0, 0, 0));
                boolean available = status == 1 && Float.isFinite(snr);
                assertEquals(available ? 1 : 0, wire[8] & 3);
                int bits = ByteBuffer.wrap(wire).getInt(20);
                if (!available) {
                    assertEquals(0x7FC00000, bits & 0x7FC00000, "quiet NaN");
                }
                byte[] received = MONITORING.clone();
                received[8] = (byte) (0xFC | status);
                ByteBuffer.wrap(received).putFloat(20, snr);
                assertEquals(available, TcpMessageDecoder.decodeSingle(received).monitoring().isSnrAvailable());
            }
        }
        byte[] unavailable = encoder.encodeMonitoring(new MonitoringMessage((byte) 0, 0, Integer.MIN_VALUE, Float.NaN, 0, 0, 0, 0));
        assertEquals(Integer.MIN_VALUE, TcpMessageDecoder.decodeSingle(unavailable).monitoring().carrierOffsetHz());
    }

    @Test
    void mapsCanonicalFrameworkMetricsAndEnforcesTheConfiguredMaximum() {
        MonitoringSnapshot snapshot = new MonitoringSnapshot(1, Map.of(
                "carrier.lock", "LOCKED", "symbol.lock", "UNLOCKED", "frame.lock", "UNKNOWN",
                "signal.snr_db", -1.5, "carrier.offset_hz", -123.0,
                "source.center_frequency_hz", 0x0123456789ABCDL,
                "frame.total", 0x100000003L, "frame.bad", 0xFFFFFFFFL, "frame.good", 4L));
        TcpMessageEncoder encoder = new TcpMessageEncoder(0xBEEF, 40);
        assertArrayEquals(MONITORING, encoder.encodeMonitoring(snapshot, TcpStatus.snapshotStatus(snapshot),
                TcpStatus.centerFrequency(snapshot), 0x80000000L));
        FrameRecord fitting = new FrameRecord((byte) 0, 1, new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrameBatch(List.of(fitting, fitting)));
        assertThrows(IllegalArgumentException.class, () -> encoder.encodeFrameBatch(new FrameRecord((byte) 0, 1, new byte[20])));
        assertThrows(IllegalArgumentException.class, () -> new TcpMessageEncoder(0, 39));
        assertNull(encoder.encodeFrameBatch(List.of()));
    }

    @Test
    void unavailableMeasurementsAndFileSourceFrequencyUseDocumentedSemantics() {
        for (double offset : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Integer.MIN_VALUE, 2147483648.0}) {
            MonitoringSnapshot snapshot = new MonitoringSnapshot(0, Map.of(
                    "carrier.offset_hz", offset, "signal.snr_db", Double.POSITIVE_INFINITY,
                    "source.center_frequency", 145000000L));
            TcpMessageEncoder encoder = new TcpMessageEncoder(0, 40);
            MonitoringMessage monitoring = TcpMessageDecoder.decodeSingle(encoder.encodeMonitoring(snapshot,
                    TcpStatus.snapshotStatus(snapshot), TcpStatus.centerFrequency(snapshot))).monitoring();
            assertEquals(145000000L, monitoring.centerFrequencyHz());
            assertEquals(3, monitoring.getCarrierState());
            assertFalse(monitoring.isCarrierOffsetAvailable());
            assertFalse(monitoring.isSnrAvailable());
            assertTrue(Float.isNaN(monitoring.snrDb()));
        }
    }

    @Test
    void eventMappingCarriesOccurrenceTimeNewStateAndCurrentMetadata() {
        TcpMessageEncoder encoder = new TcpMessageEncoder(0, 40);
        RuntimeEvent event = new RuntimeEvent(1234567890123456L, EventSeverity.INFO, "decoder",
                "carrier.locked", "Carrier acquired", Map.of("carrier.lock", "SEARCHING",
                "symbol.lock", "UNLOCKED", "source.center_frequency_hz", 145000000L));
        EventMessage decoded = TcpMessageDecoder.decodeSingle(encoder.encodeEvent(event, (byte) 0xFC, 0)).event();
        assertEquals(2, decoded.eventCode());
        assertEquals(event.timestampNanos(), decoded.timestampNanos());
        assertEquals(1, decoded.getCarrierState());
        assertEquals(2, decoded.getSymbolState());
        assertEquals(3, decoded.getFrameState());
        assertEquals(145000000L, decoded.centerFrequencyHz());
        RuntimeEvent unknownState = new RuntimeEvent(1, EventSeverity.INFO, "decoder", "frame_state_changed", "State change", Map.of());
        assertEquals(3, TcpMessageDecoder.decodeSingle(encoder.encodeEvent(unknownState, (byte) 0, 0)).event().getFrameState());
        RuntimeEvent unmappable = new RuntimeEvent(1, EventSeverity.INFO, "source", "source.eof", "End of recording", Map.of());
        assertNull(encoder.encodeEvent(unmappable, (byte) 0xFC, 0));
    }
}
