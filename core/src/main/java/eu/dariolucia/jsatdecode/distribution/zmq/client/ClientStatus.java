package eu.dariolucia.jsatdecode.distribution.zmq.client;

/**
 * Immutable observation of one configured subscription. Transport connectivity
 * and recent valid messages are independent: an idle frame publisher can remain
 * connected. This record contains local observations, never delivery guarantees.
 * Rates include topic, CBOR and payload bytes, excluding ZMTP/TCP overhead, and
 * count only complete valid messages. Status and events share monitoring totals.
 *
 * @param channel subscription being observed
 * @param endpoint configured TCP connect address
 * @param running whether the subscription worker is active
 * @param connected latest TCP connection observation from the socket monitor
 * @param dataPresent whether a valid message arrived within the availability timeout
 * @param receivedBytesPerSecond valid multipart bytes per elapsed monotonic second
 * @param receivedMessagesPerSecond valid messages per elapsed monotonic second
 * @param receivedMessages lifetime valid message count
 * @param receivedBytes lifetime valid multipart byte count
 * @param malformedMessages lifetime rejected complete multipart message count
 * @param timestampNanos local observation time in UTC epoch nanoseconds
 * @param lastReceiveTimeNanos local UTC reception time of the last valid message, or zero
 */
public record ClientStatus(ZmqClient.Channel channel, String endpoint, boolean running,
                           boolean connected, boolean dataPresent,
                           double receivedBytesPerSecond, double receivedMessagesPerSecond,
                           long receivedMessages, long receivedBytes, long malformedMessages,
                           long timestampNanos, long lastReceiveTimeNanos) {
}
