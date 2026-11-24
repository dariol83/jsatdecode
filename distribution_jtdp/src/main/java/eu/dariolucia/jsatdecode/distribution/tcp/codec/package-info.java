/**
 * Codec classes for JTDP v1 message encoding and decoding.
 *
 * <p>This package contains the message format definitions and serialization utilities
 * for the TCP Binary Distribution Protocol. All message types follow a common header
 * format with type-specific payloads.
 *
 * <h2>Message Types</h2>
 * <ul>
 *   <li>Type 0: Frame batch - contains one or more frame records</li>
 *   <li>Type 1: Monitoring - status snapshot with counters and metrics</li>
 *   <li>Type 2: Event - runtime event notification</li>
 * </ul>
 *
 * @see eu.dariolucia.jsatdecode.distribution.tcp.server
 * @see eu.dariolucia.jsatdecode.distribution.tcp.client
 */
package eu.dariolucia.jsatdecode.distribution.tcp.codec;
