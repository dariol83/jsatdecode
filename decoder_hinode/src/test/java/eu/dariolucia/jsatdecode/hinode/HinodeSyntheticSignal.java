package eu.dariolucia.jsatdecode.hinode;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;

import java.util.*;
import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import ru.r2cloud.jradio.fec.Viterbi;

/** Generated residual-carrier PM signal, for DSP smoke checks and local profiling. */
public final class HinodeSyntheticSignal {
    private HinodeSyntheticSignal() { }
    /** Runs an optional ten-second generated signal; no reference-data claim. */
    public static void main(String[] args) {
        int frames=run(10);
        if(frames==0) throw new AssertionError("Generated PM signal produced no corrected frames");
    }
    static int run(int seconds) {
        return run(seconds,.017,false);
    }
    static int run(int seconds,double radiansPerSample,boolean verifyOffset) {
        // Continuous convolutional coding of repeated CADUs. Encoder tail bytes
        // are excluded; coding state continues across frame boundaries.
        byte[] uncoded=new byte[1024*50];
        for(int i=0;i<50;i++) System.arraycopy(HinodeTest.cadu(i+1,0),0,uncoded,i*1024,1024);
        byte[] coded=Viterbi.encode(uncoded,(byte)0x4f,(byte)0x6d,true);
        int[] frames={0};
        boolean[] measured={false};
        long start=System.nanoTime();
        try(HinodeDecoder decoder=new HinodeDecoder(new Configuration(Map.of()))) {
            HinodeTest.statusContract(decoder);
            HinodeTest.check(Double.isNaN((Double)decoder.monitoringSnapshot().values().get(DecoderStatusKeys.CARRIER_OFFSET_HZ)),"Offset unavailable before start");
            decoder.setFrameSink(frame->frames[0]++); decoder.start();
            for(int sample=0;sample<seconds*500000;sample+=4096) {
                int count=Math.min(4096,seconds*500000-sample);
                float[] iq=new float[count*2];
                for(int n=0;n<count;n++) {
                    long index=sample+n,bit=index*65600/500000;
                    int value=((coded[(int)(bit/8)]>>>(7-(int)(bit%8)))&1)==0?-1:1;
                    double phase=.6*value*Math.cos(2*Math.PI*65600*index/500000)+radiansPerSample*index;
                    iq[n*2]=(float)Math.cos(phase); iq[n*2+1]=(float)Math.sin(phase);
                }
                decoder.accept(new ComplexSampleBlock(iq,count,sample,sample*2000L,500000));
                if(verifyOffset && (sample+count)%250000<count) {
                    var status=decoder.monitoringSnapshot().values();
                    double offset=(Double)status.get(DecoderStatusKeys.CARRIER_OFFSET_HZ);
                    if(status.get(DecoderStatusKeys.CARRIER_LOCK).equals("LOCKED")) {
                        // The instantaneous carrier PLL retains PM/data ripple;
                        // tolerate that residual while checking conversion/sign.
                        HinodeTest.check(Double.isFinite(offset) && Math.abs(offset-radiansPerSample*500000/(2*Math.PI))<400,
                            "Locked offset matches injected carrier in Hz with correct sign: "+offset);
                        measured[0]=true;
                    } else HinodeTest.check(Double.isNaN(offset),"Offset unavailable while searching/unlocked");
                }
            }
            if(verifyOffset) HinodeTest.check(measured[0],"Synthetic signal acquired carrier lock");
            HinodeTest.statusContract(decoder);
            HinodeTest.check(decoder.monitoringSnapshot().values().get(DecoderStatusKeys.FRAME_GOOD).equals((long)frames[0]),"Good count equals emitted frames");
            decoder.stop();
            HinodeTest.statusContract(decoder);
            HinodeTest.check(Double.isNaN((Double)decoder.monitoringSnapshot().values().get(DecoderStatusKeys.CARRIER_OFFSET_HZ)),"Offset unavailable after stop");
        }
        double wall=(System.nanoTime()-start)*1e-9;
        System.out.printf(Locale.ROOT,"synthetic signal=%ds frames=%d wall=%.3fs speed=%.2fx (includes generation)%n",seconds,frames[0],wall,seconds/wall);
        return frames[0];
    }
}
