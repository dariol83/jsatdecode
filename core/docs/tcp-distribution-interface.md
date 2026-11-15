# JSatDecode TCP Binary Distribution Protocol (JTDP) — Version 1

**Status:** Normative specification  
**Scope:** JSatDecode `distribution.tcp` server and interoperable clients  
**Transport:** TCP/IP, one connection carrying frames, monitoring, and events

The key words **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** denote normative requirements.

## 1. Encoding and framing

- All multibyte integers and IEEE-754 binary32 values use **network byte order (big-endian)**. Unsigned fields have no sign extension; implementations MUST handle `uint32` values above `2^31−1` correctly.
- TCP is a byte stream. Receivers MUST reconstruct messages from the fixed header and its length; TCP read boundaries do not delimit messages. Payload bytes (including `0xA5`) are never escape-coded.
- Every message starts with the following **8-byte header**:

| Offset | Bytes | Type | Field and semantics |
|---:|---:|---|---|
| 0 | 1 | `uint8` | Magic: `0xA5` |
| 1 | 1 | `uint8` | Type: `0` frame batch, `1` monitoring, `2` event |
| 2 | 2 | `uint16` | Antenna/decoder identifier; configured locally, stable within a session |
| 4 | 4 | `uint32` | **Total message length**, including the entire 8-byte header |

- Valid length range is **8 to `2^32−1` bytes**, subject to the receiver's configured maximum (default **1,048,576 bytes**, inclusive of header). A length below 8, exceeding the configured maximum, or inconsistent with a known message's structure is a protocol error: the receiver MUST close the connection. A message is complete only after exactly `length` bytes have been read.
- Types `0x03–0xEF` are reserved; `0xF0–0xFF` are reserved for future protocol control/version negotiation. A v1 receiver MUST skip unrecognized types by their validated total length. Such types MUST NOT be sent by a v1 producer.
- There is no v1 handshake, authentication, compression, checksum, or retransmission facility. The fields defined below constitute the complete v1 wire format; extensions MUST use new message types or a later negotiated version rather than silently extending known fixed layouts.

## 2. Type 0 — frame batch

The payload is a concatenation of **one or more** frame records with no padding or batch count. Each record has the following layout, relative to the start of that record:

| Offset | Bytes | Type | Field |
|---:|---:|---|---|
| 0 | 1 | `uint8` | Quality: `0` good, `1` bad, `2` unknown; other values reserved |
| 1 | 8 | `int64` | Antenna reception timestamp: nanoseconds since `1970-01-01T00:00:00Z` (Unix time, UTC) |
| 9 | 4 | `uint32` | Frame length **F**, in bytes |
| 13 | F | bytes | Raw frame bytes, unmodified |

- `F` MUST be greater than zero. Each record occupies `13 + F` bytes. The final record MUST end exactly at the enclosing message boundary. Message size is `8 + Σ(13 + Fᵢ)`.
- Timestamp is the frame's **reception** time, not its serialization/transmission time. A source without a valid reception timestamp MUST define that condition before distribution; no sentinel value is assigned in v1.
- The transmitter MUST preserve frame order as delivered to the distributor. It MUST NOT split an individual frame record between protocol messages, although ordinary TCP segmentation is unrestricted.
- The configured maximum message size MUST be enforced before constructing or queuing a batch. A single frame that cannot fit MUST be rejected and reported locally; it MUST NOT be truncated.

## 3. Shared 1-byte status encoding

Monitoring and event messages contain one status byte. Within each two-bit state: `0` = searching, `1` = locked, `2` = unlocked, `3` = unknown.

| Bits | Meaning |
|---|---|
| 7–6 | Carrier state |
| 5–4 | Symbol state |
| 3–2 | Frame state |
| 1–0 | SNR availability: `0` unavailable, `1` available; `2–3` reserved |

The producer MUST emit only `0` or `1` for SNR availability. A receiver MUST treat the reserved values as unavailable. The status byte is packed as `(carrier << 6) | (symbol << 4) | (frame << 2) | snrAvailable`.

## 4. Type 1 — monitoring

**Exact message length: 40 bytes**, including the header. Payload offsets are relative to byte 8 (immediately after the header):

| Offset | Bytes | Type | Field |
|---:|---:|---|---|
| 0 | 1 | `uint8` | Status byte (§3) |
| 1 | 7 | `uint56` | Center frequency in Hz; unsigned, big-endian |
| 8 | 4 | `int32` | Carrier offset in Hz; `0x80000000` denotes unavailable |
| 12 | 4 | IEEE-754 `binary32` | SNR in dB; NaN denotes unavailable |
| 16 | 4 | `uint32` | Total decoded frames |
| 20 | 4 | `uint32` | Total error frames |
| 24 | 4 | `uint32` | Total good decoded frames |
| 28 | 4 | `uint32` | Frames accepted for TCP distribution |

- Carrier offset is **signed**; positive means the observed carrier is above the reference center frequency, negative below. `−2,147,483,648` is reserved as the unavailable sentinel and is not a valid offset.
- SNR is valid only if the availability bits equal `1` **and** the float is finite. Otherwise a receiver MUST consider SNR unavailable. The producer MUST encode a quiet NaN when unavailable and set availability to `0`; it MUST NOT send infinities.
- All counters are sampled from one logically consistent snapshot and transmitted modulo `2^32`; wraparound is permitted. `total decoded = good + error` modulo `2^32` for counters tracking the same categories. `Frames accepted for TCP distribution` is a **producer-side** count, not proof of network delivery or client processing.
- A connected client SHOULD receive an immediate monitoring snapshot upon connection **when monitoring distribution is enabled**, then periodic snapshots at the configured interval. No monitoring snapshots are emitted when monitoring distribution is disabled. Snapshot submissions update the latest immutable observation; they do not independently trigger extra periodic publications. Before an observation is supplied, the initial snapshot uses unknown lock states and unavailable measurements.

## 5. Type 2 — event

**Exact message length: 25 bytes**, including the header:

| Payload offset | Bytes | Type | Field |
|---:|---:|---|---|
| 0 | 1 | `uint8` | Event code (§5.1) |
| 1 | 8 | `int64` | Event occurrence UTC Unix timestamp, nanoseconds |
| 9 | 1 | `uint8` | Current status byte (§3), reflecting state at event time |
| 10 | 7 | `uint56` | Center frequency in Hz |

### 5.1 Event codes

| Code | Meaning |
|---:|---|
| 0 | Decoder started |
| 1 | Decoder stopped |
| 2 | Carrier state changed |
| 3 | Symbol state changed |
| 4 | Frame state changed |
| 5 | Decoder error |
| 6 | Distribution overrun |
| 7 | Data discarded |
| 8–255 | Reserved |

State-change events contain the **new** state in the corresponding status bits. Events with other codes carry a current status snapshot. In v1, decoder error, distribution overrun, and data discarded are intentionally **code-only** notifications: there is no embedded text, severity, or supplemental payload. Unsupported framework event categories MUST NOT be mapped to misleading event codes; they MAY remain local to JSatDecode. Producers MUST NOT emit reserved event codes; receivers SHOULD ignore unknown codes after validating the message length.

Framework decoder lifecycle and lock transitions detected in monitoring observations generate events using that observation's timestamp and new state. Explicit framework events retain their supplied occurrence timestamp and event-time attributes; unsupported categories remain local.

Events SHOULD be emitted promptly after occurrence, without waiting for frame batching. The stopped event is best-effort during normal shutdown; abrupt process or socket failure cannot guarantee its delivery.

## 6. Connection, ordering, batching and resource limits

1. The JSatDecode TCP distributor acts as a **TCP server**. One client connection carries the enabled frame, monitoring and event message streams. Multiple clients receive independently queued copies of the same serialized messages. No application-level acknowledgement or replay exists.
2. The frame accumulator is flushed when **either** `max_frame_buffer` frames are collected (default **5**) **or** `max_frame_latency_s` seconds have elapsed since the **first frame of the current nonempty batch** (default **1 s**). A batch MUST also flush before adding a frame that would exceed `max_message_bytes`. No empty frame-batch messages are sent. Normal shutdown attempts a final flush, drains the producer queues, and gives client queues one shared bounded drain period (one second in the Java implementation) before closing stalled connections.
3. Timer deadlines MUST use a monotonic clock (e.g., `System.nanoTime()`), not UTC timestamps. The latency bound applies to *submission for transmission*, not guaranteed network delivery; it is measured in real processing time even for accelerated playback of recordings.
4. The runtime MUST keep network I/O and backpressure off decoder processing threads. Per-client outbound queues MUST be bounded by pending **bytes** (default **1,048,576**) and MUST maintain complete-message ordering. A client exceeding that limit MUST be disconnected, not silently skipped over individual frames. Other clients and the decoder MUST remain unaffected by the slow client.
5. Application messages queued on a given connection MUST retain their enqueue order across all message types. No total event-order guarantee is implied between concurrently produced frame and status updates beyond that queue order. A client connecting later has no right to historical messages.
6. A full TCP connection loss may cause undelivered messages. Successful `write` calls only establish submission to the local TCP stack, **not** remote application consumption. Clients MAY reconnect; the protocol provides no continuity sequence number or recovery mechanism.
7. On invalid magic at an expected message boundary, oversized/malformed known messages, or truncated messages at EOF, the reference client MUST report a protocol/transport error and discard the incomplete message. It MUST NOT attempt mid-payload resynchronization by scanning for `0xA5`.

## 7. Configuration (Java properties)

The `tcp` implementation lives under `distribution.tcp`. The framework's two existing distributor selections remain explicit; both enabled adapters MUST share **one** server/socket listener and one coordinated lifecycle, rather than each opening an independent server. Factory-created adapters acquire references to that listener: the first started adapter starts it, and the last stopped/closed adapter drains and closes it. Direct constructors accepting a server leave its lifecycle with the caller.

The shared `distribution.tcp.*` properties below are aliases applied to selected TCP streams before the runtime passes unprefixed sections to their factories. Canonical keys use `distribution.frames.*` and `distribution.monitoring.*`, as listed in [the configuration reference](configuration-reference.md#tcp-frame-output); explicit canonical keys take precedence over shared aliases. Shared listener settings MUST agree when both streams select TCP; mismatches are rejected before binding. Unknown TCP settings are rejected.

| Property | Default | Meaning |
|---|---|---|
| `distribution.frames.type` | Existing framework default | Set `tcp` to enable frame stream |
| `distribution.monitoring.type` | Existing framework default | Set `tcp` to enable monitoring and events |
| `distribution.tcp.port` | `6001` | TCP listen port |
| `distribution.tcp.keepalive` | `false` | `SO_KEEPALIVE` |
| `distribution.tcp.max_clients` | `2` | Concurrent client limit, `1..65535`; surplus connections rejected/closed |
| `distribution.tcp.send_buffer_bytes` | OS default | Optional requested `SO_SNDBUF` |
| `distribution.tcp.receive_buffer_bytes` | OS default | Optional requested `SO_RCVBUF` |
| `distribution.tcp.tcp_nodelay` | `true` | Set `TCP_NODELAY` |
| `distribution.tcp.monitoring_interval_s` | `3` | Periodic monitoring cadence, `0.001..86400` seconds |
| `distribution.tcp.max_frame_latency_s` | `1` | Nonempty batch latency trigger, `0.001..86400` seconds |
| `distribution.tcp.max_frame_buffer` | `5` | Frame-count flush trigger |
| `distribution.tcp.max_message_bytes` | `1048576` | Accepted/sent wire-message size limit, `40..2147483647` bytes in the Java implementation |
| `distribution.tcp.client_queue_bytes` | `1048576` | Maximum pending application TX bytes, per client |
| `distribution.tcp.slow_client_policy` | `DISCONNECT` | V1 network byte-queue policy; other values rejected |
| `distribution.tcp.source_id` | `1` | Source/antenna identifier, `0..65535` |

Each adapter also accepts canonical `queue_capacity` (default `256`) and `overflow_policy` (default `FAIL_RUNTIME`) properties for its **producer item queue**. Frame items and monitoring/event items have independent queues. `DROP_NEWEST` and `DROP_OLDEST` apply only to these producer queues; they never change the disconnect-only network byte-queue policy. Producer loss is counted locally, and monitoring-enabled sessions report coalesced overrun/discard notifications without retaining unbounded history. Frames accepted into the producer queue count toward the accepted-frame counter, including frames subsequently discarded by a producer policy.

The Java settings use millisecond timer resolution; fractional seconds are truncated to milliseconds (minimum 1 ms). Wire lengths are parsed as unsigned values before comparison with the implementation's configured signed-int allocation cap, so unsupported larger messages are rejected as oversized.

Optional socket buffer sizes MUST be positive when supplied, but the OS MAY choose different actual sizes. Implementations MUST validate configuration at startup (`1 ≤ max_clients`, `1 ≤ max_frame_buffer`, positive intervals and limits, and `max_message_bytes ≥ 40`). If a selected stream is disabled, it MUST NOT be emitted solely because the other stream selects `tcp`.

**Security:** V1 is unauthenticated and unencrypted. The server SHOULD be deployed only on trusted networks or behind a network access control; it MUST NOT be exposed to untrusted networks without an external protective transport (e.g., a secure tunnel). Operators SHOULD restrict listener exposure using host firewalls and network segmentation.

## 8. Implementation and conformance

- Java implementations SHOULD use standard JDK networking, preferably one nonblocking `Selector`-based I/O loop, per-client partial-write state, and shared immutable serialized batches. Hot paths SHOULD avoid unnecessary per-frame heap allocation. Neither third-party serializers nor compression are required.
- The reference client MUST decode all three message types; support fragmented/coalesced TCP reads; validate magic, lengths, enum ranges and known fixed-size payloads; and handle signed offset, NaN SNR and counter wraparound. It SHOULD offer reconnect with bounded exponential backoff, clearly marking data gaps and session resets.
- The implementation MUST test at least: each fixed wire layout; multiple frames in one batch; one-byte-at-a-time reads; multiple messages per read; negative offset; unavailable SNR; counter wrap; count/latency/size flush; two independent clients; stalled/overflowing client; connect/disconnect; malformed lengths; clean shutdown; sustained accelerated decoding with bounded memory.

## 9. Version-1 invariants

- Header = **8 bytes**; frame-record overhead = **13 bytes per frame**; monitoring message = **40 bytes**; event message = **25 bytes**.
- All known message types use fixed layouts or length-delimited concatenations; no implicit struct padding is allowed.
- Identifiers are `uint16`; frequencies are `uint56` Hz; offsets are signed `int32` Hz; time values are signed `int64` Unix nanoseconds.
- Transport is **best-effort live distribution over reliable ordered TCP**, not durable, acknowledged or replayable delivery.
