/**
 * Server-side implementation of JTDP v1 TCP distribution.
 *
 * <p>This package contains the TCP server components that handle client connections
 * and distribute frames, monitoring snapshots, and events to connected clients.
 *
 * <p>The server implementation uses a selector-based I/O model with separate worker
 * threads for handling frame distribution, monitoring distribution, and client I/O.
 *
 * @see eu.dariolucia.jsatdecode.distribution.tcp.client
 * @see eu.dariolucia.jsatdecode.distribution.tcp.codec
 */
package eu.dariolucia.jsatdecode.distribution.tcp.server;
