package eu.dariolucia.jsatdecode.decoder.base.dsp;

import ru.r2cloud.jradio.Context;
import ru.r2cloud.jradio.FloatInput;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Worker-owned push adapter for two independent jradio 1.121 long-form DC filters. One supplied
 * complex sample produces one complex output; zero-initialized history persists across blocks.
 * Enabled delay is 2*(length-1) input samples. No EOF tail is requested. Bypass copies raw I/Q
 * bits. Instances are not thread-safe; sample processing is synchronous, nonblocking and
 * allocation-free. The caller owns sample validation and treats the primitive output fields as
 * read-only.
 */
public final class ComplexDcBlocker {
    private final Input real, imaginary;
    private final ru.r2cloud.jradio.blocks.DcBlocker realFilter, imaginaryFilter;
    private final int length;
    private final String failureMessage;

    /** Latest in-phase output, initially zero; replaced by every sample call. */
    public float i;

    /** Latest quadrature output, initially zero; replaced by every sample call. */
    public float q;

    /**
     * Allocates fixed filter state with the common in-memory failure diagnostic.
     *
     * @param enabled lifetime enablement; false allocates no jradio filter state
     * @param length long-form averaging length, validated by the mission's settings
     */
    public ComplexDcBlocker(boolean enabled, int length) {
        this(enabled, length, "In-memory DC blocker failed");
    }

    /**
     * Allocates fixed filter state while preserving a mission's existing failure diagnostic.
     *
     * @param enabled lifetime enablement
     * @param length long-form averaging length, validated by the mission's settings
     * @param failureMessage message used to wrap a library IOException
     */
    public ComplexDcBlocker(boolean enabled, int length, String failureMessage) {
        this.length = length;
        this.failureMessage = failureMessage;
        real = enabled ? new Input() : null;
        imaginary = enabled ? new Input() : null;
        realFilter = enabled ? new ru.r2cloud.jradio.blocks.DcBlocker(real, length, true) : null;
        imaginaryFilter =
                enabled ? new ru.r2cloud.jradio.blocks.DcBlocker(imaginary, length, true) : null;
    }

    /**
     * Replaces the latest output without buffering samples for later emission. Enabled processing
     * reads I then Q in the original adapter order; nonfinite values propagate into filter history.
     *
     * @param inputI input in-phase value
     * @param inputQ input quadrature value
     * @throws UncheckedIOException if an in-memory library read fails
     */
    public void sample(float inputI, float inputQ) {
        if (realFilter == null) {
            i = inputI;
            q = inputQ;
            return;
        }
        real.value = inputI;
        imaginary.value = inputQ;
        try {
            i = realFilter.readFloat();
            q = imaginaryFilter.readFloat();
        } catch (IOException e) {
            throw new UncheckedIOException(failureMessage, e);
        }
    }

    /**
     * Returns the averaging length configured at construction, also in bypass mode.
     *
     * @return input samples per moving average
     */
    public int length() {
        return length;
    }

    private static final class Input implements FloatInput {
        private final Context context = new Context();
        private float value;

        Input() {
            context.setChannels(1);
        }

        @Override
        public float readFloat() {
            return value;
        }

        @Override
        public Context getContext() {
            return context;
        }

        @Override
        public void close() {}
    }
}
