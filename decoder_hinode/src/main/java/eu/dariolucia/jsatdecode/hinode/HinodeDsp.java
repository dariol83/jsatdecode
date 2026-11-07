package eu.dariolucia.jsatdecode.hinode;

import eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker;

import ru.r2cloud.jradio.blocks.MMSEFIRInterpolator;
import ru.r2cloud.jradio.util.CircularComplexArray;

/**
 * Sample-worker-owned PM/BPSK receiver. Timing equations adapted from jradio's Apache-2.0
 * ClockRecoveryMMComplex; interpolation tables remain in jradio.
 */
final class HinodeDsp {
    interface Output {
        void baseband(float i, float q);

        void symbol(float i, float q);
    }

    private static final float[] RRC = {
        .003561560297f, .004399490543f, .002868671203f, -.001375013846f,
        -.00765449414f, -.01418049261f, -.01830869913f, -.01712624915f,
        -.008240184747f, .009439719841f, .03515513241f, .06617614627f,
        .09818095714f, .1260803193f, .1450993717f, .1518475264f,
        .1450993717f, .1260803193f, .09818095714f, .06617614627f,
        .03515513241f, .009439719841f, -.008240184747f, -.01712624915f,
        -.01830869913f, -.01418049261f, -.00765449414f, -.001375013846f,
        .002868671203f, .004399490543f, .003561560297f
    };
    final Loop carrier = new Loop(.03f, .5f), costas = new Loop(.02f, 1);
    private final float[] firI = new float[31], firQ = new float[31], interpolated = new float[2];
    private final MMSEFIRInterpolator interpolator = new MMSEFIRInterpolator();
    private final CircularComplexArray history = new CircularComplexArray(8);
    private final Output output;
    private final ComplexDcBlocker dcblocker;
    private float gain = 1, mu = .5f, omega = 500000f / 65600;
    private float p1I, p1Q, p2I, p2Q, c1I, c1Q, c2I, c2Q;
    private float rotationI = 1, rotationQ;
    private final float stepI = (float) Math.cos(-2 * Math.PI * 65600 / 500000);
    private final float stepQ = (float) Math.sin(-2 * Math.PI * 65600 / 500000);
    private int firPos, remaining = 6;
    long samples, symbols;

    HinodeDsp(boolean enabled, Output output) {
        this.output = output;
        dcblocker = new ComplexDcBlocker(enabled, 16384);
    }

    void sample(float i, float q) {
        // Initial optional DC removal delegates to jradio before AGC and carrier PLL.
        dcblocker.sample(i, q);
        i = dcblocker.i * gain;
        q = dcblocker.q * gain;
        gain += .001f * (1 - (float) Math.sqrt(i * i + q * q));
        gain = Math.min(65536, gain);
        float cos = (float) Math.cos(carrier.phase), sin = (float) Math.sin(carrier.phase);
        float quadrature = -i * sin + q * cos;
        float error = (float) Math.atan2(q, i) - carrier.phase;
        while (error > Math.PI) error -= 2 * (float) Math.PI;
        while (error < -Math.PI) error += 2 * (float) Math.PI;
        carrier.advance(error);

        // Q-only complex input, multiplied by the -65.6 kHz rotator.
        firI[firPos] = -quadrature * rotationQ;
        firQ[firPos] = quadrature * rotationI;
        float r = rotationI * stepI - rotationQ * stepQ;
        rotationQ = rotationI * stepQ + rotationQ * stepI;
        rotationI = r;
        if ((samples & 511) == 511) {
            float norm = (float) Math.hypot(rotationI, rotationQ);
            rotationI /= norm;
            rotationQ /= norm;
        }
        float filteredI = 0, filteredQ = 0;
        int pos = firPos;
        for (int t = 0; t < 31; t++) {
            filteredI += RRC[t] * firI[pos];
            filteredQ += RRC[t] * firQ[pos];
            if (--pos < 0) pos = 30;
        }
        if (++firPos == 31) firPos = 0;
        cos = (float) Math.cos(costas.phase);
        sin = (float) Math.sin(costas.phase);
        i = filteredI * cos + filteredQ * sin;
        q = -filteredI * sin + filteredQ * cos;
        costas.advance(clip(i * q, 1));
        output.baseband(i, q);

        // Push adaptation of jradio MM recovery: retain the same eight-tap history,
        // 0/1 slicer, three-symbol TED, clipped error and +/-0.5% omega bound.
        history.add(i, q);
        if (--remaining == 0) {
            interpolator.interpolateComplex(interpolated, history, mu);
            float p0I = interpolated[0], p0Q = interpolated[1];
            float c0I = p0I > 0 ? 1 : 0, c0Q = p0Q > 0 ? 1 : 0;
            float ted =
                    (p0I - p2I) * c1I + (p0Q - p2Q) * c1Q - (c0I - c2I) * p1I - (c0Q - c2Q) * p1Q;
            ted = clip(ted, 1);
            boolean acquisition = symbols < 787200;
            omega += (acquisition ? .0001f : .000025f) * ted;
            float mid = 500000f / 65600;
            omega = mid + clip(omega - mid, .03811f);
            mu += omega + (acquisition ? .03f : .01f) * ted;
            remaining = (int) Math.floor(mu);
            mu -= remaining;
            p2I = p1I;
            p2Q = p1Q;
            p1I = p0I;
            p1Q = p0Q;
            c2I = c1I;
            c2Q = c1Q;
            c1I = c0I;
            c1Q = c0Q;
            symbols++;
            output.symbol(5 * p0I, 5 * p0Q);
        }
        if (++samples == 6000000) {
            carrier.bandwidth(.01f);
            costas.bandwidth(.005f);
        }
    }

    static float clip(float value, float limit) {
        return Math.max(-limit, Math.min(limit, value));
    }

    static final class Loop {
        float phase, frequency, alpha, beta;
        final float limit;

        Loop(float bandwidth, float limit) {
            this.limit = limit;
            bandwidth(bandwidth);
        }

        void bandwidth(float bw) {
            float damping = (float) (Math.sqrt(2) / 2), denom = 1 + 2 * damping * bw + bw * bw;
            alpha = 4 * damping * bw / denom;
            beta = 4 * bw * bw / denom;
        }

        void advance(float error) {
            frequency += beta * error;
            phase += frequency + alpha * error;
            if (phase > 2 * Math.PI) phase -= 2 * (float) Math.PI;
            if (phase < -2 * Math.PI) phase += 2 * (float) Math.PI;
            frequency = clip(frequency, limit);
        }
    }
}
