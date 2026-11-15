/**
 * JSatDecode TCP Binary Distribution Protocol (JTDP) v1 implementation.
 *
 * <p>This package provides a TCP-based distribution mechanism for frames, monitoring snapshots,
 * and runtime events following the JTDP v1 specification. The implementation includes:
 *
 * <ul>
 *   <li>A TCP server that accepts multiple client connections</li>
 *   <li>Frame batch distribution with configurable batching parameters</li>
 *   <li>Monitoring snapshot distribution at configurable intervals</li>
 *   <li>Runtime event distribution</li>
 *   <li>A reference TCP client for consuming distributed data</li>
 * </ul>
 *
 * <p>The protocol uses a simple binary wire format with an 8-byte header for all messages:
 * <pre>
 *   Magic (1 byte)  : 0xA5
 *   Type (1 byte)   : 0 = frame batch, 1 = monitoring, 2 = event
 *   Source ID (2B)  : Antenna/decoder identifier
 *   Length (4B)     : Total message length including header
 * </pre>
 *
 * <p>Security Note: V1 is unauthenticated and unencrypted. The server SHOULD be deployed
 * only on trusted networks or behind a network access control.
 *
 * @see eu.dariolucia.jsatdecode.distribution.tcp.codec
 * @see eu.dariolucia.jsatdecode.distribution.tcp.server
 * @see eu.dariolucia.jsatdecode.distribution.tcp.client
 */
package eu.dariolucia.jsatdecode.distribution.tcp;
