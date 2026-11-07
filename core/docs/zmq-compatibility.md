# SatDecode v1 ZMQ publisher configuration

For the self-contained, authoritative client wire contract and the reusable
Java reference client, see [ZMQ distribution interface](zmq-distribution-interface.md).
This document describes publisher configuration, mapping and verification.

The CLI can additionally record frames with `--store <folder>` without opening
another ZMQ socket. The disk distributor reuses SatDecodeWire and canonical CBOR;
three length-prefixed multipart bodies retain metadata and exact payload bytes.
See [frame recording](frame-recording.md) for the container format and time semantics.

The registered frame and monitoring factory IDs are both `zmq`. They implement
the generic APIs; source, decoder, runtime, UI and API packages import no ZMQ
types. JeroMQ 0.6.0 and its jnacl 1.0.0 dependency are implementation details.
Production CBOR encoding is self-contained; Jackson is test-only. The
[stand-alone distribution](../../distribution/README.md) includes runtime JARs
and upstream license notices and discovers factories on the module path. ZMQ
transport requires no native library.

## Configuration and topology

~~~text
bin/jsatdecoder.bat --config examples/core/zmq-dummy.properties --set source.file=C:/recordings/input.cf32 --headless
~~~

Run the command from an extracted distribution. Each distributor owns a
separate PUB socket and binds its endpoint on its own
worker. Existing SUB clients connect with the same topic filters. Start returns
after binding or throws a diagnostic; construction opens no socket/context.
Subscriber disconnect/reconnect requires no application intervention or publisher
restart. A restarted publisher rebinds; existing subscribers reconnect normally.
Each newly constructed publisher starts sequences at zero. No retained history,
acknowledgment, replay or connection handshake is added to the wire protocol.

Only TCP bind endpoints are accepted, including wildcard interface/port and IPv6.
JeroMQ's IPC emulation is incompatible with native libzmq IPC, so unsupported
endpoint schemes fail explicitly. The actual bound address is exposed by
endpoint(), including an assigned wildcard port.

All keys below are relative to distribution.frames or distribution.monitoring:

| Key | Frames default | Monitoring default | Meaning |
| --- | --- | --- | --- |
| type | CLI default synthetic | CLI default synthetic | Select zmq explicitly |
| endpoint | tcp://*:5555 | tcp://*:5556 | PUB bind address |
| receiver_id | receiver-1 | receiver-1 | Wire receiver identity |
| decoder | required by factory | required by factory | Runtime defaults to decoder.type |
| queue_capacity | 4096 | 256 | Queued/reserved immutable input items |
| send_hwm | 4096 | 256 | Socket send high-water mark, per peer |
| linger_ms | 250 | 250 | Finite socket close linger, 0 through 60000 ms |
| overflow_policy | FAIL_RUNTIME | FAIL_RUNTIME | Or explicit DROP_NEWEST / DROP_OLDEST |
| interval_s | unused | 0.5 | Full-status cadence, 0.001 through 86400 seconds |
| metric_capacity | unused | 1024 | Maximum retained metric keys |
| max_message_bytes | 16777216 | 16777216 | Total multipart byte bound |

Unknown keys and invalid values fail at setup. The bound counts input items;
there may also be one executing item. Choose queue/HWM capacities to fit actual
frame and metadata sizes; they are not byte-budget or subscriber-count limits.
Canonical CBOR working storage is bounded by the configured message limit and
32 container nesting levels. Oversize/unsupported metadata fails on the worker.
The immutable API owns the input data before it enters this adapter.

After global CLI/file precedence has been applied, DistributionConfiguration
normalizes these flattened SatDecode property aliases. An explicit canonical
key always wins over its alias, independent of which configuration layer supplied
the alias. Prefer canonical keys in CLI overrides if a file uses canonical keys.

| Flattened property alias | Canonical target |
| --- | --- |
| decoder.name | decoder.type |
| application.receiver_id | both distribution sections' receiver_id |
| application.monitor_interval_s | distribution.monitoring.interval_s |
| network.frames.endpoint / queue_capacity / send_hwm / linger_ms | distribution.frames with the same suffix |
| network.monitor.endpoint / queue_capacity / send_hwm / linger_ms | distribution.monitoring with the same suffix |

The Java CLI reads properties. Network aliases configure
settings but do not implicitly select ZMQ. Per-output identity overrides are
supported. Normalization also supplies identity to other distributor factories.

## Multipart and type mapping

| Channel | Parts | Envelope |
| --- | --- | --- |
| Frames | satdecode.v1.frame, CBOR, raw payload | schema_version=1, message_type=tm.frame, receiver_id, decoder, sequence, rx_time_ns, rx_time_source, publish_time_ns, frame_type, frame_length, quality, metadata |
| Status | satdecode.v1.monitor.status, CBOR | schema_version=1, message_type=monitor.status, receiver_id, decoder, sequence, timestamp_ns, metrics, metric_timestamps_ns |
| Events | satdecode.v1.monitor.event, CBOR | schema_version=1, message_type=monitor.event, receiver_id, decoder, sequence, timestamp_ns, event, severity, details |

CBOR matches cbor2 canonical encoding: definite lengths, shortest integers and
exact floating representations (float16/32/64), canonical NaN, UTF-8 strings,
and length-first/unsigned-byte ordering of encoded map keys. Scalar Java types
retain their CBOR meaning; enums use their name and characters become strings.
Maps/lists are recursive. Malformed Unicode fails instead of being replaced.
BinaryValue carries immutable byte strings and NullValue.INSTANCE carries explicit
metadata null. Raw mutable arrays and Java null are rejected.
NaN represents an unavailable numeric metric on the wire. No finite SNR or lock
state is invented.

Frame annotation mapping:

| Annotation | Mapping/default |
| --- | --- |
| rx_time_ns | Explicit nonnegative integral UTC epoch nanoseconds; highest priority |
| reception.timestamp | UTC epoch nanoseconds or ISO-8601 instant if rx_time_ns is absent |
| rx_time_source | hardware, sample_clock, host, recording or unknown |
| frame_type, then frame.type | Nonblank frame type, default RAW |
| quality, then frame.valid | GOOD/BAD/UNKNOWN; boolean valid maps to GOOD/BAD; default UNKNOWN |
| metadata | Explicit metadata map merged over remaining annotations |
| Other annotations | Retained inside metadata |

Empty payloads are rejected. Transport-owned
sequence, length, receiver/decoder identity and publication time cannot be
overridden by annotations. Their similarly named unrecognized annotation keys
remain ordinary metadata. Frames and events reserve independent submission
sequences before queue admission, preserving ordinary single-producer drop gaps.
The worker guarantees strictly increasing wire sequences even if concurrent
callers publish reserved tickets out of order. Concurrent calls have FIFO queue
order, with no implied external total order; each individual producer keeps order.
Status has its own sequence allocated on publication. Sequence exhaustion fails.

RuntimeEvent.type becomes event, severity becomes its enum name, and attributes
become details. A reserved reception.timestamp attribute may override UTC event
time and is removed from details. Generic source/message fields have no v1
wire field: include them explicitly in attributes when a consumer needs them.
Event names/details are preserved; no lock events are synthesized from status.

## Time and status semantics

Generic timestampNanos values are UTC epoch nanoseconds and are preserved directly
as reception/event/metric observation time. Adapters validate representability
and never apply another clock calibration. UtcTime supplies the framework's
allocation-free host-anchored UTC timeline; see [time.md](time.md).
Host-derived frame time defaults to rx_time_source=host. Explicit UTC frame time
defaults to unknown unless its source is supplied. For recordings, source time
is a processing-start UTC sample timeline, not the recording's original UTC;
provide explicit recording timestamps when known. Out-of-range times fail.
Envelope publication/status timestamps read host UTC on the output worker.

The monitoring worker merges accepted snapshots into a bounded latest-value
metric map. Separate source/decoder updates do not replace each other's keys.
It publishes a complete status immediately after bind, every interval_s and once
after draining on stop. There are no
catch-up bursts if a worker is delayed. Events are sent between status updates.
metric_timestamps_ns records each key's most recent supplied snapshot observation
time. The generic snapshot API has no per-key last-change timestamp; it cannot
reconstruct per-key change timestamps without that information.
Direct applications may submit snapshots at their actual metric observation times.

Runtime awaitCompletion remains the management polling loop (up to 4 Hz).
It submits merged source/decoder/distribution observations and publishes a
final observation after acquisition/decoder/frame drain. The compatible
output.frames_sent and output.frames_dropped totals are included. The ZMQ worker
owns wire cadence independently; neither polling nor serialization runs on DSP.

## Loss, errors and shutdown

Normal submission only reserves a bounded slot and enqueues immutable references;
it performs no CBOR encoding, payload copy, socket I/O or capacity waiting.
FAIL_RUNTIME surfaces queue overflow immediately. Explicit drop policies count
loss; DROP_OLDEST replaces the oldest queued item, never the executing item.
Concurrent producers may claim a vacated slot first, causing both old and new
items to be counted as dropped. Healthy workers drain already accepted input
after overflow. Serialization/socket failures fail the worker and count the
failing input plus abandoned queued input as dropped. No retry can duplicate a
partially sent multipart message: the failed socket is closed.

**PUB is inherently best effort.** Messages with no subscribers and messages
discarded internally at a peer's HWM
may still report send success. messagesSent counts local PUB acceptance, not
delivery; messagesDropped/errors count losses visible to this adapter, not remote
loss. FAIL_RUNTIME prevents silent application-mailbox overflow; it cannot turn
PUB into acknowledged delivery. connected means successfully bound, not a live
subscriber. A lossless/acknowledged protocol requires a different distributor.
See the [ZeroMQ PUB contract](https://libzmq.readthedocs.io/en/latest/zmq_socket.html#zmq-pub).

Monitoring messagesReceived counts submitted snapshots/events; messagesSent counts
actual status/event envelopes, including periodic and initial/final statuses.
These counts need not reconcile, because snapshots update state rather than
represent one wire message each. Overflow drops snapshot updates or events under
the selected policy; the last accepted metric state is retained until replaced.

Owners serialize lifecycle and quiesce producers before stop/close. Stop drains
local input, emits final status, closes socket/context on their owning worker,
and joins it. Finite linger bounds the socket's close wait; it is no delivery
guarantee. Management interruption is preserved. JeroMQ workers are stopped
cooperatively, never via Thread.interrupt. Startup errors and first asynchronous
diagnostics remain accessible through failure(), including after CLOSED.

## Verification and packaging

Run `mvn -pl core verify` from the repository root. `ZmqClientTest` covers
wire decoding and real TCP publication/subscription using the Java client in
`distribution.zmq.client`; runtime tests cover output lifecycle and bounded
mailboxes. Maven copies resolved runtime/test JARs into
`core/target/test-dependencies`. See [the implementation guide](implementation-guide.md)
for build and test commands.

The optional `core/src/test/python/zmq_interop.py` runner also requires `pyzmq`,
`cbor2` and the external `satdecode.frame_client` / `satdecode.monitor_client`
modules. Those Python client modules are not included in this repository.
