package eu.dariolucia.jsatdecode.distribution.zmq;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;

/** Optional native-Python interoperability fixture. Commands travel over stdin;
 * actual data travels over independent TCP PUB sockets to unchanged clients.
 */
public final class ZmqInteropPublisher {
    private ZmqInteropPublisher() { }
    /** Prints endpoints then accepts PROBE, SEND and STOP from the test driver.
     * @param args ignored
     * @throws Exception for fixture or output failure
     */
    public static void main(String[] args) throws Exception {
        var config=new Configuration(Map.of("endpoint","tcp://127.0.0.1:*","decoder","test-decoder",
                "receiver_id","test-rx","interval_s",0.02,"linger_ms",250));
        try(var frames=new ZmqFrameDistributor(config); var monitoring=new ZmqMonitoringDistributor(config);
            var input=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.UTF_8))) {
            frames.start(); monitoring.start();
            System.out.println("READY "+frames.endpoint().orElseThrow()+" "+monitoring.endpoint().orElseThrow());
            System.out.flush();
            String command;
            while((command=input.readLine())!=null && !command.equals("STOP")) {
                if(command.equals("PROBE")) {
                    frames.distribute(frame(99)); monitoring.publishEvent(event("probe"));
                } else if(command.equals("SEND")) {
                    monitoring.publishMonitoring(new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),Map.of("source.active",true)));
                    monitoring.publishMonitoring(new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),Map.of(DecoderStatusKeys.SIGNAL_SNR_DB,9.25,DecoderStatusKeys.FRAME_LOCK,"UNLOCKED")));
                    for(int i=0;i<20;i++) frames.distribute(frame(i));
                    monitoring.publishEvent(event("FRAME_LOCK_LOSS"));
                } else throw new IllegalArgumentException("Unknown test command");
            }
            frames.stop(); monitoring.stop();
            if(frames.failure().isPresent() || monitoring.failure().isPresent()) throw new AssertionError("Interop output failed");
            System.out.println("STOPPED"); System.out.flush();
        }
    }
    private static AnnotatedFrame frame(int marker) {
        return new AnnotatedFrame(new byte[]{(byte)marker,(byte)(255-marker)},eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),
                Map.of("rx_time_ns",123456789L,"rx_time_source","recording","frame_type","CCSDS_TM","quality","GOOD","vcid",3,
                        "opaque",new BinaryValue(new byte[]{0,1,(byte)255}),"absent",NullValue.INSTANCE));
    }
    private static RuntimeEvent event(String type) {
        return new RuntimeEvent(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),EventSeverity.WARNING,"test",type,"Loss of lock",
                Map.of("metric",DecoderStatusKeys.FRAME_LOCK,"previous","LOCKED","value","UNLOCKED"));
    }
}
