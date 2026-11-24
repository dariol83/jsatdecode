package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedDistributionTest {
    @Test void factoriesDeliverAllThreeStreamsOnOneConnection() throws Exception {
        int port = TcpTestSupport.port();
        try (var frame = (TcpFrameDistributor)new TcpFrameDistributorFactory().create(
                     new Configuration(Map.of("port", port, "max_frame_buffer", 1)));
             var monitor = (TcpMonitoringDistributor)new TcpMonitoringDistributorFactory().create(
                     new Configuration(Map.of("port", port, "monitoring_interval_s", .02)))) {
            frame.start(); monitor.start();
            try (Socket socket = new Socket("localhost", port)) {
                socket.setSoTimeout(2000);
                assertTrue(TcpTestSupport.read(socket).isMonitoring(), "initial enabled snapshot");
                long now = eu.dariolucia.jsatdecode.util.UtcTime.nowNanos();
                frame.distribute(new AnnotatedFrame(new byte[]{(byte)0xA5,2,3}, now, Map.of("quality", "UNKNOWN")));
                monitor.publishMonitoring(new MonitoringSnapshot(now, Map.of(
                        "carrier.lock", "LOCKED", "symbol.lock", "UNLOCKED", "frame.lock", "UNKNOWN",
                        "source.center_frequency_hz", 145000000L,
                        "signal.snr_db", 4.5, "carrier.offset_hz", -123.0,
                        "frame.good", 10L, "frame.bad", 2L, "frame.total", 12L)));
                monitor.publishEvent(new RuntimeEvent(now, EventSeverity.INFO, "decoder",
                        "carrier.unlocked", "Lost carrier", Map.of()));
                boolean gotFrame=false, gotMonitoring=false, gotEvent=false;
                long deadline=System.nanoTime()+3_000_000_000L;
                while (!(gotFrame && gotMonitoring && gotEvent) && System.nanoTime()<deadline) {
                    var message=TcpTestSupport.read(socket);
                    if (message.isFrameBatch()) {
                        assertEquals(1,message.frames().size());
                        assertArrayEquals(new byte[]{(byte)0xA5,2,3},message.frames().get(0).frameData());
                        assertEquals(now,message.frames().get(0).timestampNanos());
                        assertEquals(2,message.frames().get(0).quality());
                        gotFrame=true;
                    } else if (message.isMonitoring() && message.monitoring().totalDecodedFrames()==12) {
                        assertEquals(145000000L,message.monitoring().centerFrequencyHz());
                        assertEquals(-123,message.monitoring().carrierOffsetHz());
                        assertEquals(4.5f,message.monitoring().snrDb());
                        assertEquals(1,message.monitoring().framesAccepted());
                        gotMonitoring=true;
                    } else if (message.isEvent() && message.event().eventCode()==2
                            && message.event().timestampNanos()==now) {
                        if (((message.event().statusByte() >> 6)&3)==2) {
                            assertEquals(145000000L,message.event().centerFrequencyHz());
                            gotEvent=true;
                        }
                    }
                }
                assertTrue(gotFrame,"frame stream");
                assertTrue(gotMonitoring,"real monitoring fields");
                assertTrue(gotEvent,"new carrier state in event");
            }
        } finally { TcpServerRegistry.clearAll(); }
    }
}
