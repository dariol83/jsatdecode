package eu.dariolucia.jsatdecode;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.decoder.*;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.api.frame.*;
import eu.dariolucia.jsatdecode.api.monitoring.*;
import eu.dariolucia.jsatdecode.api.taps.*;
import eu.dariolucia.jsatdecode.api.lifecycle.*;
import eu.dariolucia.jsatdecode.api.distribution.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Self-contained contract tests; checks execute even without -ea. */
public final class CoreApiTest {
    private static int checks;
    private CoreApiTest() {}
    /** Executes Phase 1 acceptance tests.
     * @param args unused
     * @throws Exception if concurrency verification fails
     */
    public static void main(String[] args) throws Exception {
        configuration();
        descriptors();
        ownership();
        validation();
        lifecycle();
        concurrentReads();
        utcClock();
        decoderStatusContract();
        System.out.println("Phase 1: "+checks+" checks passed");
    }
    private static void decoderStatusContract() throws Exception {
        Set<String> keys=new HashSet<>();
        for(var field:DecoderStatusKeys.class.getFields()) {
            check(field.getType()==String.class && java.lang.reflect.Modifier.isStatic(field.getModifiers()) && java.lang.reflect.Modifier.isFinal(field.getModifiers()));
            check(keys.add((String)field.get(null)));
        }
        check(keys.size()==10);
        try(var decoder=new eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder()) {
            Map<String,MonitoringDescriptor> descriptors=new HashMap<>();
            for(var descriptor:decoder.monitoringDescriptors()) descriptors.put(descriptor.key(),descriptor);
            check(descriptors.keySet().equals(keys));
            check(decoder.monitoringSnapshot().values().keySet().equals(keys));
            decoder.setFrameSink(frame->{}); decoder.start();
            decoder.accept(new ComplexSampleBlock(new float[]{1,0,2,0},2,0,eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),4));
            for(int stage=0;stage<2;stage++) {
                var values=decoder.monitoringSnapshot().values();
                check(values.keySet().equals(keys));
                for(var entry:values.entrySet()) {
                    Class<?> expected=switch(descriptors.get(entry.getKey()).type()) {
                        case DOUBLE -> Double.class; case LONG -> Long.class; case STRING -> String.class;
                        default -> throw new AssertionError("Unexpected decoder metric type");
                    };
                    check(expected.isInstance(entry.getValue()));
                }
                check(values.get(DecoderStatusKeys.CARRIER_LOCK).equals("UNKNOWN"));
                check(Double.isNaN((Double)values.get(DecoderStatusKeys.CARRIER_OFFSET_HZ)));
                check(values.get(DecoderStatusKeys.FRAME_GOOD).equals(1L));
                check(values.get(DecoderStatusKeys.FRAME_BAD).equals(0L));
                check(values.get(DecoderStatusKeys.FRAME_TOTAL).equals(1L));
                check(values.get(DecoderStatusKeys.FRAME_RATE_HZ).equals(2.0));
                decoder.stop();
            }
        }
    }
    private static void utcClock() {
        var original=TimeZone.getDefault();
        try {
            for(String zone:List.of("UTC","Pacific/Honolulu","Asia/Tokyo")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                long epoch=eu.dariolucia.jsatdecode.util.UtcTime.nowNanos();
                long host=eu.dariolucia.jsatdecode.util.UtcTime.epochNanos(java.time.Instant.now());
                check(Math.abs(epoch-host)<TimeUnit.SECONDS.toNanos(2));
                check(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos()>=epoch);
            }
            check(eu.dariolucia.jsatdecode.util.UtcTime.epochNanos(java.time.Instant.parse("1970-01-01T00:00:01.123456789Z"))==1123456789L);
            check(eu.dariolucia.jsatdecode.util.UtcTime.epochNanos(java.time.Instant.parse("1969-12-31T23:59:59.999999999Z"))==-1);
        } finally { TimeZone.setDefault(original); }
    }
    private static void configuration() {
        Configuration c=new Configuration(Map.of(
            "source.type","cf32","source.rate","500000","source.repeat","TrUe",
            "sourced.type","wrong","int",123,"long","9223372036854775807",
            "double","1.25","false",false,"fraction",1.5));
        check(c.getString("source.type").equals("cf32"));
        check(c.getInt("source.rate")==500000);
        check(c.getLong("long")==Long.MAX_VALUE);
        check(c.getDouble("double")==1.25);
        check(c.getBoolean("source.repeat"));
        check(!c.getBoolean("false"));
        check(c.getInt("int")==123);
        check(c.getSection("source").asMap().size()==3);
        check(c.getSection("source.").getString("type").equals("cf32"));
        check(c.getSection("absent").asMap().isEmpty());
        expect(ConfigurationException.class,()->c.get("missing"));
        expect(ConfigurationException.class,()->c.getInt("fraction"));
        expect(ConfigurationException.class,()->c.getInt("long"));
        expect(ConfigurationException.class,()->c.getString("int"));
        expect(ConfigurationException.class,()->c.getBoolean("int"));
        for(String value:List.of("NaN","Infinity","-Infinity","bad")) {
            Configuration bad=new Configuration(Map.of("value",value));
            expect(ConfigurationException.class,()->bad.getDouble("value"));
            expect(ConfigurationException.class,()->bad.getInt("value"));
        }
        for(String value:List.of("9223372036854775808","-9223372036854775809","0.1")) {
            expect(ConfigurationException.class,()->new Configuration(Map.of("v",value)).getLong("v"));
        }
        expect(ConfigurationException.class,()->new Configuration(Map.of("v","yes")).getBoolean("v"));
        expect(IllegalArgumentException.class,()->c.getSection(" "));
        expect(NullPointerException.class,()->c.get(null));
        expect(UnsupportedOperationException.class,()->c.asMap().put("new",1));
    }
    private static void descriptors() {
        check(new DecoderDescriptor("d","Decoder","1","test").equals(new DecoderDescriptor("d","Decoder","1","test")));
        check(new SourceDescriptor("s","Source","1","test").equals(new SourceDescriptor("s","Source","1","test")));
        check(new FrameDistributorDescriptor("f","Frame","1","test").equals(new FrameDistributorDescriptor("f","Frame","1","test")));
        check(new MonitoringDistributorDescriptor("m","Monitor","1","test").equals(new MonitoringDistributorDescriptor("m","Monitor","1","test")));
        check(!new DecoderDescriptor("d","Decoder","1","test").equals(new DecoderDescriptor("d","Decoder","2","test")));
        check(new MonitoringDescriptor("snr",MonitoringType.DOUBLE,"dB","SNR").equals(
            new MonitoringDescriptor("snr",MonitoringType.DOUBLE,"dB","SNR")));
        expect(IllegalArgumentException.class,()->new DecoderDescriptor(" ","D","1","test"));
        expect(NullPointerException.class,()->new SourceDescriptor("s",null,"1","test"));
        expect(IllegalArgumentException.class,()->new FrameDistributorDescriptor("f","F","","test"));
        expect(NullPointerException.class,()->new MonitoringDistributorDescriptor("m","M","1",null));
    }
    private static void ownership() {
        List<Object> nested=new ArrayList<>(List.of("original"));
        Map<String,Object> values=new HashMap<>();
        values.put("nested",nested);
        Configuration c=new Configuration(values);
        MonitoringSnapshot m=new MonitoringSnapshot(-1,values);
        RuntimeEvent e=new RuntimeEvent(-1,EventSeverity.INFO,"source","source.eof","EOF",values);
        MonitoringValue v=new MonitoringValue("nested",nested,-1);
        byte[] bytes={1,2,3};
        AnnotatedFrame frame=new AnnotatedFrame(bytes,-1,values);
        nested.set(0,"changed"); values.clear(); bytes[0]=9;
        check(((List<?>)c.get("nested")).getFirst().equals("original"));
        check(((List<?>)m.values().get("nested")).getFirst().equals("original"));
        check(((List<?>)e.attributes().get("nested")).getFirst().equals("original"));
        check(((List<?>)v.value()).getFirst().equals("original"));
        check(frame.data()[0]==1);
        frame.data()[0]=8;
        check(frame.data()[0]==1);
        AnnotatedFrame equal=new AnnotatedFrame(new byte[]{1,2,3},-1,Map.of("nested",List.of("original")));
        check(frame.equals(equal) && frame.hashCode()==equal.hashCode());
        expect(UnsupportedOperationException.class,()->m.values().clear());
        expect(UnsupportedOperationException.class,()->((List<?>)c.get("nested")).clear());
        float[] iq={1,2,3,4,99,99};
        ComplexSampleBlock block=new ComplexSampleBlock(iq,2,10,-5,500000);
        ComplexSnapshot snapshot=new ComplexSnapshot(iq,2,500000,-5);
        iq[0]=99;
        check(block.i(0)==1 && block.q(1)==4);
        check(block.iq().length==4 && block.sampleIndex()==10 && block.timestampNanos()==-5);
        block.iq()[0]=99;
        check(block.i(0)==1 && block.sampleRate()==500000 && block.complexSamples()==2);
        check(snapshot.iq()[0]==1 && snapshot.complexSamples()==2 && snapshot.sampleRate()==500000);
        snapshot.iq()[0]=99;
        check(snapshot.iq()[0]==1 && snapshot.timestampNanos()==-5);
        check(new ComplexSampleBlock(new float[0],0,0,0,1).complexSamples()==0);
        MonitoringSnapshot locks=new MonitoringSnapshot(0,Map.of(
            DecoderStatusKeys.CARRIER_LOCK,false,DecoderStatusKeys.SYMBOL_LOCK,true,DecoderStatusKeys.FRAME_LOCK,false,DecoderStatusKeys.SIGNAL_SNR_DB,Double.NaN));
        check(locks.values().get(DecoderStatusKeys.SYMBOL_LOCK).equals(true));
        check(locks.values().get(DecoderStatusKeys.CARRIER_LOCK).equals(false));
        check(Double.isNaN((Double)locks.values().get(DecoderStatusKeys.SIGNAL_SNR_DB)));
    }
    private static void validation() {
        expect(NullPointerException.class,()->new Configuration(null));
        Map<String,Object> nullValue=new HashMap<>(); nullValue.put("x",null);
        expect(NullPointerException.class,()->new Configuration(nullValue));
        expect(IllegalArgumentException.class,()->new Configuration(Map.of("x",new StringBuilder())));
        expect(IllegalArgumentException.class,()->new Configuration(Map.of("x",new int[]{1})));
        expect(IllegalArgumentException.class,()->new Configuration(Map.of("x",Map.of(1,"bad"))));
        expect(IllegalArgumentException.class,()->new MonitoringDescriptor("",MonitoringType.DOUBLE,"","bad"));
        expect(NullPointerException.class,()->new MonitoringDescriptor("x",null,"","bad"));
        expect(NullPointerException.class,()->new MonitoringSnapshot(0,null));
        expect(NullPointerException.class,()->new MonitoringValue("x",null,0));
        expect(NullPointerException.class,()->new AnnotatedFrame(null,0,Map.of()));
        expect(IllegalArgumentException.class,()->new RuntimeEvent(0,EventSeverity.INFO,"s","","m",Map.of()));
        expect(NullPointerException.class,()->new RuntimeEvent(0,null,"s","t","m",Map.of()));
        for(double rate:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            expect(IllegalArgumentException.class,()->new ComplexSampleBlock(new float[2],1,0,0,rate));
            expect(IllegalArgumentException.class,()->new ComplexSnapshot(new float[2],1,rate,0));
        }
        expect(IllegalArgumentException.class,()->new ComplexSampleBlock(new float[2],2,0,0,1));
        expect(IllegalArgumentException.class,()->new ComplexSampleBlock(new float[2],-1,0,0,1));
        expect(IllegalArgumentException.class,()->new ComplexSampleBlock(new float[2],1,-1,0,1));
        expect(NullPointerException.class,()->new ComplexSampleBlock(null,0,0,0,1));
        ComplexSampleBlock b=new ComplexSampleBlock(new float[2],1,0,0,1);
        expect(IndexOutOfBoundsException.class,()->b.i(-1));
        expect(IndexOutOfBoundsException.class,()->b.q(1));
        expect(IllegalArgumentException.class,()->new DistributionSnapshot(true,true,-1,0,0,0,0,1,0));
        expect(IllegalArgumentException.class,()->new DistributionSnapshot(true,true,0,0,0,0,2,1,0));
        check(new DistributionSnapshot(false,false,0,0,0,0,0,0,-1).queueCapacity()==0);
        check(OverflowPolicy.values().length==3);
    }
    private static void lifecycle() {
        // Phase 1 has no active implementation: exercise the interface default for every state.
        StateProbe probe=new StateProbe();
        for(ComponentState s:ComponentState.values()) {
            probe.current=s;
            check(probe.state()==s);
            check(probe.isRunning()==(s==ComponentState.RUNNING));
        }
    }
    private static void concurrentReads() throws Exception {
        // Publication and detached copies must remain safe for simultaneous observer readers.
        ComplexSampleBlock b=new ComplexSampleBlock(new float[]{1,2},1,0,0,1);
        Configuration c=new Configuration(Map.of("x",List.of(1,2)));
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread[] readers=new Thread[4];
        for(int t=0;t<readers.length;t++) {
            readers[t]=new Thread(()-> {
                try {
                    for(int i=0;i<1000;i++) {
                        b.iq()[0]=9;
                        if(b.i(0)!=1 || !c.get("x").equals(List.of(1,2))) throw new AssertionError("Mutation leaked");
                    }
                } catch(Throwable failure) { error.compareAndSet(null,failure); }
            });
            readers[t].start();
        }
        for(Thread reader:readers) { reader.join(5000); check(!reader.isAlive()); }
        if(error.get()!=null) throw new AssertionError(error.get());
        check(true);
    }
    private static void check(boolean condition) {
        checks++;
        if(!condition) throw new AssertionError("Check "+checks+" failed");
    }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        checks++;
        try { action.run(); }
        catch(Throwable e) {
            if(type.isInstance(e)) return;
            throw new AssertionError("Expected "+type.getName()+", got "+e,e);
        }
        throw new AssertionError("Expected "+type.getName());
    }
    private static final class StateProbe implements LifecycleComponent {
        private ComponentState current=ComponentState.CONFIGURED;
        @Override public ComponentState state() { return current; }
        @Override public void start() { throw new UnsupportedOperationException("State probe only"); }
        @Override public void stop() { throw new UnsupportedOperationException("State probe only"); }
        @Override public void close() { throw new UnsupportedOperationException("State probe only"); }
    }
}
