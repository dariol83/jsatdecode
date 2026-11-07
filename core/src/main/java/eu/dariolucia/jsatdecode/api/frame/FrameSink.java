package eu.dariolucia.jsatdecode.api.frame;

/**
 * Lightweight functional output boundary; implementations enqueue into bounded storage.
 */
@FunctionalInterface
public interface FrameSink {
    /**
     * Accepts a frame without waiting, protocol I/O or UI work on the DSP caller.
     * Overflow must be explicit: fail the runtime by default, or account for
     * drops under an operator-selected policy. Exceptions propagate to the runtime.
     *
     * @param frame non-null immutable result, retainable after return
     */
    void accept(AnnotatedFrame frame);
}