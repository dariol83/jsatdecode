package eu.dariolucia.jsatdecode.source.fmcomms2;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import eu.dariolucia.jsatdecode.api.config.*;
import eu.dariolucia.jsatdecode.api.lifecycle.*;
import eu.dariolucia.jsatdecode.api.source.*;
import eu.dariolucia.jsatdecode.util.UtcTime;

/** Independent TCP daemon fixtures exercise real protocol framing without native code or hardware. */
public final class Fmcomms2SourceTest {
    private static int checks;
    private Fmcomms2SourceTest() { }
    /** Runs protocol, conversion, configuration, cancellation and throughput checks.
     * @param args ignored
     * @throws Exception on failed assertions or socket operations
     */
    public static void main(String[] args) throws Exception {
        configuration(); conversion(); streaming(); readDiagnostics(); decimation(); fileFilter(); failures(); cancellation(); throughput();
        Cf32RecordingTest.run();
        System.out.println("FMComms2 source: "+checks+" checks passed");
    }
    private static void check(boolean condition,String message) { checks++; if(!condition) throw new AssertionError(message); }
    private static Configuration config(int port,Object... extra) {
        Map<String,Object> values=new LinkedHashMap<>(Map.of("type","fmcomms2","uri","ip:127.0.0.1:"+port,
            "buffer_size",4,"block_samples",2,"gain1","manual","manual_gain1",42.5));
        for(int i=0;i<extra.length;i+=2) values.put((String)extra[i],extra[i+1]);
        return new Configuration(values);
    }
    private static void configuration() throws Exception {
        check(ServiceLoader.load(SampleSourceFactory.class).stream().anyMatch(p->p.get().descriptor().id().equals("fmcomms2")),"ServiceLoader registration");
        for(var invalid:List.of(config(1,"uri","usb:1.2.3"),config(1,"rx2_en",true),config(1,"rx1_en",false),
                config(1,"sample_rate",500000),config(1,"filter_source","Auto"),config(1,"filter_source","Design"),
                config(1,"block_samples",5),config(1,"gain1","invalid"),config(1,"typo",true),config(1,"read_timeout_ms",1000),
                config(1,"fpga_decimation",3),config(1,"fpga_decimation",32),
                config(1,"sample_rate",3000001,"fpga_decimation",2),
                config(1,"sample_rate",4000000,"fpga_decimation",16,"buffer_size",1048576))) {
            try { new Fmcomms2SourceFactory().create(invalid); throw new AssertionError("Bad config accepted"); }
            catch(ConfigurationException expected) { checks++; }
        }
        Fmcomms2Settings ipv6=Fmcomms2Settings.read(config(1,"uri","ip:[::1]:30432"));
        check(ipv6.port()==30432,"IPv6 URI accepted");
        try(var source=new Fmcomms2SourceFactory().create(config(1))) {
            check(source.state()==ComponentState.CONFIGURED && source.failure().isEmpty(),"Construction has no network I/O");
            check(source.monitoringDescriptors().size()==source.monitoringSnapshot().values().size(),"Fixed monitoring schema");
            source.stop(); check(source.state()==ComponentState.STOPPED,"Stop before start");
        }
    }
    private static void conversion() throws Exception {
        byte[] little={(byte)0xff,7,0,(byte)0xf8};
        check(IioContext.Format.parse("le:S12/16>>0").value(little,0)==2047/2048f,"Signed full-scale positive");
        check(IioContext.Format.parse("le:S12/16>>0").value(little,2)==-1f,"Signed full-scale negative");
        byte[] big={(byte)0x80,0,0x7f,(byte)0xf0};
        check(IioContext.Format.parse("be:s12/16>>4").value(big,0)==-1f,"Shifted big-endian sign extension");
        check(IioContext.Format.parse("be:s12/16>>4").value(big,2)==2047/2048f,"Shifted big-endian positive");
        for(String format:List.of("le:u12/16>>0","le:s12/32>>0","le:s12/16X2>>0","le:s12/16>>8")) {
            try { IioContext.Format.parse(format); throw new AssertionError("Bad format accepted"); } catch(IOException expected) { checks++; }
        }
        String xml=xml("be:s12/16>>4",40);
        var layout=IioContext.parse(xml.getBytes(StandardCharsets.UTF_8)).device("cf-ad9361-lpc").layout(1);
        check(layout.mask().equals("000000000000000c"),"Mask is sorted channel number, padded multiword, not scan index/XML order");
        float[] iq=new float[2]; layout.convert(big,0,1,iq);
        check(iq[0]==-1f&&iq[1]==2047/2048f,"RX2 I/Q demultiplexing");
        var external=IioContext.parse(xml.replace("<!DOCTYPE context []>","<!DOCTYPE context SYSTEM 'file:///nonexistent-fmcomms2.dtd'>").getBytes(StandardCharsets.UTF_8));
        check(external.device("ad9361-phy")!=null,"No external DTD fetch");
    }
    private static void readDiagnostics() throws Exception {
        for (int capacity : new int[] {0, -1}) {
            try { new ReadDiagnostics(capacity, (utc, elapsed, samples) -> {}); throw new AssertionError("Invalid diagnostic capacity accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        try { new ReadDiagnostics(1, null); throw new AssertionError("Null diagnostic callback accepted"); }
        catch (NullPointerException expected) { checks++; }
        ReadDiagnostics empty = new ReadDiagnostics(1, (utc, elapsed, samples) -> {});
        check(empty.capturedReadCount() == 0 && empty.overflowReadCount() == 0, "Diagnostic storage starts empty");
        try { empty.sampleCount(0); throw new AssertionError("Uncaptured diagnostic entry accepted"); }
        catch (IndexOutOfBoundsException expected) { checks++; }
        try { new Fmcomms2SampleSource(config(1), (ReadDiagnostics) null); throw new AssertionError("Null diagnostic log accepted"); }
        catch (NullPointerException expected) { checks++; }

        Path folder = Files.createTempDirectory("jsat-read-diagnostics-");
        try {
            int[] actual = {4, 1, 2, 4}, callbacks = new int[1];
            int[] callbackCounts = new int[actual.length];
            long[] callbackTimes = new long[actual.length], callbackElapsed = new long[actual.length];
            AtomicReference<Fmcomms2SampleSource> owned = new AtomicReference<>();
            ReadDiagnostics diagnostics = new ReadDiagnostics(2, (utc, elapsed, samples) -> {
                int index = callbacks[0]++;
                callbackCounts[index] = samples;
                callbackTimes[index] = utc;
                callbackElapsed[index] = elapsed;
                if (callbacks[0] == actual.length) owned.get().requestStop();
            });
            try (Server server = new Server(xml("le:S12/16>>0", 0), (s, request, turn) -> {
                int count = actual[turn] * 4;
                if (turn == 0) {
                    s.send("8\n" + s.mask + "\n"); s.fragment(new byte[8]);
                    s.send("8\n"); s.fragment(new byte[8]);
                } else {
                    s.send(count + "\n" + s.mask + "\n"); s.fragment(new byte[count]);
                    if (count < request) s.send("0\n");
                }
            }); Fmcomms2SampleSource source = new Fmcomms2SampleSource(config(server.port(),
                    "recording.enabled", true, "recording.folder", folder.toString()), diagnostics)) {
                owned.set(source);
                AtomicInteger sinkCalls = new AtomicInteger();
                source.setSampleSink(block -> sinkCalls.incrementAndGet());
                long before = UtcTime.nowNanos();
                source.start();
                check(source.awaitCompletion(Duration.ofSeconds(5)), "Diagnostic callback can request cancellation");
                source.stop();
                long after = UtcTime.nowNanos();
                check(source.failure().isEmpty() && source.state() == ComponentState.STOPPED, "Count-only acquisition joins normally");
                check(sinkCalls.get() == 0, "Count-only mode never delivers FFT/storage/decoder sample blocks");
                check(callbacks[0] == 4 && Arrays.equals(callbackCounts, actual), "One diagnostic callback per whole READBUF with actual short-read counts");
                check(diagnostics.capacity() == 2 && diagnostics.capturedReadCount() == 2, "Diagnostic primitive storage remains bounded");
                check(diagnostics.overflowReadCount() == 2, "Every omitted read is explicitly accounted after diagnostic overflow");
                check(diagnostics.sampleCount(0) == 4 && diagnostics.sampleCount(1) == 1, "Log retains original read counts without overwrite");
                check(diagnostics.elapsedNanos(0) == 0 && callbackElapsed[0] == 0, "Monotonic diagnostic offsets start at first completion");
                for (int i = 0; i < callbacks[0]; i++) {
                    check(callbackTimes[i] >= before && callbackTimes[i] <= after, "Read completion is host UTC epoch time");
                    if (i > 0) check(callbackElapsed[i] >= callbackElapsed[i - 1], "Read completion offsets increase monotonically");
                    if (i < diagnostics.capturedReadCount()) {
                        check(diagnostics.completionTimeNanos(i) == callbackTimes[i] && diagnostics.elapsedNanos(i) == callbackElapsed[i],
                                "Retained entry matches the completion callback");
                    }
                }
                check(source.monitoringSnapshot().values().get("source.samples").equals(0L), "Diagnostic reads do not change successful sink-delivery count");
                try (var files = Files.list(folder)) { check(files.findAny().isEmpty(), "Count-only mode bypasses configured recording entirely"); }
            }
        } finally { Files.delete(folder); }

        IllegalStateException listenerFailure = new IllegalStateException("diagnostic listener failure");
        ReadDiagnostics failing = new ReadDiagnostics(1, (utc, elapsed, samples) -> { throw listenerFailure; });
        try (Server server = new Server(xml("le:S12/16>>0", 0), (s, count, turn) -> {
            s.send(count + "\n" + s.mask + "\n"); s.fragment(new byte[count]);
        }); Fmcomms2SampleSource source = new Fmcomms2SampleSource(config(server.port()), failing)) {
            source.setSampleSink(block -> { throw new AssertionError("Diagnostic sink invoked"); });
            source.start(); check(source.awaitCompletion(Duration.ofSeconds(5)), "Diagnostic listener failure terminates acquisition"); source.stop();
            check(source.state() == ComponentState.FAILED && source.failure().orElseThrow() == listenerFailure, "Diagnostic listener exception is retained as source failure");
            check(failing.capturedReadCount() == 1 && failing.sampleCount(0) == 4, "Read is logged before its listener is invoked");
        }

        ReadDiagnostics invalid = new ReadDiagnostics(1, (utc, elapsed, samples) -> { throw new AssertionError("Invalid scan reported"); });
        try (Server server = new Server(xml("le:S12/16>>0", 0), (s, count, turn) -> {
            s.send("6\n" + s.mask + "\n"); s.fragment(new byte[6]); s.send("0\n");
        }); Fmcomms2SampleSource source = new Fmcomms2SampleSource(config(server.port()), invalid)) {
            source.setSampleSink(block -> {}); source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)), "Misaligned diagnostic read terminates acquisition"); source.stop();
            check(source.failure().isPresent() && invalid.capturedReadCount() == 0, "Only validated whole complex scans are counted in diagnostics");
        }
    }
    private static void streaming() throws Exception {
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,request,turn)->{
            byte[] data=turn==0?new byte[]{1,0,2,0,3,0,4,0,5,0,6,0,7,0,8,0}:new byte[]{(byte)0xff,(byte)0xff,0,(byte)0xf8};
            if(turn==0) {
                s.send("8\n"+s.mask+"\n"); s.fragment(Arrays.copyOfRange(data,0,8));
                s.send("8\n"); s.fragment(Arrays.copyOfRange(data,8,16));
            } else { s.send("4\n"+s.mask+"\n"); s.fragment(data); s.send("0\n"); }
        }); var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            List<ComplexSampleBlock> blocks=new ArrayList<>();
            source.setSampleSink(block->{blocks.add(block); if(block.sampleIndex()+block.complexSamples()==5) source.requestStop();});
            long utc=UtcTime.nowNanos(); source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)),"Streaming completes after callback cancellation"); source.stop();
            check(source.failure().isEmpty()&&source.state()==ComponentState.STOPPED,"Intentional socket cancellation is not failure");
            check(blocks.size()==3&&blocks.get(2).complexSamples()==1,"Chunked READBUF and short final response");
            check(blocks.get(0).i(0)==1/2048f&&blocks.get(0).q(1)==4/2048f,"Exact first IQ block");
            check(blocks.get(1).i(0)==5/2048f&&blocks.get(2).i(0)==-1/2048f&&blocks.get(2).q(0)==-1f,"Immutable blocks and signed samples");
            for(var block:blocks) {
                check(block.sampleRate()==3000000,"Verified source rate");
                check(block.timestampNanos()-blocks.get(0).timestampNanos()==(long)(block.sampleIndex()/3000000.0*1e9),"Contiguous UTC sample clock");
            }
            check(Math.abs(blocks.get(0).timestampNanos()-utc)<2_000_000_000L,"Host UTC epoch anchor");
            var status=source.monitoringSnapshot().values();
            check(status.get("source.samples").equals(5L)&&status.get("source.blocks").equals(3L),"Delivery counters");
            check(status.get("source.active").equals(false)&&status.get("source.connected").equals(false),"Terminal activity cleared");
            check(server.written("iio:device0 INPUT voltage0 hardwaregain").equals("42.5"),"Manual gain configured");
            check(server.written("iio:device0 OUTPUT altvoltage0 frequency").equals("2400000000"),"LO configured");
            check(server.written("iio:device0 in_out_voltage_filter_fir_en").equals("0"),"Off disables FIR");
            check(server.requests.contains("SET iio:device1 BUFFERS_COUNT 4")&&server.mask.equals("00000003"),"Kernel buffers and RX1 open mask");
            check(server.written("iio:device0 INPUT voltage0 quadrature_tracking_en").equals("1"),"Correction setting");
            source.close(); check(source.state()==ComponentState.CLOSED,"Idempotent close");
        }
    }
    private static void decimation() throws Exception {
        String rateKey="iio:device1 INPUT voltage0 sampling_frequency";
        for(int factor:List.of(2,4,8,16)) {
            // Exercise both capability-list discovery and older writable-rate-only firmware.
            for(boolean advertised:List.of(true,false)) {
                String attrs="<attribute name='sampling_frequency'/>"+
                    (advertised?"<attribute name='sampling_frequency_available'/>":"");
                String metadata=xml("le:S12/16>>0",0).replace("<scan-element index='0'",attrs+"<scan-element index='0'");
                try(Server server=new Server(metadata,(s,n,t)->{s.send(n+"\n"+s.mask+"\n");s.fragment(new byte[n]);});
                    var source=new Fmcomms2SourceFactory().create(config(server.port(),"sample_rate",4000000,"fpga_decimation",factor))) {
                    server.writes.put(rateKey,"4000000\0".getBytes(StandardCharsets.US_ASCII));
                    server.writes.put(rateKey+"_available","4000000 2000000 1000000 500000 250000\n".getBytes(StandardCharsets.US_ASCII));
                    List<ComplexSampleBlock> blocks=new ArrayList<>();
                    source.setSampleSink(block->{blocks.add(block); if(blocks.size()==2) source.requestStop();});
                    source.start(); check(source.awaitCompletion(Duration.ofSeconds(5)),"Decimated acquisition completes"); source.stop();
                    int output=4000000/factor;
                    check(source.failure().isEmpty()&&blocks.size()==2,"Native decimation x"+factor+" succeeds");
                    check(server.written(rateKey).equals(Integer.toString(output)),"ADC output rate programmed");
                    check(server.written("iio:device0 INPUT voltage0 sampling_frequency").equals("4000000"),"PHY stays at 4 Msps");
                    check(blocks.get(0).sampleRate()==output&&blocks.get(1).sampleRate()==output,"Decoder sees output rate");
                    check(blocks.get(1).timestampNanos()-blocks.get(0).timestampNanos()==(long)(2.0/output*1e9),"Decimated UTC clock");
                    var status=source.monitoringSnapshot().values();
                    check(status.get("source.sample_rate").equals((double)output)&&status.get("source.phy_sample_rate").equals(4000000.0)
                        &&status.get("source.fpga_decimation").equals(factor),"Distinct PHY/output/factor monitoring");
                }
            }
        }
        for(int mode=0;mode<5;mode++) {
            String attrs=mode==0?"":"<attribute name='sampling_frequency'/>"+
                (mode==1||mode==4?"<attribute name='sampling_frequency_available'/>":"");
            String metadata=xml("le:S12/16>>0",0).replace("<scan-element index='0'",attrs+"<scan-element index='0'");
            try(Server server=new Server(metadata,(s,n,t)->{throw new AssertionError("Unsupported decimator opened");});
                var source=new Fmcomms2SourceFactory().create(config(server.port(),"sample_rate",4000000,"fpga_decimation",16))) {
                server.writes.put(rateKey,"4000000".getBytes(StandardCharsets.US_ASCII));
                server.writes.put(rateKey+"_available",(mode==4?"invalid":"4000000 500000").getBytes(StandardCharsets.US_ASCII));
                if(mode==2) server.rejectWrite="iio:device1 INPUT voltage0 sampling_frequency";
                if(mode==3) server.adcRateOverride="500000";
                source.setSampleSink(block->{throw new AssertionError("Samples delivered after setup failure");});
                try { source.start(); throw new AssertionError("Unsupported hardware accepted"); }
                catch(SourceException expected) {
                    check(expected.getMessage().contains("native FPGA decimation x16")&&expected.getMessage().contains("250000"),"Actionable factor/rate startup exception");
                }
                check(source.state()==ComponentState.FAILED&&source.failure().isPresent(),"Unsupported decimation retained as failure");
                check(server.requests.stream().noneMatch(r->r.startsWith("OPEN ")),"No stream opens on unsupported hardware");
            }
        }
        Path properties=Files.createTempFile("fmcomms2-unsupported-", ".properties");
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{throw new AssertionError("Unsupported CLI source opened");})) {
            Files.writeString(properties,"source.type=fmcomms2\nsource.uri=ip:127.0.0.1:"+server.port()+
                "\nsource.sample_rate=4000000\nsource.fpga_decimation=16\ndecoder.type=dummy\nui.enabled=false\n");
            try {
                eu.dariolucia.jsatdecode.cli.Main.main(new String[]{"--config",properties.toString(),"--headless"});
                throw new AssertionError("CLI swallowed unsupported-hardware exception");
            } catch(SourceException expected) {
                check(expected.getMessage().contains("ADC sampling_frequency attribute is missing"),"CLI propagates actionable source failure after runtime cleanup");
            }
            check(server.requests.stream().noneMatch(r->r.startsWith("OPEN ")),"CLI never starts unsupported stream");
        } finally { Files.delete(properties); }
    }
    private static void fileFilter() throws Exception {
        Path filter=Files.createTempFile("fmcomms2-",".ftr"); Files.writeString(filter,"RX 3 GAIN -6 DEC 4\nTX 3 GAIN 0 INT 4\n1,1\n");
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{s.send(n+"\n"+s.mask+"\n");s.fragment(new byte[n]);});
            var source=new Fmcomms2SourceFactory().create(config(server.port(),"rx1_en",false,"rx2_en",true,"gain2","slow_attack",
                "filter_source","File","filter",filter.toString(),"sample_rate",1000000))) {
            source.setSampleSink(block->source.requestStop()); source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)),"File filter stream starts"); source.stop();
            check(source.failure().isEmpty(),"File filter accepted");
            check(Arrays.equals(server.writes.get("iio:device0 filter_fir_config"),Files.readAllBytes(filter)),"FIR bytes sent raw, without added NUL");
            check(server.mask.equals("0000000c"),"RX2 mask");
            check(server.written("iio:device0 INPUT voltage1 gain_control_mode").equals("slow_attack"),"Selected RX2 gain control");
            check(!server.writes.containsKey("iio:device0 INPUT voltage1 hardwaregain"),"Manual gain not written for AGC");
        } finally { Files.delete(filter); }
    }
    private static void failures() throws Exception {
        for(int mode=0;mode<5;mode++) {
            final int scenario=mode;
            try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{
                switch(scenario) {
                    case 0 -> s.send("-75\n");
                    case 1 -> s.send((n+1)+"\n");
                    case 2 -> s.send(n+"\n000000ff\n");
                    case 3 -> { s.send(n+"\n"+s.mask+"\n"); s.fragment(new byte[]{1}); s.connection.close(); }
                    case 4 -> { s.pending.countDown(); s.input.read(); }
                    default -> throw new AssertionError();
                }
            }); var source=new Fmcomms2SourceFactory().create(config(server.port(),"server_timeout_ms",100,"read_timeout_ms",200))) {
                source.setSampleSink(block->{throw new AssertionError("Invalid protocol data delivered");}); source.start();
                check(source.awaitCompletion(Duration.ofSeconds(5)),"Protocol/read failure terminates"); source.stop();
                check(source.state()==ComponentState.FAILED&&source.failure().isPresent(),"Failure retained");
                if(mode==0) check(source.monitoringSnapshot().values().get("source.reported_overruns").equals(1L),"Reported EOVERFLOW counted");
                source.close(); check(source.failure().isPresent(),"Failure survives close");
            }
        }
        try(Server server=new Server("<invalid/>",(s,n,t)->{});var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            source.setSampleSink(block->{});
            try { source.start(); throw new AssertionError("Invalid context accepted"); } catch(SourceException expected) { checks++; }
            check(source.state()==ComponentState.FAILED&&server.writes.isEmpty(),"Invalid XML fails before hardware writes");
        }
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            server.rejectWrite="gain_control_mode"; source.setSampleSink(block->{});
            try { source.start(); throw new AssertionError("Rejected setting accepted"); } catch(SourceException expected) { checks++; }
            check(source.state()==ComponentState.FAILED&&source.failure().orElseThrow().getMessage().contains("errno 22"),"Negative attribute errno retained");
        }
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            server.rateOverride="3000001"; source.setSampleSink(block->{});
            try { source.start(); throw new AssertionError("Wrong readback rate accepted"); } catch(SourceException expected) { checks++; }
            check(source.state()==ComponentState.FAILED,"Hardware sample rate mismatch cannot silently mislabel samples");
        }
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{s.send(n+"\n"+s.mask+"\n");s.fragment(new byte[n]);});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            source.setSampleSink(block->{throw new IllegalStateException("Deliberate sink failure");}); source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)),"Sink failure terminates");
            check(source.failure().orElseThrow().getMessage().equals("Deliberate sink failure"),"Sink diagnostic retained");
        }
        String withAdcRate=xml("le:S12/16>>0",0).replace("<scan-element index='0'", "<attribute name='sampling_frequency'/><scan-element index='0'");
        try(Server server=new Server(withAdcRate,(s,n,t)->{s.send(n+"\n"+s.mask+"\n");s.fragment(new byte[n]);});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            server.writes.put("iio:device1 INPUT voltage0 sampling_frequency","375000\0".getBytes(StandardCharsets.US_ASCII));
            source.setSampleSink(block->source.requestStop()); source.start();
            check(source.awaitCompletion(Duration.ofSeconds(5)),"ADC decimation reset stream starts"); source.stop();
            check(server.written("iio:device1 INPUT voltage0 sampling_frequency").equals("3000000"),"Advertised stale FPGA decimation disabled");
            check(source.failure().isEmpty(),"ADC streaming rate verified");
        }
    }
    private static void cancellation() throws Exception {
        ReadDiagnostics preCancelled = new ReadDiagnostics(1, (utc, elapsed, samples) -> {
            throw new AssertionError("Pre-cancelled source completed a read");
        });
        try (Server server = new Server(xml("le:S12/16>>0", 0), (s, n, t) -> {});
             Fmcomms2SampleSource source = new Fmcomms2SampleSource(config(server.port()), preCancelled)) {
            source.setSampleSink(block -> { throw new AssertionError("Pre-cancelled source delivered samples"); });
            source.requestStop(); source.start();
            check(source.awaitCompletion(Duration.ZERO), "Pre-cancelled start completes without pending acquisition");
            source.stop();
            check(source.state() == ComponentState.STOPPED && source.failure().isEmpty(), "Pre-cancelled start retains normal cancellation state");
            check(server.connection == null && server.requests.isEmpty(), "Pre-cancelled start performs no network connection or configuration writes");
            check(preCancelled.capturedReadCount() == 0, "Pre-cancelled diagnostic log stays empty");
        }
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{s.pending.countDown();s.input.read();});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            source.setSampleSink(block->{}); source.start();
            check(server.pending.await(3,TimeUnit.SECONDS),"Worker blocked in READBUF");
            long begin=System.nanoTime(); source.stop();
            check(System.nanoTime()-begin<1_000_000_000L,"Socket close cancels read without waiting for I/O timeout");
            check(source.failure().isEmpty()&&source.state()==ComponentState.STOPPED,"Clean joined cancellation");
        }
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{});
            var source=new Fmcomms2SourceFactory().create(config(server.port()))) {
            server.pausePrint=true; source.setSampleSink(block->{});
            AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread starter=new Thread(()->{try {source.start();}catch(Throwable e){failure.set(e);}},"test-source-start");
            starter.start(); check(server.pending.await(3,TimeUnit.SECONDS),"Startup blocked in PRINT");
            source.stop(); starter.join(2000);
            check(!starter.isAlive()&&failure.get()==null&&source.failure().isEmpty(),"Concurrent stop cancels startup");
        }
    }
    private static void throughput() throws Exception {
        int buffers=32,bufferSamples=65536;
        try(Server server=new Server(xml("le:S12/16>>0",0),(s,n,t)->{s.send(n+"\n"+s.mask+"\n");s.output.write(new byte[n]);s.output.flush();});
            var source=new Fmcomms2SourceFactory().create(config(server.port(),"buffer_size",bufferSamples,"block_samples",4096))) {
            AtomicLong total=new AtomicLong(); source.setSampleSink(block->{if(total.addAndGet(block.complexSamples())==buffers*(long)bufferSamples)source.requestStop();});
            long begin=System.nanoTime(); source.start(); check(source.awaitCompletion(Duration.ofSeconds(15)),"Bounded streaming throughput run completes"); source.stop();
            double wall=(System.nanoTime()-begin)*1e-9;
            check(source.failure().isEmpty()&&total.get()==buffers*(long)bufferSamples,"All throughput samples delivered");
            System.out.printf(Locale.ROOT,"FMComms2 loopback: %.2f Msamples/s, %.3f s, no DSP/native hardware claim%n",total.get()/wall/1e6,wall);
        }
    }
    static String xml(String format,int extras) {
        StringBuilder phy=new StringBuilder();
        for(int channel=0;channel<2;channel++) {
            phy.append("<channel id='voltage").append(channel).append("' type='input'>");
            for(String attr:List.of("sampling_frequency","rf_bandwidth","rf_port_select","hardwaregain","gain_control_mode","quadrature_tracking_en","rf_dc_offset_tracking_en","bb_dc_offset_tracking_en"))
                phy.append("<attribute name='").append(attr).append("'/>");
            phy.append("</channel>");
        }
        StringBuilder adc=new StringBuilder("<channel id='temp' type='input'/>");
        int[] order={3,0,2,1},index={0,1,4,7};
        for(int channel:order) adc.append("<channel id='voltage").append(channel).append("' type='input'><scan-element index='")
            .append(index[channel]).append("' format='").append(format).append("'/></channel>");
        for(int i=0;i<extras;i++) adc.append("<channel id='extra").append(i).append("' type='input'/>");
        return "<?xml version='1.0'?><!DOCTYPE context []><context name='local'><device id='iio:device0' name='ad9361-phy'>"+phy+
            "<channel id='altvoltage0' type='output'><attribute name='frequency'/></channel><attribute name='in_out_voltage_filter_fir_en'/><attribute name='filter_fir_config'/></device>"+
            "<device id='iio:device1' name='cf-ad9361-lpc'>"+adc+"</device></context>";
    }
    @FunctionalInterface interface StreamReply { void reply(Server server,int bytes,int turn) throws Exception; }
    static final class Server implements AutoCloseable {
        final ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress());
        final List<String> requests=new CopyOnWriteArrayList<>();
        final Map<String,byte[]> writes=new ConcurrentHashMap<>();
        final CountDownLatch pending=new CountDownLatch(1);
        final Thread thread;
        volatile Socket connection;
        volatile InputStream input;
        volatile OutputStream output;
        volatile String mask;
        volatile Throwable failure;
        volatile String rejectWrite,rateOverride,adcRateOverride,loOverride;
        volatile boolean pausePrint;
        Server(String xml,StreamReply reply) throws IOException {
            thread=new Thread(()->serve(xml,reply),"test-iiod"); thread.start();
        }
        int port() { return listener.getLocalPort(); }
        String written(String attribute) { return new String(writes.get(attribute),StandardCharsets.US_ASCII).replace("\0",""); }
        void serve(String xml,StreamReply reply) {
            try(Socket socket=listener.accept()) {
                connection=socket; socket.setTcpNoDelay(true); input=socket.getInputStream(); output=socket.getOutputStream();
                int turn=0;
                for(String command;(command=readLine())!=null;) {
                    requests.add(command);
                    if(command.startsWith("TIMEOUT ")||command.startsWith("SET ")) send("0\n");
                    else if(command.equals("PRINT")) {
                        if(pausePrint) { pending.countDown(); input.read(); break; }
                        byte[] bytes=xml.getBytes(StandardCharsets.UTF_8); send("\n"+bytes.length+"\n"); fragment(bytes); send("\n");
                    } else if(command.startsWith("WRITE ")) {
                        int split=command.lastIndexOf(' '),length=Integer.parseInt(command.substring(split+1));
                        byte[] value=input.readNBytes(length); if(value.length!=length) throw new EOFException();
                        writes.put(command.substring(6,split),value);
                        send(rejectWrite!=null&&command.contains(rejectWrite)?"-22\n":length+"\n");
                    } else if(command.startsWith("READ ")) {
                        byte[] value=writes.get(command.substring(5));
                        if(rateOverride!=null&&command.endsWith(" sampling_frequency")) value=rateOverride.getBytes(StandardCharsets.US_ASCII);
                        if(adcRateOverride!=null&&command.equals("READ iio:device1 INPUT voltage0 sampling_frequency")) value=adcRateOverride.getBytes(StandardCharsets.US_ASCII);
                        if(loOverride!=null&&command.equals("READ iio:device0 OUTPUT altvoltage0 frequency")) value=loOverride.getBytes(StandardCharsets.US_ASCII);
                        send(value.length+"\n"); fragment(value); send("\n");
                    } else if(command.startsWith("OPEN ")) { mask=command.split(" ")[3]; send("0\n"); }
                    else if(command.startsWith("READBUF ")) reply.reply(this,Integer.parseInt(command.substring(command.lastIndexOf(' ')+1)),turn++);
                    else throw new IOException("Unexpected client command: "+command);
                }
            } catch(SocketException ignored) { /* intentional peer/server cancellation */ }
            catch(Throwable e) { failure=e; }
        }
        String readLine() throws IOException {
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            for(int b;(b=input.read())>=0;) { if(b=='\n') return bytes.toString(StandardCharsets.US_ASCII).strip(); bytes.write(b); }
            return null;
        }
        void send(String text) throws IOException { fragment(text.getBytes(StandardCharsets.US_ASCII)); }
        void fragment(byte[] bytes) throws IOException {
            for(int n=0;n<bytes.length;n+=7) output.write(bytes,n,Math.min(7,bytes.length-n)); output.flush();
        }
        @Override public void close() throws Exception {
            listener.close(); if(connection!=null) connection.close(); thread.join(3000);
            if(thread.isAlive()) throw new AssertionError("Test daemon did not stop");
            if(failure!=null) throw new AssertionError("Test daemon failed",failure);
        }
    }
}
