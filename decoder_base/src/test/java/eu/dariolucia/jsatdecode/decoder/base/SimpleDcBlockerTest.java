package eu.dariolucia.jsatdecode.decoder.base;

import eu.dariolucia.jsatdecode.decoder.base.dsp.SimpleDcBlocker;

/** Single-pole startup, response, independent history and exact bypass checks. */
public final class SimpleDcBlockerTest {
    private SimpleDcBlockerTest() { }
    /** Runs without hardware or external DSP dependencies.
     * @param args unused
     */
    public static void main(String[] args) {
        SimpleDcBlocker filter=new SimpleDcBlocker();
        check(filter.i()==0&&filter.q()==0,"Zero initial outputs");
        filter.sample(1,-2);
        check(filter.i()==.9999f&&filter.q()== -1.9998f,"Original Hinode startup");
        for(int n=1;n<1000;n++) filter.sample(1,-2);
        check(Math.abs(filter.i()-Math.pow(.9999,1000))<.00001,"Single-pole step response");
        check(filter.q()== -2*filter.i(),"Independent channel scaling");
        for(int n=1000;n<200000;n++) filter.sample(1,-2);
        check(Math.abs(filter.i())<.001&&Math.abs(filter.q())<.002,"Constant DC rejection");

        SimpleDcBlocker impulse=new SimpleDcBlocker(true);
        impulse.sample(1,0);
        impulse.sample(0,0);
        check(Math.abs(impulse.i()+.00009999)<1e-11&&impulse.q()==0,"Retained history without I/Q coupling");
        check(new SimpleDcBlocker().i()==0,"Fresh instance has independent history");

        SimpleDcBlocker bypass=new SimpleDcBlocker(false);
        for(int bits:new int[]{0,0x80000000,0x3fa00000,0xc2280000,1,0x7fc01234,0x7f800000}) {
            float value=Float.intBitsToFloat(bits);
            bypass.sample(value,-value);
            check(Float.floatToRawIntBits(bypass.i())==bits
                &&Float.floatToRawIntBits(bypass.q())==Float.floatToRawIntBits(-value),"Bit-exact bypass");
        }
        System.out.println("SimpleDcBlocker checks passed");
    }
    private static void check(boolean condition,String message) {
        if(!condition) throw new AssertionError(message);
    }
}
