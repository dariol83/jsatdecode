package eu.dariolucia.jsatdecode.ui.swing;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.taps.*;
import eu.dariolucia.jsatdecode.core.distribution.SyntheticFrameDistributor;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder;
import eu.dariolucia.jsatdecode.source.cf32.Cf32SourceFactory;

/** Bounded symbol observation, BPSK/QPSK pixel placement and isolation checks. */
public final class ConstellationUiTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private ConstellationUiTest() { }
    /** Runs without a native display.
     * @param args ignored
     * @throws Exception on verification failure
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        settings(); history(); rendering(); observer(); cadence(); determinism();
        System.out.println("Phase 8: "+checks.get()+" checks passed");
    }
    private static ConstellationSettings settings(int points,int history,boolean auto) {
        return new ConstellationSettings(points,history,30,auto,2,ConstellationSettings.Reference.NONE);
    }
    private static void settings() {
        var defaults=ConstellationSettings.read(new Configuration(Map.of()));
        check(defaults.points()==1024 && defaults.historyFrames()==1 && defaults.autoScale() && defaults.refreshHz()==15,"defaults");
        var configured=ConstellationSettings.read(new Configuration(Map.of("constellation.points",2048,"constellation.history_frames",2,
                "constellation.scale_mode","fixed","constellation.scale",3,"constellation.reference","qpsk","refresh.constellation_hz",20)));
        check(configured.points()==2048 && configured.historyFrames()==2 && !configured.autoScale() && configured.scale()==3 && configured.reference()==ConstellationSettings.Reference.QPSK,"configurable display");
        for(Map<String,Object> invalid:List.<Map<String,Object>>of(Map.of("constellation.points",0),Map.of("constellation.points",16385),Map.of("constellation.history_frames",9),
                Map.of("constellation.scale_mode","wrong"),Map.of("constellation.scale",0),Map.of("constellation.reference","unknown"),Map.of("refresh.constellation_hz",31))) {
            try {ConstellationSettings.read(new Configuration(invalid));throw new AssertionError("invalid setting accepted");}
            catch(ConfigurationException expected) {check(true,"invalid settings rejected");}
        }
    }
    private static ComplexSnapshot snapshot(float[] iq,double rate,long stamp) {return new ComplexSnapshot(iq,iq.length/2,rate,stamp);}
    private static ConstellationFrame observe(ConstellationAnalysis a,float[] iq,double rate,long stamp) {
        return a.analyze(Optional.of(max->{check(iq.length/2<=max,"bounded symbols request");return snapshot(iq,rate,stamp);}));
    }
    private static void history() {
        var a=new ConstellationAnalysis(settings(4,2,true));
        var first=observe(a,new float[]{1,0,-1,0},2400,1);
        check(first.points()==2 && first.i(0)==1 && first.q(1)==0,"BPSK samples unchanged");
        near(first.extent(),1.1,1e-12,"auto symmetric headroom");
        var second=observe(a,new float[]{2,3,4,5,6,7,8,9},2400,2);
        check(second.points()==6 && second.i(0)==1 && second.i(5)==8,"partial and full snapshots persist in order");
        near(second.extent(),9.9,1e-12,"auto fits I and Q with same extent");
        var third=observe(a,new float[]{10,11,12,13,14,15,16,17},2400,3);
        check(third.points()==8 && third.i(0)==2 && third.i(7)==16,"history evicts oldest at exact bound");
        check(first.points()==2 && first.i(0)==1 && second.points()==6 && second.i(5)==8,"old frames survive ring/scratch reuse");
        check(observe(a,new float[0],2400,4)==third,"empty contention snapshot retains points/time");
        check(observe(a,new float[]{99,99},2400,3)==third,"same observation timestamp not duplicated");
        var changed=observe(a,new float[]{0,0},1200,5);
        check(changed.points()==1 && changed.extent()==1,"rate change clears history; zero input has useful scale");
        check(a.analyze(Optional.empty()).state()==ConstellationFrame.State.UNAVAILABLE,"missing tap normal");
        check(observe(a,new float[0],1200,6).state()==ConstellationFrame.State.WAITING,"reappearance empty waits");
        check(observe(a,new float[]{Float.NaN,0,1,Float.POSITIVE_INFINITY},1200,7).state()==ConstellationFrame.State.INVALID,"all invalid points clear history");
        var finite=observe(a,new float[]{Float.NaN,0,1,2,Float.NEGATIVE_INFINITY,3,4,5},1200,8);
        check(finite.points()==2 && finite.i(0)==1 && finite.q(1)==5,"mixed invalid points filtered as pairs");
        var fixed=new ConstellationAnalysis(settings(4,1,false));
        check(observe(fixed,new float[]{100,100},1,1).extent()==2,"fixed range does not tune sample amplitude");
        try {a.analyze(Optional.of(max->snapshot(new float[10],1,9)));throw new AssertionError("oversized tap accepted");}
        catch(IllegalStateException expected) {check(true,"provider bound enforced");}
        var maximum=new ConstellationAnalysis(settings(16384,8,true));
        for(int i=0;i<12;i++) observe(maximum,new float[32768],1,i);
        check(observe(maximum,new float[]{1,1},1,99).points()==131072,"maximum history storage bounded");
    }
    private static void rendering() throws Exception {
        for(boolean qpsk:new boolean[]{false,true}) for(int[] size:new int[][]{{640,260},{280,420}}) {
            float v=(float)Math.sqrt(0.5);
            float[] iq=qpsk?new float[]{v,v,-v,v,-v,-v,v,-v}:new float[]{1,0,-1,0};
            var a=new ConstellationAnalysis(settings(4,1,false));
            var frame=observe(a,iq,2400,1);
            SwingUtilities.invokeAndWait(()->{
                var panel=new ConstellationPanel(settings(4,1,false));panel.setSize(size[0],size[1]);panel.apply(frame);
                BufferedImage image=new BufferedImage(size[0],size[1],BufferedImage.TYPE_INT_RGB);
                Graphics2D g=image.createGraphics();try {panel.paint(g);}finally {g.dispose();}
                // Independent expected geometry: square inscribed inside fixed label margins.
                int side=Math.min(size[0]-104,size[1]-56),left=(size[0]-side)/2;
                for(int i=0;i<iq.length/2;i++) {
                    int x=left+(int)Math.round((iq[2*i]/2.0+1)*side/2);
                    int y=24+(int)Math.round((1-iq[2*i+1]/2.0)*side/2);
                    int rgb=image.getRGB(x,y)&0xffffff;
                    check(rgb==0xaacfdc || rgb==0x6eb4cb || rgb==0x3296b4 || rgb==0x0076a0,"BPSK/QPSK correct I/Q pixel and persistence shade");
                }
                check(panel.getComponentCount()==0 && panel.frame()==frame,"single Java2D component, immutable frame");
            });
        }
    }
    private static TapProvider taps(java.util.function.Supplier<Optional<ComplexTap>> symbols) {
        SyntheticUiSource source=new SyntheticUiSource();
        return new TapProvider() {
            public Optional<ComplexTap> rf() {return source.rf();}
            public Optional<ComplexTap> baseband() {return source.baseband();}
            public Optional<ComplexTap> symbols() {return symbols.get();}
        };
    }
    private static void observer() throws Exception {
        SyntheticUiSource source=new SyntheticUiSource();
        AtomicBoolean fail=new AtomicBoolean();AtomicInteger reads=new AtomicInteger();AtomicReference<UiViewModel> latest=new AtomicReference<>();
        CountDownLatch ready=new CountDownLatch(3);
        var provider=taps(()->Optional.of(max->{
            check(max==4 && !SwingUtilities.isEventDispatchThread() && Thread.currentThread().getName().equals("jsatdecode-ui-observer"),"symbols read on observer with configured bound");
            int n=reads.incrementAndGet();if(fail.get())throw new IllegalStateException("deliberate symbols failure");
            return snapshot(new float[]{1,1,-1,1,-1,-1,1,-1},2400,n);
        }));
        try(var controller=new MonitoringUiController(source,()->provider,UiSettings.read(new Configuration(Map.of())),SpectrumSettings.read(new Configuration(Map.of())),
                settings(4,2,true),()->100,()->100,model->{check(SwingUtilities.isEventDispatchThread(),"symbols published on EDT");latest.set(model);ready.countDown();},error->{})) {
            controller.start();check(ready.await(3,TimeUnit.SECONDS),"constellation displayed");
            check(latest.get().constellation().points()==8,"bounded persistent display model");
            CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
            SwingUtilities.invokeLater(()->{entered.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(entered.await(2,TimeUnit.SECONDS),"EDT blocked");
            try {int before=reads.get();Thread.sleep(160);check(reads.get()>before && controller.coalescedUpdates()>0,"symbol observation continues while EDT blocked");}
            finally {release.countDown();}
            PrintStream previous=System.err;ByteArrayOutputStream warning=new ByteArrayOutputStream();
            try(PrintStream capture=new PrintStream(warning)) {
                System.setErr(capture);fail.set(true);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
                while(latest.get().constellation().state()!=ConstellationFrame.State.ERROR && System.nanoTime()<deadline)Thread.sleep(5);
                check(latest.get().constellation().state()==ConstellationFrame.State.ERROR,"symbol failure isolated");
                int before=reads.get();Thread.sleep(120);check(reads.get()==before,"failed symbol panel not retried");
                check(warning.toString().contains("Constellation disabled"),"failure diagnostic emitted");
            } finally {System.setErr(previous);}
            check(controller.isActive() && controller.failure().isEmpty() && latest.get().quality().snrValid(),"status and quality survive symbol failure");
            check(latest.get().spectra().stream().allMatch(f->f.state()==SpectrumFrame.State.READY),"both FFT spectra continue after symbol failure");
        }
        int before=reads.get();Thread.sleep(80);check(reads.get()==before,"close joins symbol observer");
    }
    private static void cadence() throws Exception {
        SyntheticUiSource source=new SyntheticUiSource();AtomicInteger symbols=new AtomicInteger(),rf=new AtomicInteger();CountDownLatch ticks=new CountDownLatch(4);
        TapProvider provider=new TapProvider() {
            public Optional<ComplexTap> rf() {rf.incrementAndGet();return Optional.empty();}
            public Optional<ComplexTap> baseband() {return Optional.empty();}
            public Optional<ComplexTap> symbols() {symbols.incrementAndGet();return Optional.empty();}
        };
        var slow=new ConstellationSettings(4,1,0.5,true,1,ConstellationSettings.Reference.NONE);
        try(var controller=new MonitoringUiController(source,()->provider,UiSettings.read(new Configuration(Map.of())),SpectrumSettings.read(new Configuration(Map.of("refresh.spectrum_hz",30))),
                slow,()->100,()->100,model->ticks.countDown(),error->{})) {
            controller.start();check(ticks.await(2,TimeUnit.SECONDS),"independent cadence models");
            check(symbols.get()==1 && rf.get()>=4,"symbols use own refresh deadline without status duplicate reads");
        }
    }
    private static void determinism() throws Exception {
        Path file=Files.createTempFile("jsat-constellation-",".cf32");byte[] bytes=new byte[8*8192];new Random(83).nextBytes(bytes);Files.write(file,bytes);
        try {
            List<AnnotatedFrame> off=decode(file,false),on=decode(file,true);
            check(!off.isEmpty() && off.size()==on.size(),"constellation on/off frame counts");
            for(int i=0;i<off.size();i++)check(Arrays.equals(off.get(i).data(),on.get(i).data()) && off.get(i).annotations().equals(on.get(i).annotations()),"symbol observation leaves bytes/order/annotations unchanged");
        } finally {Files.delete(file);}
    }
    private static List<AnnotatedFrame> decode(Path file,boolean enabled) throws Exception {
        var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",file.toString(),"sample_rate",48000,"block_samples",256,"realtime",true)));
        List<AnnotatedFrame> frames=new CopyOnWriteArrayList<>();var output=new SyntheticFrameDistributor(1024,OverflowPolicy.FAIL_RUNTIME,frames::add);
        SyntheticUiSource synthetic=new SyntheticUiSource();AtomicInteger observed=new AtomicInteger();
        try(var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(output),List.of());
            var observer=new MonitoringUiController(runtime,()->synthetic,UiSettings.read(new Configuration(Map.of())),SpectrumSettings.read(new Configuration(Map.of())),
                ConstellationSettings.read(new Configuration(Map.of())),()->200,()->200,model->{if(model.constellation().state()==ConstellationFrame.State.READY)observed.incrementAndGet();},error->{})) {
            runtime.start();if(enabled)observer.start();runtime.awaitCompletion();SwingUtilities.invokeAndWait(()->{});
            check(runtime.failure().isEmpty(),"file decoder completes");if(enabled)check(observed.get()>=2,"symbols analyzed during file decoding");
        }
        return frames;
    }
    private static void near(double actual,double expected,double tolerance,String message) {check(Math.abs(actual-expected)<=tolerance,message);}
    private static void check(boolean condition,String message) {checks.incrementAndGet();if(!condition)throw new AssertionError(message);}
}
