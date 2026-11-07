package eu.dariolucia.jsatdecode.ui.swing;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import javax.swing.SwingUtilities;
import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.monitoring.*;
import eu.dariolucia.jsatdecode.api.taps.*;
import eu.dariolucia.jsatdecode.core.distribution.SyntheticFrameDistributor;
import eu.dariolucia.jsatdecode.core.runtime.JSatDecodeRuntime;
import eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoder;
import eu.dariolucia.jsatdecode.source.cf32.Cf32SourceFactory;
import eu.dariolucia.jsatdecode.ui.swing.UiViewModel.*;

/** Self-contained Phase 6 observer tests; no display or native input required. */
public final class MonitoringUiTest {
    private static final AtomicInteger checks=new AtomicInteger();
    private MonitoringUiTest() { }
    /** Runs profile, concurrency, headless and functional determinism checks.
     * @param args ignored
     * @throws Exception on verification failure
     */
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless","true");
        Path directory=Files.createTempDirectory("jsat-ui-");
        try {
            quality(); refresh(); coalescing(); failures(); determinism(directory); headless(directory);
            System.out.println("Phase 6: "+checks.get()+" checks passed");
        } finally {
            try(var paths=Files.walk(directory)) {for(Path path:paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);}
        }
    }
    private static UiSettings settings(Object... entries) {
        Map<String,Object> values=new LinkedHashMap<>(Map.of("refresh.status_hz",20,"refresh.quality_hz",30));
        for(int i=0;i<entries.length;i+=2) values.put((String)entries[i],entries[i+1]);
        return UiSettings.read(new Configuration(values));
    }
    private static void quality() {
        UiSettings defaults=UiSettings.read(new Configuration(Map.of()));
        check(defaults.statusHz()==4 && defaults.qualityHz()==8 && defaults.smoothingAlpha()==0.25,"cadence/smoothing defaults");
        SignalQualityProfile profile=defaults.qualityProfile();
        for(int i=0;i<=4;i++) check(profile.percent(-5+5*i)==25*i,"SNR threshold mapping");
        check(profile.percent(-100)==0 && profile.percent(100)==100 && profile.percent(2.5)==37.5,"clamping/interpolation");
        check(Double.isNaN(profile.percent(Double.NaN)) && Double.isNaN(profile.percent(Double.POSITIVE_INFINITY)),"invalid SNR unavailable");
        expect(IllegalArgumentException.class,()->new SignalQualityProfile(0,1,1,3,4));
        expect(ConfigurationException.class,()->settings("quality.alpha",0));
        expect(ConfigurationException.class,()->settings("maximum_metrics",0));
        expect(ConfigurationException.class,()->settings("refresh.status_hz",100));
        SignalQualityEstimator estimator=new SignalQualityEstimator(defaults);
        Quality first=estimator.update(snapshot(15)); check(first.percent()==100,"first reading is unsmoothed");
        check(estimator.update(snapshot(-5)).percent()==75,"exponential display smoothing");
        check(estimator.update(snapshot(Double.NaN)).snrValid()==false,"NaN clears stale confidence");
        check(estimator.update(snapshot(-5)).percent()==0,"reacquisition resets smoothing history");
        for(Object invalid:List.of(Double.NEGATIVE_INFINITY,Float.NaN,"9.2")) {
            Quality value=estimator.update(new MonitoringSnapshot(1,Map.of(DecoderStatusKeys.SIGNAL_SNR_DB,invalid)));
            check(!value.snrValid() && Double.isNaN(value.percent()),"invalid typed SNR is not zero quality");
        }
        Quality independent=estimator.update(new MonitoringSnapshot(2,Map.of(DecoderStatusKeys.SIGNAL_SNR_DB,5,DecoderStatusKeys.CARRIER_LOCK,false,DecoderStatusKeys.SYMBOL_LOCK,"SEARCHING",DecoderStatusKeys.FRAME_LOCK,true)));
        check(independent.carrier()==LockState.UNLOCKED && independent.symbol()==LockState.SEARCHING && independent.frame()==LockState.LOCKED,"true independent lock inputs");
        Quality missing=estimator.update(new MonitoringSnapshot(3,Map.of()));
        check(!missing.snrValid() && missing.carrier()==LockState.UNKNOWN,"missing SNR/locks unavailable");
    }
    private static MonitoringSnapshot snapshot(double snr) {return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),Map.of(DecoderStatusKeys.SIGNAL_SNR_DB,snr));}
    private static TapProvider emptyTaps() {
        return new TapProvider() {
            public Optional<ComplexTap> rf(){return Optional.empty();}
            public Optional<ComplexTap> baseband(){return Optional.empty();}
            public Optional<ComplexTap> symbols(){return Optional.empty();}
        };
    }
    private static Monitorable provider(java.util.function.Supplier<MonitoringSnapshot> snapshots) {
        return new Monitorable() {
            public MonitoringSnapshot monitoringSnapshot(){return snapshots.get();}
            public Collection<MonitoringDescriptor> monitoringDescriptors(){
                check(!SwingUtilities.isEventDispatchThread(),"metadata is polled off EDT");
                return List.of(new MonitoringDescriptor(DecoderStatusKeys.SIGNAL_SNR_DB,MonitoringType.DOUBLE,"dB","Test SNR"));
            }
        };
    }
    private static void refresh() throws Exception {
        CountDownLatch displayed=new CountDownLatch(3); AtomicReference<UiViewModel> latest=new AtomicReference<>();
        AtomicInteger reads=new AtomicInteger();
        Monitorable source=provider(()->{
            check(!SwingUtilities.isEventDispatchThread() && Thread.currentThread().getName().equals("jsatdecode-ui-observer"),"snapshot worker ownership");
            int n=reads.incrementAndGet();
            return new MonitoringSnapshot(eu.dariolucia.jsatdecode.util.UtcTime.nowNanos(),Map.of(DecoderStatusKeys.SIGNAL_SNR_DB,5.0,"decoder.message","x".repeat(100),
                    "decoder.sequence",n,"decoder.extra",1,"source.active",true));
        });
        TapProvider taps=new TapProvider() {
            public Optional<ComplexTap> rf(){check(!SwingUtilities.isEventDispatchThread(),"tap lookup off EDT");return Optional.of(max->{
                check(max==8 && !SwingUtilities.isEventDispatchThread(),"bounded tap acquisition off EDT");
                return new ComplexSnapshot(new float[]{1,0},1,48000,eu.dariolucia.jsatdecode.util.UtcTime.nowNanos());});}
            public Optional<ComplexTap> baseband(){return Optional.empty();}
            public Optional<ComplexTap> symbols(){return Optional.of(max->new ComplexSnapshot(new float[0],0,2400,eu.dariolucia.jsatdecode.util.UtcTime.nowNanos()));}
        };
        try(var controller=new MonitoringUiController(source,()->taps,settings("maximum_metrics",3,"maximum_text_characters",16,"tap_samples",8),model->{
            check(SwingUtilities.isEventDispatchThread(),"display only on EDT"); latest.set(model); displayed.countDown();
        },error->{throw new AssertionError(error);})) {
            controller.start(); check(displayed.await(5,TimeUnit.SECONDS),"view refresh");
            UiViewModel model=latest.get();
            check(model.rows().size()==3 && model.omittedMetrics()==2,"bounded dynamic metric rows");
            for(var row:model.rows()) check(row.metric().length()<=16 && row.value().length()<=16,"bounded formatted text");
            check(model.taps().get(0).samples()==1 && !model.taps().get(1).available() && model.taps().get(2).available() && model.taps().get(2).samples()==0,"missing/empty/available taps");
            expect(UnsupportedOperationException.class,()->model.rows().clear());
            controller.close(); int stopped=reads.get(); Thread.sleep(100);
            check(reads.get()==stopped && !controller.isActive() && controller.failure().isEmpty(),"joined observer shutdown");
        }
    }
    private static void coalescing() throws Exception {
        CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),polled=new CountDownLatch(6);
        AtomicInteger updates=new AtomicInteger();
        SwingUtilities.invokeLater(()->{blocked.countDown();await(release);}); check(blocked.await(3,TimeUnit.SECONDS),"EDT blocked fixture");
        try(var controller=new MonitoringUiController(provider(()->{polled.countDown();return snapshot(5);}),MonitoringUiTest::emptyTaps,
                settings(),model->updates.incrementAndGet(),error->{})) {
            controller.start(); check(polled.await(5,TimeUnit.SECONDS),"poller independent from slow EDT");
            waitUntil(()->controller.coalescedUpdates()>=4,"latest-model replacement");
            check(updates.get()==0,"no display executed through blocked EDT");
            long begin=System.nanoTime(); controller.close();
            check(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(1),"management close never waits for EDT");
        } finally {release.countDown(); SwingUtilities.invokeAndWait(()->{});}
        check(updates.get()==0,"stale pending model suppressed after close");
    }
    private static void failures() throws Exception {
        for(boolean renderer:new boolean[]{false,true}) {
            AtomicInteger errors=new AtomicInteger();
            Monitorable source=provider(()->{if(!renderer) throw new IllegalStateException("Provider failed");return snapshot(5);});
            try(var controller=new MonitoringUiController(source,MonitoringUiTest::emptyTaps,settings(),model->{throw new IllegalStateException("Renderer failed");},
                    error->errors.incrementAndGet())) {
                controller.start(); waitUntil(()->controller.failure().isPresent(),"isolated observer failure"); controller.close();
                check(!controller.isActive() && errors.get()==1,"failure disables UI once, no runtime action");
            }
        }
    }
    private static void determinism(Path directory) throws Exception {
        Path file=directory.resolve("samples.cf32"); byte[] bytes=new byte[8*8192];
        new Random(42).nextBytes(bytes); Files.write(file,bytes);
        List<AnnotatedFrame> baseline=decode(file,false),observed=decode(file,true);
        check(baseline.size()==observed.size() && !baseline.isEmpty(),"UI on/off frame count");
        int offset=0;
        for(int i=0;i<baseline.size();i++) {
            byte[] data=baseline.get(i).data();
            check(Arrays.equals(data,observed.get(i).data()) && baseline.get(i).annotations().equals(observed.get(i).annotations()),"deterministic bytes/order/annotations");
            check(Arrays.equals(data,Arrays.copyOfRange(bytes,offset,offset+data.length)),"recorded bytes preserved"); offset+=data.length;
        }
        check(offset==bytes.length,"entire file delivered");
    }
    private static List<AnnotatedFrame> decode(Path file,boolean observer) throws Exception {
        var source=new Cf32SourceFactory().create(new Configuration(Map.of("file",file.toString(),"sample_rate",48000,"block_samples",256)));
        List<AnnotatedFrame> frames=new CopyOnWriteArrayList<>();
        var output=new SyntheticFrameDistributor(1024,OverflowPolicy.FAIL_RUNTIME,frames::add);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        try(var runtime=new JSatDecodeRuntime(source,new DummyDecoder(),List.of(output),List.of())) {
            Monitorable slow=provider(()->{entered.countDown();await(release);return runtime.monitoringSnapshot();});
            try(var controller=new MonitoringUiController(slow,runtime::taps,settings(),model->{},error->{})) {
                if(observer) {controller.start();check(entered.await(3,TimeUnit.SECONDS),"slow observer active before DSP");}
                try {
                    runtime.start();
                    CompletableFuture<Void> completed=CompletableFuture.runAsync(()->{
                        try {runtime.awaitCompletion();}catch(InterruptedException e){throw new CompletionException(e);}
                    });
                    completed.get(3,TimeUnit.SECONDS);
                    check(runtime.failure().isEmpty() && !runtime.isRunning(),"DSP finishes while observer remains blocked");
                } finally {release.countDown();}
            }
        }
        return frames;
    }
    private static void headless(Path directory) throws Exception {
        Path file=directory.resolve("samples.cf32"),config=directory.resolve("run.properties");
        Files.writeString(config,"source.type=cf32\nsource.file="+file.toString().replace('\\','/')+"\nsource.sample_rate=48000\ndecoder.type=dummy\nui.enabled=true\n");
        Path main=Path.of(eu.dariolucia.jsatdecode.cli.Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path tests=Path.of(MonitoringUiTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path target=main;
        while(target!=null && !Files.isDirectory(target.resolve("test-dependencies"))) target=target.getParent();
        if(target==null) throw new AssertionError("Missing resolved test dependencies");
        String cp=main+File.pathSeparator+tests+File.pathSeparator+target.resolve("test-dependencies")+File.separator+"*";
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        for(int mode=0;mode<4;mode++) {
            Path output=directory.resolve("headless-"+mode+".log");
            List<String> command=new ArrayList<>(List.of(java,"-Djava.awt.headless="+(mode!=3),"-verbose:class","--limit-modules",mode==1 || mode==3?"java.base,java.desktop":"java.base",
                    "-cp",cp,"eu.dariolucia.jsatdecode.UiHeadlessProbe","--config",config.toString(),mode==0?"--headless":"--ui"));
            if(mode==3) command.addAll(List.of("--set","ui.quality.alpha=0"));
            var process=new ProcessBuilder(command)
                    .redirectErrorStream(true).redirectOutput(output.toFile()).start();
            try {
                check(process.waitFor(15,TimeUnit.SECONDS),"headless child completes");
                String text=Files.readString(output);
                check(process.exitValue()==0 && text.contains("HEADLESS_NO_UI_THREADS"),"no UI threads in disabled/fallback path");
                if(mode==0) check(!text.contains("ui.swing.SwingMonitoringUi") && !text.contains("javax.swing.JFrame"),"disabled UI not loaded");
                else check(text.contains("continuing without"),"requested unavailable UI warns and decodes");
            } finally {if(process.isAlive()) {process.destroyForcibly();process.waitFor(3,TimeUnit.SECONDS);}}
        }
    }
    private static void waitUntil(BooleanSupplier condition,String message) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean() && System.nanoTime()<deadline) Thread.sleep(1);
        check(condition.getAsBoolean(),message);
    }
    private static void await(CountDownLatch latch) {
        try {if(!latch.await(5,TimeUnit.SECONDS)) throw new AssertionError("Latch timeout");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
    }
    private static void expect(Class<? extends Throwable> type,Runnable operation) {
        try {operation.run();}catch(Throwable error){check(type.isInstance(error),"expected "+type+", got "+error);return;}
        throw new AssertionError("Expected "+type);
    }
    private static void check(boolean condition,String message) {checks.incrementAndGet();if(!condition) throw new AssertionError(message);}
}
