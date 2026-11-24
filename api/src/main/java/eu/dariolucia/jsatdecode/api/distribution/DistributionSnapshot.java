package eu.dariolucia.jsatdecode.api.distribution;

/**
 * Immutable transport-neutral counters, safely shareable between threads.
 * Counts are nonnegative; messages include frames, snapshots or events as applicable.
 *
 * @param running          worker active
 * @param connected        transport connected
 * @param messagesReceived submitted messages
 * @param messagesSent     transmitted messages
 * @param messagesDropped  explicitly discarded messages
 * @param errors           observed errors
 * @param queueDepth       queued messages
 * @param queueCapacity    configured bounded capacity
 * @param timestampNanos   UTC epoch-nanosecond observation time
 */
public record DistributionSnapshot(boolean running, boolean connected, long messagesReceived,
                                   long messagesSent, long messagesDropped, long errors, int queueDepth,
                                   int queueCapacity, long timestampNanos) {
    /**
     * Validates counters and capacity.
     *
     * @param running          worker active
     * @param connected        transport connected
     * @param messagesReceived received count
     * @param messagesSent     sent count
     * @param messagesDropped  dropped count
     * @param errors           error count
     * @param queueDepth       queued count
     * @param queueCapacity    maximum count
     * @param timestampNanos   UTC epoch-nanosecond timestamp
     */
    public DistributionSnapshot {
        if (messagesReceived < 0 || messagesSent < 0 || messagesDropped < 0 || errors < 0 ||
                queueDepth < 0 || queueCapacity < queueDepth)
            throw new IllegalArgumentException("Invalid distribution counters/capacity");
    }
}