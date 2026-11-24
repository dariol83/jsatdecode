package eu.dariolucia.jsatdecode.distribution.tcp;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.distribution.tcp.server.TcpServerRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.EOFException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TcpDistributionIntegrationTest {
    @TempDir Path directory;

    private Configuration config(int port, boolean monitoring) throws Exception {
        byte[] iq=new byte[128*8];
        var bytes=ByteBuffer.wrap(iq).order(ByteOrder.LITTLE_ENDIAN);
        while (bytes.hasRemaining()) bytes.putFloat(.125f);
        Path file=directory.resolve("input.cf32");
        Files.write(file,iq);
        var values=new LinkedHashMap<String,Object>();
        values.put("source.type","cf32"); values.put("source.file",file.toString());
        values.put("source.sample_rate",1000); values.put("source.block_samples",16);
        values.put("source.repeat",true); values.put("source.realtime",true);
        values.put("source.center_frequency",145000000);
        values.put("decoder.type","dummy");
        values.put("distribution.frames.type","tcp");
        values.put("distribution.monitoring.type",monitoring ? "tcp" : "synthetic");
        values.put("distribution.tcp.port",port);
        values.put("distribution.tcp.max_frame_buffer",1);
        values.put("distribution.tcp.monitoring_interval_s",.02);
        return new Configuration(values);
    }

    @Test void normalRuntimeHonorsAliasesAndSendsRealMetricsAndStoppedEvent() throws Exception {
        int port=TcpTestSupport.port();
        AtomicReference<Throwable> managementFailure=new AtomicReference<>();
        try (var runtime=JSatDecodeRuntime.create(config(port,true))) {
            runtime.start();
            Thread management=new Thread(() -> {
                try { runtime.awaitCompletion(); } catch(Throwable e) { managementFailure.set(e); }
            });
            management.start();
            try (Socket socket=new Socket("localhost",port)) {
                socket.setSoTimeout(3000);
                var initial=TcpTestSupport.read(socket);
                assertTrue(initial.isMonitoring());
                assertEquals(145000000L,initial.monitoring().centerFrequencyHz());
                boolean frame=false, counters=false;
                long deadline=System.nanoTime()+4_000_000_000L;
                while (!(frame && counters) && System.nanoTime()<deadline) {
                    var message=TcpTestSupport.read(socket);
                    frame |= message.isFrameBatch();
                    if (message.isMonitoring() && message.monitoring().totalGoodFrames()>0) {
                        assertEquals(message.monitoring().totalGoodFrames(),message.monitoring().totalDecodedFrames());
                        assertEquals(0,message.monitoring().totalErrorFrames());
                        assertTrue(message.monitoring().framesAccepted()>0);
                        counters=true;
                    }
                }
                assertTrue(frame); assertTrue(counters);
                runtime.stop();
                boolean stopped=false;
                try {
                    for (;;) {
                        var message=TcpTestSupport.read(socket);
                        if (message.isEvent() && message.event().eventCode()==1) stopped=true;
                    }
                } catch (EOFException expected) { }
                assertTrue(stopped,"normal shutdown attempts decoder stopped event");
                management.join(3000);
                assertFalse(management.isAlive());
                assertNull(managementFailure.get());
                assertFalse(TcpServerRegistry.hasServer(port));
            } finally {
                runtime.stop(); management.join(3000);
            }
        } finally { TcpServerRegistry.clearAll(); }
    }

    @Test void frameOnlyRuntimeDoesNotEmitMonitoringOrEvents() throws Exception {
        int port=TcpTestSupport.port();
        try (var runtime=JSatDecodeRuntime.create(config(port,false))) {
            runtime.start();
            try (Socket socket=new Socket("localhost",port)) {
                socket.setSoTimeout(3000);
                for (int i=0;i<8;i++) assertTrue(TcpTestSupport.read(socket).isFrameBatch());
            }
        } finally { TcpServerRegistry.clearAll(); }
    }

    @Test void fullRuntimeRejectsMismatchedPortsAndUnknownSettings() throws Exception {
        int port=TcpTestSupport.port();
        var values=new LinkedHashMap<>(config(port,true).asMap());
        values.put("distribution.monitoring.port",port==65535 ? port-1 : port+1);
        assertThrows(ConfigurationException.class,()->JSatDecodeRuntime.create(new Configuration(values)));
        assertFalse(TcpServerRegistry.hasServer(port));
        values.remove("distribution.monitoring.port");
        values.put("distribution.frames.unknown",true);
        assertThrows(ConfigurationException.class,()->JSatDecodeRuntime.create(new Configuration(values)));
        assertFalse(TcpServerRegistry.hasServer(port));
    }

    @Test void sharedSettingsAreValidatedBeforeCreatingNonTcpOutputs() throws Exception {
        int port = TcpTestSupport.port();
        var values = new LinkedHashMap<>(config(port, true).asMap());
        values.put("distribution.frames.type", "synthetic");
        values.put("distribution.monitoring.type", "synthetic");
        values.put("distribution.tcp.unknown", true);
        var error = assertThrows(ConfigurationException.class, () -> JSatDecodeRuntime.create(new Configuration(values)));
        assertTrue(error.getMessage().contains("Unknown shared TCP setting"));
        assertFalse(TcpServerRegistry.hasServer(port));
    }
}
