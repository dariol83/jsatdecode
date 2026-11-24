/**
 * SatDecode v1 wire adapters. JeroMQ types are confined to this package's private
 * implementation; public configuration and distributor contracts use only Java SE.
 * Workers own sockets, CBOR encoding, UTC mapping, bounded metric caches and I/O.
 * See docs/zmq-compatibility.md for mapping and PUB delivery limitations.
 */
package eu.dariolucia.jsatdecode.distribution.zmq;
