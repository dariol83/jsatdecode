/**
 * Reference client implementation for JTDP v1 TCP distribution.
 *
 * <p>This package contains a reference TCP client that can connect to a JSatDecode
 * TCP distribution server and consume frames, monitoring snapshots, and events.
 *
 * <p>The client implementation provides:
 * <ul>
 *   <li>Message parsing and validation</li>
 *   <li>Reconnection with bounded exponential backoff</li>
 *   <li>Data gap detection and session reset marking</li>
 * </ul>
 *
 * @see eu.dariolucia.jsatdecode.distribution.tcp.server
 * @see eu.dariolucia.jsatdecode.distribution.tcp.codec
 */
package eu.dariolucia.jsatdecode.distribution.tcp.client;
