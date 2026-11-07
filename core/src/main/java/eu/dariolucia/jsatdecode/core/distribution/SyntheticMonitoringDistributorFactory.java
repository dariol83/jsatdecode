package eu.dariolucia.jsatdecode.core.distribution;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;

/**
 * ServiceLoader synthetic monitoring output; consumes and counts without retaining messages.
 */
public final class SyntheticMonitoringDistributorFactory implements MonitoringDistributorFactory {
    /**
     * Creates a stateless factory.
     */
    public SyntheticMonitoringDistributorFactory() {
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("synthetic", "Synthetic monitoring", "1", "Count-only synthetic observations");
    }

    @Override
    public MonitoringDistributor create(Configuration c) {
        return new SyntheticMonitoringDistributor(OutputSettings.capacity(c), OutputSettings.policy(c), s -> {
        }, e -> {
        });
    }
}
