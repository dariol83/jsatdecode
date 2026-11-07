package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;

/**
 * ServiceLoader factory for the isolated SatDecode ZMQ monitoring/event adapter.
 */
public final class ZmqMonitoringDistributorFactory implements MonitoringDistributorFactory {
    /**
     * Creates a stateless setup factory.
     */
    public ZmqMonitoringDistributorFactory() {
    }

    @Override
    public MonitoringDistributorDescriptor descriptor() {
        return new MonitoringDistributorDescriptor("zmq", "SatDecode ZMQ monitoring", "1", "SatDecode v1 canonical CBOR status/event PUB");
    }

    @Override
    public MonitoringDistributor create(Configuration configuration) {
        return new ZmqMonitoringDistributor(configuration);
    }
}
