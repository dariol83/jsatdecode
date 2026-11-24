package eu.dariolucia.jsatdecode.api.distribution;

import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.LifecycleComponent;

/**
 * Protocol-neutral bounded asynchronous frame output. Network/file I/O belongs to
 * owned workers, never to DSP callers. Shutdown must drain accepted frames or
 * explicitly report failure/loss, then join workers. No silent loss.
 */
public interface FrameDistributor extends LifecycleComponent {
    /**
     * Returns metadata.
     *
     * @return immutable descriptor
     */
    FrameDistributorDescriptor descriptor();

    /**
     * Submits without waiting while RUNNING; default overflow policy is FAIL_RUNTIME.
     * Explicit drop policies must increment counters. Errors propagate to the runtime.
     *
     * @param frame non-null immutable frame
     */
    void distribute(AnnotatedFrame frame);

    /**
     * Returns safely published counters without transport I/O.
     *
     * @return immutable status
     */
    DistributionSnapshot monitoringSnapshot();
}