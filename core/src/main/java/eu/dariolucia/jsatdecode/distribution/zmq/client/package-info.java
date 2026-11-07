/**
 * Transport-neutral callback API for the SatDecode v1 ZMQ frame and monitoring
 * distribution interface. {@link eu.dariolucia.jsatdecode.distribution.zmq.client.ZmqClient}
 * owns the subscription transport; callers receive Java values and independently
 * observed connection, inactivity and receive-rate information. No native library
 * or CBOR dependency is needed. See core/docs/zmq-distribution-interface.md.
 */
package eu.dariolucia.jsatdecode.distribution.zmq.client;
