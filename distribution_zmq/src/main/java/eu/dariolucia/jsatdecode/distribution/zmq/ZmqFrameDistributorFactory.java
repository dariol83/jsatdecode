package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;

/**
 * ServiceLoader factory for the isolated SatDecode ZMQ frame adapter.
 */
public final class ZmqFrameDistributorFactory implements FrameDistributorFactory {
    /**
     * Creates a stateless setup factory.
     */
    public ZmqFrameDistributorFactory() {
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("zmq", "SatDecode ZMQ frames", "1", "SatDecode v1 canonical CBOR multipart PUB");
    }

    @Override
    public FrameDistributor create(Configuration configuration) {
        return new ZmqFrameDistributor(configuration);
    }
}
