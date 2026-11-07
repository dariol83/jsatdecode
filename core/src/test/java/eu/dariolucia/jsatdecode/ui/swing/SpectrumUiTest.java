package eu.dariolucia.jsatdecode.ui.swing;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
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
import eu.dariolucia.jsatdecode.dsp.fft.*;

/** Integration checks for observer FFT, reduction, immutable publication and rendering. */
public final class SpectrumUiTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private SpectrumUiTest() { }
    /** Runs without a desktop display.
     * @param args ignored
     * @throws Exception on verification failure
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        tones(); averaging(); smoothingLevels(); adaptiveRange(); snapshots(); configuration(); observer(); determinism();
        System.out.println("Phase 7: "+checks.get()+" checks passed");
    }
    private static SpectrumSettings settings(int n,WindowFunction window,double alpha) {
        return new SpectrumSettings(n,window,30,30,alpha,-140,100,4096,Double.NaN);
    }
    private static void smoothingLevels() {
        int n=512;
        for(int level=0;level<=4;level++) {
            SpectrumAnalysis analysis=new SpectrumAnalysis("RF spectrum",settings(n,WindowFunction.RECTANGULAR,1));
            analysis.smoothingLevel(level);
            analyze(analysis,tone(n,48000,0,1),n,0,false);
            SpectrumFrame next=analyze(analysis,tone(n,48000,0,0.5),n,0,false);
            double alpha=1.0/(1<<level);
            near(next.dbAt(n/2),10*Math.log10(n*(double)n*(1-alpha+alpha*0.25)),0.0001,"Each smoothing level halves EMA weight");
            analysis.smoothingLevel(0);
            SpectrumFrame lowest=analyze(analysis,tone(n,48000,0,0.5),n,0,false);
            near(lowest.dbAt(n/2),20*Math.log10(n*0.5),0.0001,"Lowest restores original averaging");
        }
    }
    private static void adaptiveRange() {
        int n=512;
        for(String name:List.of("RF spectrum","Baseband spectrum")) {
            SpectrumAnalysis analysis=new SpectrumAnalysis(name,settings(n,WindowFunction.RECTANGULAR,1));
            ComplexSnapshot quiet=new ComplexSnapshot(new float[n*2],n,48000,1);
            SpectrumFrame initial=analysis.analyze(Optional.of(max->quiet),n,0,false,0);
            check(initial.floorDb()==-150 && initial.ceilingDb()==-130,"Zero signal uses numerical floor with ten dB margins");
            ComplexSnapshot strong=tone(n,48000,32,1);
            SpectrumFrame held=analysis.analyze(Optional.of(max->strong),n,0,false,999_999_999L);
            check(held.floorDb()==initial.floorDb() && held.ceilingDb()==initial.ceilingDb(),"Range holds for one second");
            SpectrumFrame updated=analysis.analyze(Optional.of(max->strong),n,0,false,1_000_000_000L);
            double minimum=Double.POSITIVE_INFINITY,maximum=Double.NEGATIVE_INFINITY;
            for(int i=0;i<n;i++) {minimum=Math.min(minimum,updated.dbAt(i));maximum=Math.max(maximum,updated.dbAt(i));}
            check(updated.floorDb()==minimum-10 && updated.ceilingDb()==maximum+10,"Range follows all bins with ten dB margins");
            check(initial.ceilingDb()==-130,"Published frame range stays immutable");
            analysis.analyze(Optional.empty(),n,0,false,1_100_000_000L);
            SpectrumFrame reset=analysis.analyze(Optional.of(max->quiet),n,0,false,1_200_000_000L);
            check(reset.ceilingDb()==-130,"New stream initializes range immediately");
        }
    }
    private static ComplexSnapshot tone(int n,double rate,int bin,double amplitude) {
        float[] iq=new float[n*2];
        for(int i=0;i<n;i++) {double a=2*Math.PI*bin*i/n; iq[2*i]=(float)(amplitude*Math.cos(a)); iq[2*i+1]=(float)(amplitude*Math.sin(a));}
        return new ComplexSnapshot(iq,n,rate,eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());
    }
    private static SpectrumFrame analyze(SpectrumAnalysis a,ComplexSnapshot snapshot,int columns,double center,boolean absolute) {
        return a.analyze(Optional.of(max->{check(max>=snapshot.complexSamples(),"snapshot bound");return snapshot;}),columns,center,absolute);
    }
    private static int peak(SpectrumFrame f) {
        int result=0; for(int i=1;i<f.columns();i++) if(f.dbAt(i)>f.dbAt(result)) result=i; return result;
    }
    private static void tones() throws Exception {
        SpectrumFrame rendered=null;
        for(int n=512;n<=16384;n*=2) {
            for(WindowFunction window:WindowFunction.values()) {
                SpectrumAnalysis a=new SpectrumAnalysis("RF spectrum",settings(n,window,1));
                for(int bin:new int[]{-n/2,-n/8,0,n/8,n/2-1}) {
                    var sample=tone(n,48000,bin,0.5);
                    SpectrumFrame f=analyze(a,sample,Math.min(n,4096),137100000,true);
                    check(f.state()==SpectrumFrame.State.READY,"complete spectrum");
                    double expected=137100000+bin*48000.0/n;
                    near(f.frequencyAtColumn(peak(f)),expected,48000.0/f.columns(),"signed frequency including DC/Nyquist");
                    double gain=new Window(window,n).coherentGain();
                    near(f.dbAt(peak(f)),20*Math.log10(n*0.5*gain),0.001,"unnormalized windowed amplitude");
                    SpectrumFrame narrow=analyze(a,sample,37,0,false);
                    near(narrow.dbAt(peak(narrow)),f.dbAt(peak(f)),1e-8,"maximum reduction preserves narrow tone");
                    near(narrow.frequencyAtColumn(peak(narrow)),bin*48000.0/n,48000.0/37,"reduced axis within one column");
                    check(narrow.columns()==37 && !narrow.absoluteFrequency(),"bounded relative axis");
                    rendered=narrow;
                }
            }
        }
        SpectrumFrame display=rendered;
        SwingUtilities.invokeAndWait(()->{
            AtomicInteger width=new AtomicInteger();
            SpectrumPanel panel=new SpectrumPanel("Test",settings(512,WindowFunction.HANN,1),width::set);
            panel.setSize(650,240); panel.apply(display);
            BufferedImage image=new BufferedImage(650,240,BufferedImage.TYPE_INT_RGB);
            Graphics2D g=image.createGraphics(); try {panel.paint(g);}finally{g.dispose();}
            int colored=0;
            for(int y=SpectrumPanel.TOP;y<202;y++) for(int x=SpectrumPanel.LEFT;x<634;x++) if((image.getRGB(x,y)&0xffffff)==0x0076a0) colored++;
            check(colored>100,"Java2D draws immutable trace");
            check(panel.frame()==display,"paint retains immutable model");
        });
    }
    private static void averaging() {
        int n=512; var a=new SpectrumAnalysis("Baseband",settings(n,WindowFunction.RECTANGULAR,0.25));
        var full=tone(n,48000,64,1); var half=tone(n,48000,64,0.5);
        SpectrumFrame first=analyze(a,full,n,0,false);
        SpectrumFrame second=analyze(a,half,n,0,false);
        near(second.dbAt(peak(second)),10*Math.log10(n*(double)n*0.8125),1e-5,"EMA operates on linear power");
        near(first.dbAt(peak(first)),20*Math.log10(n),1e-5,"published trace survives scratch reuse");
        var empty=new ComplexSnapshot(new float[0],0,48000,99);
        check(analyze(a,empty,n,0,false)==second,"empty observation retains last trace");
        check(analyze(a,tone(128,48000,16,0.1),n,0,false)==second,"partial observation never zero pads");
        var third=analyze(a,half,n,0,false);
        near(third.dbAt(peak(third)),10*Math.log10(n*(double)n*(0.75*0.8125+0.25*0.25)),1e-5,"skipped snapshots do not advance EMA");
        var moved=analyze(a,half,n,100,true);
        near(moved.dbAt(peak(moved)),20*Math.log10(n*0.5),1e-5,"RF retune resets history");
        var rate=analyze(a,tone(n,12000,64,1),n,100,true);
        near(rate.dbAt(peak(rate)),20*Math.log10(n),1e-5,"sample rate change resets history");
        check(a.analyze(Optional.empty(),n,0,false).state()==SpectrumFrame.State.UNAVAILABLE,"missing tap normal");
        var resumed=analyze(a,tone(n,12000,64,0.5),n,100,true);
        near(resumed.dbAt(peak(resumed)),20*Math.log10(n*0.5),1e-5,"reappearance resets history");
        float[] invalid=new float[n*2]; invalid[31]=Float.NaN;
        check(analyze(a,new ComplexSnapshot(invalid,n,12000,1),n,100,true).state()==SpectrumFrame.State.INVALID,"invalid IQ rejected");
        var recovered=analyze(a,tone(n,12000,64,1),n,100,true);
        near(recovered.dbAt(peak(recovered)),20*Math.log10(n),1e-5,"invalid observation resets averaging");
        check(analyze(a,full,0,0,false).state()==SpectrumFrame.State.WAITING,"hidden plot skips FFT");
    }
    private static void snapshots() {
        float[] values={1,2,3,4}; ComplexSnapshot snapshot=new ComplexSnapshot(values,2,1,0); values[0]=9;
        check(snapshot.i(0)==1 && snapshot.q(1)==4,"indexed snapshot ownership");
        snapshot.iq()[1]=9; check(snapshot.q(0)==2,"indexed snapshot cannot expose backing array");
        try {snapshot.i(2);throw new AssertionError("index accepted");} catch(IndexOutOfBoundsException expected) {check(true,"index bounds");}
    }
    private static void configuration() {
        SpectrumSettings defaults=SpectrumSettings.read(new Configuration(Map.of()));
        check(defaults.fftSize()==4096 && defaults.window()==WindowFunction.HANN && defaults.rfHz()==15 && defaults.averageAlpha()==1,"spectrum defaults");
        for(Map<String,Object> invalid:List.<Map<String,Object>>of(Map.of("fft.size",513),Map.of("fft.size",262144),Map.of("fft.window","bad"),Map.of("spectrum.average_alpha",0),Map.of("spectrum.maximum_columns",99999),Map.of("refresh.spectrum_hz",0))) {
            try {SpectrumSettings.read(new Configuration(invalid));throw new AssertionError("invalid config accepted");}
            catch(ConfigurationException expected) {check(true,"configuration rejected");}
        }
    }
    private static void observer() throws Exception {
        SyntheticUiSource source=new SyntheticUiSource(); AtomicBoolean failRf=new AtomicBoolean();
        AtomicInteger rfReads=new AtomicInteger(),baseReads=new AtomicInteger(),columns=new AtomicInteger(73);
        AtomicReference<UiViewModel> latest=new AtomicReference<>(); CountDownLatch displayed=new CountDownLatch(3);
        TapProvider taps=new TapProvider() {
            public Optional<ComplexTap> rf() {return Optional.of(max->{
                check(!SwingUtilities.isEventDispatchThread() && Thread.currentThread().getName().equals("jsatdecode-ui-observer"),"FFT snapshot worker ownership");
                check(max==512,"FFT-sized snapshot request"); rfReads.incrementAndGet();
                if(failRf.get()) throw new IllegalStateException("deliberate RF failure");
                return tone(max,48000,64,1);
            });}
            public Optional<ComplexTap> baseband() {return Optional.of(max->{baseReads.incrementAndGet(); return tone(max,12000,-64,1);});}
            public Optional<ComplexTap> symbols() {return Optional.empty();}
        };
        try(var controller=new MonitoringUiController(source,()->taps,UiSettings.read(new Configuration(Map.of())),
                settings(512,WindowFunction.HANN,1),columns::get,columns::get,model->{check(SwingUtilities.isEventDispatchThread(),"publication on EDT");latest.set(model);displayed.countDown();},error->{throw new AssertionError(error);})) {
            controller.start(); check(displayed.await(4,TimeUnit.SECONDS),"spectrum models displayed");
            var initial=latest.get(); check(initial.spectra().size()==2 && initial.spectra().get(0).columns()==73,"two bounded spectra");
            near(initial.spectra().get(0).frequencyAtColumn(peak(initial.spectra().get(0))),137106000,48000.0/73,"metadata absolute RF axis");
            near(initial.spectra().get(1).frequencyAtColumn(peak(initial.spectra().get(1))),-1500,12000.0/73,"baseband relative axis");
            CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1);
            SwingUtilities.invokeLater(()->{blocked.countDown();try {release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            check(blocked.await(2,TimeUnit.SECONDS),"EDT blocked for bounded publication test");
            try {int before=baseReads.get();Thread.sleep(180);check(baseReads.get()>before && controller.coalescedUpdates()>0,"analysis proceeds with coalesced EDT work");}
            finally {release.countDown();}
            columns.set(19); failRf.set(true);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(System.nanoTime()<deadline && (latest.get().spectra().get(0).state()!=SpectrumFrame.State.ERROR || latest.get().spectra().get(1).columns()!=19)) Thread.sleep(5);
            check(latest.get().spectra().get(0).state()==SpectrumFrame.State.ERROR,"failed RF isolated");
            check(controller.isActive() && controller.failure().isEmpty() && latest.get().spectra().get(1).columns()==19 && latest.get().quality().snrValid(),"baseband resize and quality survive RF failure");
            check(initial.spectra().get(0).columns()==73,"old model remains immutable after resize");
        }
        int reads=baseReads.get();Thread.sleep(80);check(reads==baseReads.get(),"close joins analysis worker");
    }
    private static void determinism() throws Exception {
        Path file=Files.createTempFile("jsat-spectrum-",".cf32");
        byte[] bytes=new byte[8*8192];new Random(73).nextBytes(bytes);Files.write(file,bytes);
        try {
            List<AnnotatedFrame> off=decode(file,false),on=decode(file,true);
            check(!off.isEmpty() && off.size()==on.size(),"FFT on/off frame counts");
            for(int i=0;i<off.size();i++) check(Arrays.equals(off.get(i).data(),on.get(i).data()) && off.get(i).annotations().equals(on.get(i).annotations()),"FFT on/off deterministic bytes and annotations");
        } finally {Files.delete(file);}
    }
    private static List<AnnotatedFrame> decode(Path file,boolean enabled) throws Exception {
        var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",file.toString(),"sample_rate",48000,"block_samples",256,"realtime",true)));
        List<AnnotatedFrame> frames=new CopyOnWriteArrayList<>();
        var output=new SyntheticFrameDistributor(1024,OverflowPolicy.FAIL_RUNTIME,frames::add);
        SyntheticUiSource synthetic=new SyntheticUiSource(); AtomicInteger analyses=new AtomicInteger();
        try(var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(output),List.of());
            var observer=new MonitoringUiController(runtime,()->synthetic,UiSettings.read(new Configuration(Map.of())),settings(4096,WindowFunction.HANN,0.25),()->200,()->200,
                    model->{if(model.spectra().get(0).state()==SpectrumFrame.State.READY) analyses.incrementAndGet();},error->{throw new AssertionError(error);})) {
            runtime.start(); if(enabled) observer.start(); runtime.awaitCompletion();
            check(runtime.failure().isEmpty(),"file decoding completes with FFT observer");
            if(enabled) {SwingUtilities.invokeAndWait(()->{});check(analyses.get()>=2,"actual FFT analyses during file decoding");}
        }
        return frames;
    }
    private static void near(double actual,double expected,double tolerance,String message) {check(Math.abs(actual-expected)<=tolerance,message+": "+actual+" expected "+expected);}
    private static void check(boolean condition,String message) {checks.incrementAndGet();if(!condition)throw new AssertionError(message);}
}
