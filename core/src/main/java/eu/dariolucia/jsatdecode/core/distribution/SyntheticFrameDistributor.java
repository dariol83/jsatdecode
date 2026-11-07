package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.distribution.DistributionSnapshot;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.OverflowPolicy;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;

import java.util.function.Consumer;

/**
 * Bounded synthetic output for tests and embedding. Retains no history.
 * Submissions may be concurrent; owner quiesces producers before stop/close.
 * A callback runs only on the output worker and must eventually return.
 */
public final class SyntheticFrameDistributor implements FrameDistributor {
    private final AsyncOutput<AnnotatedFrame> output;

    /**
     * Creates a one-shot output without starting a worker.
     *
     * @param capacity positive queue bound
     * @param policy   explicit overflow policy
     * @param sink     worker callback; exceptions fail output and count remaining loss
     */
    public SyntheticFrameDistributor(int capacity, OverflowPolicy policy, Consumer<AnnotatedFrame> sink) {
        output = new AsyncOutput<>(capacity, policy, sink);
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("synthetic", "Synthetic frames", "1", "Bounded synthetic frame consumer");
    }

    @Override
    public void distribute(AnnotatedFrame frame) {
        output.offer(frame);
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        return output.snapshot();
    }

    @Override
    public void start() {
        output.start();
    }

    @Override
    public void stop() {
        output.stop();
    }

    @Override
    public void close() {
        output.close();
    }

    @Override
    public ComponentState state() {
        return output.state();
    }
}
