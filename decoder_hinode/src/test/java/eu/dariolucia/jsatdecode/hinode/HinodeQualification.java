package eu.dariolucia.jsatdecode.hinode;

import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.source.wav.*;

/** Explicit, external-data acceptance harness. Never runs as a synthetic unit test. */
public final class HinodeQualification {
    private HinodeQualification() { }
    /** Takes reference IQ WAV and accepted concatenated 892-byte transfer-frame file.
     * Runs UI OFF twice and the real desktop UI ON once; requires exact reference
     * bytes/order, over 300 frames, and each run faster than twice signal time.
     * All file writes and reporting occur on this management thread after DSP stops.
     */
    public static void main(String[] args) throws Exception {
        if(args.length!=2) throw new IllegalArgumentException("Expected reference.wav accepted-frames.bin");
        byte[] expected=Files.readAllBytes(Path.of(args[1]));
        if(expected.length%892!=0 || expected.length/892<=300 || expected.length>892*10000)
            throw new IllegalArgumentException("Expected 301..10000 accepted 892-byte frames");
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(expected));
        System.out.println("reference_frames="+expected.length/892+" sha256="+hash);
        for(int run=0;run<3;run++) {
            boolean ui=run==2;
            List<byte[]> frames=new ArrayList<>();
            long[] sampleCount={0};
            try(HinodeDecoder decoder=new HinodeDecoder(new Configuration(Map.of()));
                WavSampleSource source=new WavSourceFactory().create(new Configuration(Map.of("file",args[0])))) {
                decoder.setFrameSink(frame->{
                    if(frames.size()==10000) throw new IllegalStateException("Qualification frame bound exceeded");
                    frames.add(frame.data());
                });
                source.setSampleSink(block->{decoder.accept(block); sampleCount[0]+=block.complexSamples();});
                decoder.start();
                try(AutoCloseable desktop=ui?HinodeDesktopSession.open(decoder):()->{}) {
                    long start=System.nanoTime(); source.start();
                    if(!source.awaitCompletion(Duration.ofMinutes(10))) throw new IllegalStateException("Source timeout");
                    source.failure().ifPresent(problem->{throw new IllegalStateException("Source failed",problem);});
                    decoder.stop();
                    double elapsed=(System.nanoTime()-start)*1e-9, signal=sampleCount[0]/500000.0, speed=signal/elapsed;
                    byte[] actual=new byte[frames.size()*892];
                    for(int i=0;i<frames.size();i++) {
                        if(frames.get(i).length!=892) throw new AssertionError("Unexpected frame size");
                        System.arraycopy(frames.get(i),0,actual,i*892,892);
                    }
                    System.out.printf(Locale.ROOT,"run=%d ui=%s frames=%d signal=%.6f wall=%.6f speed=%.3fx%n",run,ui,frames.size(),signal,elapsed,speed);
                    if(!Arrays.equals(expected,actual)) throw new AssertionError("Frame bytes/count/order differ at byte "+Arrays.mismatch(expected,actual));
                    if(speed<=2) throw new AssertionError("Throughput must exceed 2x real time");
                    if(signal<89 || signal>91) throw new AssertionError("Expected the 90-second reference segment");
                }
            }
        }
        System.out.println("PASS: repeated and desktop UI frames match reference; all runs >2x");
    }
}
