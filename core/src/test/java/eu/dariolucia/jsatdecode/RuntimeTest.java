package eu.dariolucia.jsatdecode;

import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.decoder.*;
import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.frame.*;
import eu.dariolucia.jsatdecode.api.lifecycle.*;
import eu.dariolucia.jsatdecode.api.monitoring.*;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.api.taps.*;
import eu.dariolucia.jsatdecode.cli.*;
import eu.dariolucia.jsatdecode.core.config.*;
import eu.dariolucia.jsatdecode.core.distribution.*;
import eu.dariolucia.jsatdecode.core.registry.*;
import eu.dariolucia.jsatdecode.core.runtime.*;
import eu.dariolucia.jsatdecode.decoder.dummy.*;
import eu.dariolucia.jsatdecode.source.cf32.*;

/** Self-contained Phase 5 acceptance and lifecycle tests. */
public final class RuntimeTest {
    private RuntimeTest() {}
    /** Executes integration tests with bounded waits.
     * @param args ignored
     * @throws Exception if verification fails
     */
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("jsat-runtime-");
        try {
            configuration(directory);
            discovery();
            SourceFrequencyRotatorTest.main(new String[0]);
            endToEnd(directory);
            runtimeFailures(directory);
            overflow();
            failureAndLifecycle();
            System.out.println("RuntimeTest passed");
        } finally {
            try(var paths=Files.walk(directory)) {
                for(Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        }
    }
    private static void configuration(Path dir) throws Exception {
        Path file=dir.resolve("settings.properties");
        Files.writeString(file,"x=file\nui.enabled=true\nlabel=caff\u00e8");
        var command=CommandLine.parse(new String[]{"--headless","--config",file.toString(),"--set","x=a=b","--set","ui.enabled=true"});
        var c=ConfigurationLoader.load(Map.of("x","default"),command.config(),command.overrides(),command.switches());
        check(c.getString("x").equals("a=b") && !c.getBoolean("ui.enabled") && c.getString("label").equals("caff\u00e8"),"precedence/UTF-8");
        check(ConfigurationLoader.load(Map.of("x","default"),null,Map.of(),Map.of()).getString("x").equals("default"),"defaults");
        expect(ConfigurationException.class,()->CommandLine.parse(new String[]{"--set","bad"}));
        expect(ConfigurationException.class,()->CommandLine.parse(new String[]{"--config"}));
        expect(ConfigurationException.class,()->CommandLine.parse(new String[]{"--store"}));
        check(CommandLine.parse(new String[]{"--store",dir.resolve("with spaces").toString()}).switches().get("storage.folder").equals(dir.resolve("with spaces").toString()),"store CLI folder");
        var verbose=CommandLine.parse(new String[]{"--headless","-v","--set","logging.verbose=false","-v"});
        check(verbose.action().equals("run") && ConfigurationLoader.load(Map.of(),null,verbose.overrides(),verbose.switches()).getBoolean("logging.verbose"),"verbose switch precedence/repetition");
        check(!CommandLine.parse(new String[]{"--headless"}).switches().containsKey("logging.verbose"),"verbose disabled by default");
        for(String option:List.of("--config","--store","--set"))
            expect(ConfigurationException.class,()->CommandLine.parse(new String[]{option,"-v"}));
        expect(ConfigurationException.class,()->CommandLine.parse(new String[]{"--unknown"}));
        expect(ConfigurationException.class,()->new FactoryRegistry<>(List.of("x","x"),x->x));
    }
    private static void discovery() {
        check(FactoryRegistry.discover(SampleSourceFactory.class,f->f.descriptor().id()).entries().keySet()
                .equals(Set.of("cf32","cs16","wav","fmcomms2")),"source discovery");
        check(FactoryRegistry.discover(DecoderFactory.class,f->f.descriptor().id()).require("dummy") instanceof DummyDecoderFactory,"decoder discovery");
        check(FactoryRegistry.discover(FrameDistributorFactory.class,f->f.descriptor().id()).entries().containsKey("synthetic"),"frame discovery");
        check(FactoryRegistry.discover(MonitoringDistributorFactory.class,f->f.descriptor().id()).entries().containsKey("synthetic"),"monitor discovery");
        expect(ConfigurationException.class,()->new FactoryRegistry<String>(List.of(),x->x).require("missing"));
    }
    private static void endToEnd(Path dir) throws Exception {
        byte[] bytes=new byte[8*33];
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        for(int i=0;i<66;i++) b.putFloat(i-32.25f);
        Path file=dir.resolve("test.cf32"); Files.write(file,bytes);
        List<byte[]> previous=null;
        for(int pass=0;pass<2;pass++) {
            var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",file.toString(),"sample_rate",1000,"block_samples",8)));
            List<byte[]> received=new CopyOnWriteArrayList<>();
            List<Object> observations=new CopyOnWriteArrayList<>();
            var output=new SyntheticFrameDistributor(128,OverflowPolicy.FAIL_RUNTIME,f->received.add(f.data()));
            var monitor=new SyntheticMonitoringDistributor(128,OverflowPolicy.FAIL_RUNTIME,observations::add,observations::add);
            var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(output),List.of(monitor));
            runtime.start(); runtime.awaitCompletion();
            check(runtime.state()==ComponentState.STOPPED && runtime.failure().isEmpty(),"EOF success");
            int offset=0;
            for(byte[] frame:received) { check(Arrays.equals(frame,Arrays.copyOfRange(bytes,offset,offset+frame.length)),"exact frame data"); offset+=frame.length; }
            check(offset==bytes.length && !observations.isEmpty(),"complete frames/events");
            check(output.monitoringSnapshot().messagesSent()==received.size(),"drain");
            if(previous!=null) {
                check(previous.size()==received.size(),"deterministic count");
                for(int i=0;i<previous.size();i++) check(Arrays.equals(previous.get(i),received.get(i)),"deterministic payload");
            }
            previous=received;
            runtime.close(); runtime.close();
            check(source.state()==ComponentState.CLOSED && output.state()==ComponentState.CLOSED,"close");
        }
        var config=new Configuration(Map.of("source.type","cf32","source.file",file.toString(),"source.sample_rate",1000,
                "decoder.type","dummy","distribution.frames.type","synthetic","distribution.monitoring.type","synthetic"));
        try(var runtime=JSatDecodeRuntime.create(config)) { runtime.start(); runtime.awaitCompletion(); check(runtime.failure().isEmpty(),"factory construction"); }
        Main.main(new String[]{"--config",writeConfig(dir,file).toString(),"--headless"});
        Path recordings=dir.resolve("new-recordings");
        Main.main(new String[]{"--config",writeConfig(dir,file).toString(),"--headless","--store",recordings.toString()});
        try(var stored=Files.list(recordings)) {
            var files=stored.toList();
            check(files.size()==1 && Files.size(files.get(0))>bytes.length,"CLI records frames with metadata");
        }
    }
    private static Path writeConfig(Path dir,Path data) throws Exception {
        Path file=dir.resolve("run.properties");
        Files.writeString(file,"source.type=cf32\nsource.file="+data.toString().replace('\\','/')+"\nsource.sample_rate=1000\ndecoder.type=dummy\n");
        return file;
    }
    private static void overflow() throws Exception {
        var frame=new AnnotatedFrame(new byte[]{1},0,Map.of());
        for(var policy:OverflowPolicy.values()) {
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            List<Long> received=new CopyOnWriteArrayList<>();
            var output=new SyntheticFrameDistributor(1,policy,f->{
                received.add(f.timestampNanos());
                if(received.size()==1) { entered.countDown(); waitLatch(release); }
            });
            output.start();
            try {
                output.distribute(frame);
                check(entered.await(3,TimeUnit.SECONDS),"worker entered");
                output.distribute(new AnnotatedFrame(new byte[]{2},2,Map.of()));
                if(policy==OverflowPolicy.FAIL_RUNTIME) expect(OutputException.class,()->output.distribute(new AnnotatedFrame(new byte[]{3},3,Map.of())));
                else output.distribute(new AnnotatedFrame(new byte[]{3},3,Map.of()));
                check(output.monitoringSnapshot().messagesDropped()==1,"explicit overflow count");
            } finally { release.countDown(); output.stop(); }
            check(received.equals(List.of(0L,policy==OverflowPolicy.DROP_OLDEST?3L:2L)),"overflow order");
            output.close();
        }
        var failed=new SyntheticFrameDistributor(2,OverflowPolicy.FAIL_RUNTIME,f->{throw new IllegalStateException("worker failure");});
        failed.start(); failed.distribute(frame);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(failed.state()!=ComponentState.FAILED && System.nanoTime()<deadline) Thread.sleep(1);
        failed.stop();
        check(failed.monitoringSnapshot().errors()==1 && failed.monitoringSnapshot().messagesDropped()==1,"worker failure counted");
        failed.close();
        var monitor=new SyntheticMonitoringDistributor(8,OverflowPolicy.FAIL_RUNTIME,s->{},e->{});
        monitor.start(); monitor.publishMonitoring(new MonitoringSnapshot(0,Map.of()));
        monitor.publishEvent(new RuntimeEvent(0,EventSeverity.INFO,"test","test.event","Test",Map.of()));
        monitor.stop(); check(monitor.monitoringSnapshot().messagesSent()==2,"monitor drain"); monitor.close();
        var concurrent=new SyntheticFrameDistributor(8,OverflowPolicy.DROP_OLDEST,f->{});
        concurrent.start();
        List<Thread> producers=new ArrayList<>();
        AtomicReference<Throwable> error=new AtomicReference<>();
        for(int p=0;p<4;p++) {
            Thread thread=new Thread(()->{ try { for(int i=0;i<1000;i++) concurrent.distribute(frame); }
                catch(Throwable e) {error.set(e);} });
            producers.add(thread); thread.start();
        }
        for(Thread thread:producers) {thread.join(5000); check(!thread.isAlive(),"producer never waits for output");}
        concurrent.stop();
        var status=concurrent.monitoringSnapshot();
        check(error.get()==null && status.messagesReceived()==4000 && status.queueDepth()==0 &&
                status.messagesSent()+status.messagesDropped()==4000,"concurrent bounded accounting");
        concurrent.close();
    }
    private static void runtimeFailures(Path dir) throws Exception {
        Path data=dir.resolve("test.cf32");
        var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",data.toString(),"sample_rate",1000)));
        var output=new SyntheticFrameDistributor(8,OverflowPolicy.FAIL_RUNTIME,f->{throw new IllegalStateException("transport");});
        try(var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(output),List.of())) {
            runtime.start();
            try {runtime.awaitCompletion(); throw new AssertionError("Expected runtime output failure");}
            catch(OutputException expected) {check(runtime.failure().isPresent(),"async failure retained");}
        }
        check(source.state()==ComponentState.CLOSED && output.state()==ComponentState.CLOSED,"failed runtime cleanup");
        var paced=new Cf32SourceFactory().create(new Configuration(Map.of("file",data.toString(),"sample_rate",0.01,"realtime",true)));
        try(var runtime=new JSatDecodeRuntime(paced,new DummyDecoder(),List.of(),List.of())) {
            runtime.start();
            AtomicBoolean interrupted=new AtomicBoolean();
            Thread management=new Thread(()->{
                try {runtime.awaitCompletion();}
                catch(InterruptedException expected) {interrupted.set(Thread.currentThread().isInterrupted());}
            });
            management.start(); management.interrupt(); management.join(3000);
            check(!management.isAlive() && interrupted.get() && runtime.state()==ComponentState.STOPPED,"interrupted management cancels pacing and joins");
        }
    }
    private static void failureAndLifecycle() throws Exception {
        List<String> calls=new ArrayList<>();
        class Source implements SampleSource {
            ComponentState state=ComponentState.CONFIGURED;
            public SourceDescriptor descriptor(){ return new SourceDescriptor("test","Test","1","Test"); }
            public SampleFormat sampleFormat(){return SampleFormat.COMPLEX_FLOAT32_INTERLEAVED;}
            public void setSampleSink(SampleSink sink){}
            public void start(){ calls.add("source.start"); state=ComponentState.RUNNING; }
            public void stop(){calls.add("source.stop"); state=ComponentState.STOPPED;}
            public void close(){calls.add("source.close"); state=ComponentState.CLOSED;}
            public ComponentState state(){return state;}
            public MonitoringSnapshot monitoringSnapshot(){return new MonitoringSnapshot(0,Map.of());}
            public Collection<MonitoringDescriptor> monitoringDescriptors(){return List.of();}
        }
        class BadDecoder implements Decoder {
            ComponentState state=ComponentState.CONFIGURED;
            public DecoderDescriptor descriptor(){return new DecoderDescriptor("bad","Bad","1","Test");}
            public void setFrameSink(FrameSink sink){}
            public void accept(ComplexSampleBlock b){}
            public TapProvider taps(){return new DummyDecoder().taps();}
            public void start(){calls.add("decoder.start"); state=ComponentState.FAILED; throw new IllegalStateException("startup");}
            public void stop(){calls.add("decoder.stop");}
            public void close(){calls.add("decoder.close"); state=ComponentState.CLOSED;}
            public ComponentState state(){return state;}
            public MonitoringSnapshot monitoringSnapshot(){return new MonitoringSnapshot(0,Map.of());}
            public Collection<MonitoringDescriptor> monitoringDescriptors(){return List.of();}
        }
        var runtime=new JSatDecodeRuntime(new Source(),new BadDecoder(),List.of(),List.of());
        expect(IllegalStateException.class,()->{try{runtime.start();}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}});
        runtime.close();
        check(calls.equals(List.of("decoder.start","source.stop","decoder.stop","source.close","decoder.close")),"startup rollback order/exactly once close");
        check(runtime.failure().isPresent() && runtime.state()==ComponentState.CLOSED,"startup diagnostic");
        expect(IllegalStateException.class,()->{try{runtime.start();}catch(RuntimeException e){throw e;}catch(Exception e){throw new RuntimeException(e);}});
    }
    private static void waitLatch(CountDownLatch latch) {
        try { if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("latch timeout"); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void expect(Class<? extends Throwable> type,Runnable action) {
        try { action.run(); } catch(Throwable e) { if(type.isInstance(e)) return; throw new AssertionError("Unexpected failure",e); }
        throw new AssertionError("Expected "+type);
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
}
