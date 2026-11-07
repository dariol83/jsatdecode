package eu.dariolucia.jsatdecode.elektrol;

import eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker;
import ru.r2cloud.jradio.Context;
import ru.r2cloud.jradio.FloatInput;
import ru.r2cloud.jradio.blocks.AGC;
import ru.r2cloud.jradio.blocks.ControlLoop;
import ru.r2cloud.jradio.blocks.CostasLoop;
import ru.r2cloud.jradio.blocks.FIRFilter;
import ru.r2cloud.jradio.blocks.Firdes;
import ru.r2cloud.jradio.blocks.MMSEFIRInterpolator;
import ru.r2cloud.jradio.blocks.Window;
import ru.r2cloud.jradio.util.CircularComplexArray;
import ru.r2cloud.jradio.util.MathUtils;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Single-owner, bounded streaming port of decoder_elektrol_ggak.grc. Translation to the input centre
 * belongs to the source. The optional DC blocker is the first decoder-owned operation. jradio
 * performs tap design, convolution, AGC, loop control, Costas and MMSE interpolation. The
 * M&M controller adapts the reference equations and its finite-stream lookahead explicitly.
 */
final class ElektroLGgakDsp {
    interface Output {
        void baseband(float i, float q);
        void symbol(float i, float q);
    }

    /** Optional setup-only qualification checkpoint sink; absent from the production path. */
    interface Trace { void sample(String stage, float i, float q); }

    private static final float PLL_BANDWIDTH = .02f;
    private static final float PLL_LIMIT = .5f;
    private final ComplexDcBlocker dcBlocker;
    private final DecimatingFir channel, timing, matched;
    private final ComplexInput agcInput = new ComplexInput();
    private final ComplexInput costasInput = new ComplexInput();
    private final AGC agc = new AGC(agcInput, .01f, 1f, 1f, 65536f);
    private final ControlLoop carrier = new ControlLoop(PLL_BANDWIDTH, PLL_LIMIT, -PLL_LIMIT);
    private final CostasLoop costas = new CostasLoop(costasInput, .005f, 2, false);
    private final TimingInput timingInput = new TimingInput();
    private final MMSEFIRInterpolator interpolator = new MMSEFIRInterpolator();
    private final CircularComplexArray timingHistory = new CircularComplexArray(8);
    private float[] p0 = new float[2], p1 = new float[2], p2 = new float[2];
    private float[] c0 = new float[2], c1 = new float[2], c2 = new float[2];
    private float omega = 10f, mu = .5f;
    private long timingCursor, timingConsumed, timingInputCount;
    private final Output output;
    private final Trace trace;
    private double pmPhase;

    // Primitive stage checkpoints are package-visible to independent qualification harnesses.
    float channelI, channelQ, timingI, timingQ, agcI, agcQ, pllI, pllQ, pmI, pmQ, rrcI, rrcQ;
    long channelSamples, timingSamples, symbols;

    ElektroLGgakDsp(ElektroLGgakSettings settings, Output output) {
        this(settings, output, null);
    }

    ElektroLGgakDsp(ElektroLGgakSettings settings, Output output, Trace trace) {
        this.output = java.util.Objects.requireNonNull(output, "output");
        this.trace = trace;
        dcBlocker = new ComplexDcBlocker(settings.dcblocker(), settings.dcBlockerLength());
        channel = new DecimatingFir(
                Firdes.lowPass(1, settings.sampleRateHz(), 40000, 15000, Window.WIN_HAMMING, 6.76),
                settings.channelDecimation());
        timing = new DecimatingFir(
                Firdes.lowPass(1, settings.channelRate(), 20000, 4000, Window.WIN_HAMMING, 6.76), 5);
        matched = new DecimatingFir(
                Firdes.rootRaisedCosine(1, settings.baseRate(), settings.bitRate(), .5, 31), 1);
    }

    void sample(float inI, float inQ) {
        dcBlocker.sample(inI, inQ);
        if (!channel.add(dcBlocker.i, dcBlocker.q)) return;
        channelI = channel.value[0];
        channelQ = channel.value[1];
        channelSamples++;
        if (trace != null) trace.sample("channel", channelI, channelQ);
        if (!timing.add(channelI, channelQ)) return;
        timingI = timing.value[0];
        timingQ = timing.value[1];
        timingSamples++;
        if (trace != null) trace.sample("timing", timingI, timingQ);
        try {
            agcInput.set(timingI, timingQ);
            agcI = agc.readFloat();
            agcQ = agc.readFloat();
            if (trace != null) trace.sample("agc", agcI, agcQ);

            // Carrier PLL output uses the old phase; update control only after producing it.
            float phase = carrier.getPhase();
            float cosine = (float) Math.cos(phase), sine = (float) Math.sin(phase);
            pllI = agcI * cosine + agcQ * sine;
            pllQ = -agcI * sine + agcQ * cosine;
            float error = MathUtils.fastAtan(agcQ, agcI) - phase;
            if (error > Math.PI) error -= (float) (2 * Math.PI);
            else if (error < -Math.PI) error += (float) (2 * Math.PI);
            carrier.advanceLoop(error);
            if (trace != null) trace.sample("pll", pllI, pllQ);

            // Embedded GRC PM block: j*imaginary(input), continuously shifted by -5000 Hz.
            pmI = (float) (-pllQ * Math.sin(pmPhase));
            pmQ = (float) (pllQ * Math.cos(pmPhase));
            pmPhase -= 2 * Math.PI / 10;
            if (pmPhase < -Math.PI) pmPhase += 2 * Math.PI;
            if (trace != null) trace.sample("pm", pmI, pmQ);
            matched.add(pmI, pmQ);
            rrcI = matched.value[0];
            rrcQ = matched.value[1];
            if (trace != null) trace.sample("rrc", rrcI, rrcQ);
            costasInput.set(rrcI, rrcQ);
            float i = costas.readFloat(), q = costas.readFloat();
            if (!Float.isFinite(i) || !Float.isFinite(q))
                throw new IllegalArgumentException("Elektro-L GGAK DSP amplitude overflow");
            output.baseband(i, q);
            if (trace != null) trace.sample("costas", i, q);

            timingSample(i, q);
        } catch (IOException e) {
            throw new UncheckedIOException("In-memory Elektro-L GGAK DSP failed", e);
        }
    }

    /** M&M input checkpoint, also used by isolated independent GNU Radio qualification. */
    void timingSample(float i, float q) {
        timingInput.add(i, q);
        timingInputCount++;
        recoverSymbol();
    }

    float timingOmega() { return omega; }
    float timingMu() { return mu; }

    private void recoverSymbol() {
        // GNU Radio 3.10.12 MM forecast for one output: ceil(omega + ntaps) + FUDGE.
        // ninput_items includes history()-1=2 zero/history samples. This steady lookahead
        // is part of the reference finite-stream semantics, not a timed acquisition phase.
        int required = (int) Math.ceil(omega + 8f) + 16 - 2;
        if (timingInputCount - timingCursor < required) return;

        // Interpolator window is in[cursor-2 .. cursor+5]. The library circular buffer
        // supplies the initial two zeros and reverses time exactly as its interpolator needs.
        long through = timingCursor + 6;
        while (timingConsumed < through) {
            float i = timingInput.readFloat(), q = timingInput.readFloat();
            timingHistory.add(i, q);
            timingConsumed++;
        }
        float[] reused = p2;
        p2 = p1;
        p1 = p0;
        p0 = reused;
        interpolator.interpolateComplex(p0, timingHistory, mu);
        reused = c2;
        c2 = c1;
        c1 = c0;
        c0 = reused;
        c0[0] = p0[0] > 0 ? 1 : 0;
        c0[1] = p0[1] > 0 ? 1 : 0;
        float x = (c0[0] - c2[0]) * p1[0] - (c0[1] - c2[1]) * -p1[1];
        float y = (p0[0] - p2[0]) * c1[0] - (p0[1] - p2[1]) * -c1[1];
        float error = MathUtils.branchlessClip(y - x, 1f);
        omega += .000025f * error;
        omega = 10f + MathUtils.branchlessClip(omega - 10f, 10f * .005f);
        mu = mu + omega + .01f * error;
        int advance = (int) Math.floor(mu);
        mu -= (float) Math.floor(mu);
        if (advance < 1) throw new IllegalStateException("Elektro-L GGAK timing advance became nonpositive");
        timingCursor += advance;
        if (!Float.isFinite(p0[0]) || !Float.isFinite(p0[1]))
            throw new IllegalArgumentException("Elektro-L GGAK symbol amplitude overflow");
        symbols++;
        if (trace != null) trace.sample("symbols", p0[0], p0[1]);
        output.symbol(p0[0], p0[1]);
    }

    private static final class DecimatingFir {
        final FIRFilter filter;
        final CircularComplexArray history;
        final float[] value = new float[2];
        final int decimation;
        int phase;

        DecimatingFir(float[] taps, int decimation) {
            filter = new FIRFilter(taps);
            history = new CircularComplexArray(taps.length);
            this.decimation = decimation;
        }

        boolean add(float i, float q) {
            history.add(i, q);
            // GNU Radio's decimator convolves at the first sample of each consumed group,
            // but it may emit only when the entire decimation group exists. Keep that final
            // incomplete-group behaviour without buffering or synthesizing an EOF tail.
            if (phase == 0) filter.filterComplex(value, history);
            if (++phase != decimation) return false;
            phase = 0;
            return true;
        }
    }

    private static class ComplexInput implements FloatInput {
        final Context context = new Context();
        float i, q;
        boolean real = true;
        ComplexInput() { context.setChannels(2); context.setSampleRate(50000); }
        void set(float i, float q) { this.i = i; this.q = q; }
        @Override public float readFloat() {
            float value = real ? i : q;
            real = !real;
            return value;
        }
        @Override public Context getContext() { return context; }
        @Override public void close() {}
    }

    private static final class TimingInput {
        final float[] real = new float[64], imag = new float[64];
        int head, size;
        boolean inPhase = true;
        void add(float i, float q) {
            if (size == real.length) throw new IllegalStateException("Timing input overflow");
            int tail = (head + size) & 63;
            real[tail] = i;
            imag[tail] = q;
            size++;
        }
        float readFloat() {
            if (size == 0) throw new IllegalStateException("Timing input underflow");
            if (inPhase) { inPhase = false; return real[head]; }
            float value = imag[head];
            head = (head + 1) & 63;
            size--;
            inPhase = true;
            return value;
        }
    }
}
