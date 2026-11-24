package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;

import java.util.concurrent.atomic.AtomicLong;

/**
 * SatDecode v1 PUB-bind frame output. Only the owned worker serializes or uses
 * sockets. Submission retains the immutable frame in a bounded mailbox and never
 * waits for capacity/network. Default overflow fails the runtime. PUB acceptance
 * cannot acknowledge delivery to subscribers; see docs/zmq-compatibility.md.
 * The management owner serializes lifecycle and quiesces producers before stop.
 */
public final class ZmqFrameDistributor extends ZmqWorker<ZmqFrameDistributor.Item> implements FrameDistributor {
    record Item(AnnotatedFrame frame, long sequence) {
    }

    private final AtomicLong sequence = new AtomicLong();
    private long nextWireSequence;

    /**
     * Validates unprefixed settings without creating sockets or threads.
     *
     * @param configuration component settings; decoder identity is required
     */
    public ZmqFrameDistributor(Configuration configuration) {
        super(ZmqSettings.read(configuration, false));
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("zmq", "SatDecode ZMQ frames", "1", "SatDecode v1 canonical CBOR multipart PUB");
    }

    @Override
    public void distribute(AnnotatedFrame frame) {
        java.util.Objects.requireNonNull(frame);
        offer(new Item(frame, sequence(sequence)));
    }

    @Override
    void process(Item item) {
        long sequence = orderedSequence(nextWireSequence, item.sequence());
        send(wire.frame(item.frame(), sequence));
        nextWireSequence = sequence + 1;
    }
}
