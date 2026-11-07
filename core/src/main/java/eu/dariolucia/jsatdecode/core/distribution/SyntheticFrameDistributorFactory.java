package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;

/**
 * ServiceLoader synthetic frame output; discards consumed data deliberately, counting it as sent.
 */
public final class SyntheticFrameDistributorFactory implements FrameDistributorFactory {
    /**
     * Creates a stateless factory.
     */
    public SyntheticFrameDistributorFactory() {
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("synthetic", "Synthetic frames", "1", "Count-only synthetic output");
    }

    @Override
    public FrameDistributor create(Configuration c) {
        return new SyntheticFrameDistributor(OutputSettings.capacity(c), OutputSettings.policy(c), frame -> {
        });
    }
}
