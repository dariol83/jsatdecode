# ZMQ frame and monitoring distribution interface

This is the authoritative client implementation contract for the ZMQ interface
published by the JSatDecode `core` module. It describes the current SatDecode v1
wire format in full; an implementor does not need the original SatDecode source
or a Java API to receive it. The normative implementation is
`distribution.zmq.SatDecodeWire`, `CanonicalCbor`, `ZmqFrameDistributor` and
`ZmqMonitoringDistributor`. The
[publisher configuration document](zmq-compatibility.md) describes configuration,
property aliases, annotation mapping and verification.

In this document, **must** identifies a requirement of a conforming message or
client. **Should** identifies recommended client behavior. Examples use decoded
CBOR notation, with `NaN` for an IEEE floating unavailable value and `h'...'` for
byte strings; this notation is not JSON sent on the network.

## 1. Transport and subscriptions

There are two independent ZeroMQ PUB services. The frame publisher binds a TCP
endpoint (default `tcp://*:5555`); the monitoring publisher binds another (default
`tcp://*:5556`). Either publisher may be configured independently. The client
creates SUB sockets and **connects**, using a reachable host address, for example
`tcp://127.0.0.1:5555` and `tcp://127.0.0.1:5556`. A publisher wildcard bind address
is not a client destination. IPv4 and IPv6 TCP are supported; IPC and other
endpoint schemes are not part of this interface.

Each message is one ZeroMQ multipart message. Parts are byte arrays with no
terminating NUL, newline, length prefix or extra separator:

| Service/message | Part 0: exact ASCII bytes | Part 1 | Part 2 | Total parts |
| --- | --- | --- | --- | --- |
| Frames | `satdecode.v1.frame` | CBOR frame envelope | Raw frame payload | 3 |
| Monitoring status | `satdecode.v1.monitor.status` | CBOR status envelope | Absent | 2 |
| Monitoring event | `satdecode.v1.monitor.event` | CBOR event envelope | Absent | 2 |

The part-0 byte lengths are respectively 18, 27 and 26. Topic matching in a SUB
socket is a byte-prefix filter. Subscribe to `satdecode.v1.frame` on the frame
socket and `satdecode.v1.monitor.` on the monitoring socket to receive both
monitoring message kinds. Separate exact status/event subscriptions also work.
A client must check the complete topic after receipt: a matching prefix alone
does not establish that a message has one of the three schemas above.

Always consume the complete multipart message, including on malformed or unknown
messages. Do not interpret its parts as independent ZeroMQ messages. There is no
request socket, subscription handshake in the application protocol, authentication
envelope, compression, length framing inside a part, or initial state request.
Deployment-specific network security and endpoint discovery are outside this
wire format.

## 2. CBOR representation and validation

Part 1 is exactly one CBOR map with text keys. Trailing bytes or additional CBOR
objects are not part of the envelope. All maps, including nested extension maps,
have unique, nonblank text keys. Envelope field names and enum strings are case
sensitive. Text is well-formed UTF-8, with no CBOR string tags. Payload bytes are
never passed through a text decoder or interpreted as CBOR.

The publisher emits the following CBOR vocabulary:

| Value | CBOR representation / limits |
| --- | --- |
| Integral number | Major type 0 or 1, signed 64-bit value `-2^63..2^63-1` |
| Floating number | IEEE binary16, binary32 or binary64; NaN and infinities are representable |
| Text | Major type 3, valid Unicode encoded as UTF-8 |
| Binary value | Major type 2, arbitrary bytes, including empty |
| Boolean | `false` / `true` |
| Explicit null | CBOR `null` |
| List | Definite-length array, recursively containing this vocabulary |
| Map | Definite-length map with text keys, recursively containing this vocabulary |

No tags, indefinite-length containers/strings, undefined values, arbitrary CBOR
simple values or integers outside signed 64-bit are emitted. Each child map value
or list element increases depth by one from root depth zero; values at depth
greater than 32 are rejected by the publisher. Empty extension maps/lists are
valid. Scalar strings may be empty unless their field explicitly requires
nonblank text. Metadata may use a binary value or explicit null; missing a key
and supplying a null value are distinct.

Encoding is deterministic and matches `cbor2.dumps(value, canonical=True)` for
this vocabulary:

* Integers and definite lengths use the shortest encoding.
* Map entries are sorted first by encoded key byte length, then by unsigned
  lexicographic comparison of the complete encoded key bytes.
* Floating values use the shortest binary16/32/64 representation that preserves
  their value exactly, including the sign of zero. Infinities use binary16;
  every NaN is encoded as `f9 7e 00`.

Numeric meaning does not depend on the encoded float width. A standard DOUBLE
metric such as `signal.snr_db=9.25` can therefore arrive as a binary16 CBOR value.
An integral-looking floating value remains a CBOR float, rather than becoming a
CBOR integer. Clients must support all three float widths and preserve NaN as
unavailable data. Mandatory numeric measurements never use infinity to indicate
unavailability.

Clients must validate required fields and their types, topic/message-type
agreement, version, part count, and frame length before delivery to an
application. They should reject malformed known messages without terminating
the receive loop, and expose diagnostics. Unknown fields and extension keys
should be retained or ignored without failing an otherwise valid message.
An unsupported `schema_version` or unknown topic is not implicitly a v1 message.
Implementors should impose local byte/container bounds before allocating from
untrusted lengths. Publisher `max_message_bytes` defaults to 16,777,216 and
bounds the **sum of all multipart part lengths**; deployments can change it.
A client must configure its own acceptable bound rather than assume this is a
universal protocol maximum.

## 3. Common envelope fields

Every one of the three envelopes contains all fields below. Unknown additional
fields are extension points.

| Key | Required CBOR type | Meaning / permitted values |
| --- | --- | --- |
| `schema_version` | Integer | Exactly `1` |
| `message_type` | Text | `tm.frame`, `monitor.status` or `monitor.event`, as specified by the topic |
| `receiver_id` | Nonblank text | Configured receiver identity; default `receiver-1` |
| `decoder` | Nonblank text | Configured decoder identity; runtime normally supplies its decoder factory ID |
| `sequence` | Integer | Nonnegative `0..2^63-1`; independent sequence for each message kind |

Receiver and decoder identities are opaque identifiers, not Java class names or
network addresses. Per-publisher overrides are allowed, so clients must not
assume that frame and monitoring identities match merely because their endpoints
are on one host. There is no publisher instance/session UUID on this interface.

## 4. Frame envelope and payload

On `satdecode.v1.frame`, `message_type` is `tm.frame`. In addition to the common
fields, every frame envelope contains:

| Key | Required CBOR type | Meaning / permitted values |
| --- | --- | --- |
| `rx_time_ns` | Integer | Reception/sample time, UTC epoch nanoseconds, `0..2^63-1` |
| `rx_time_source` | Text | Exactly `hardware`, `sample_clock`, `host`, `recording` or `unknown` |
| `publish_time_ns` | Integer | Worker publication time, UTC epoch nanoseconds, `0..2^63-1` |
| `frame_type` | Nonblank text | Decoder-defined payload type; default `RAW`; no closed list is imposed |
| `frame_length` | Integer | Positive byte count, exactly equal to the part-2 length; current Java publisher uses at most `2^31-1` |
| `quality` | Text | Exactly `GOOD`, `BAD` or `UNKNOWN` |
| `metadata` | Map | Decoder/application annotations, possibly empty |

Part 2 is nonempty. Byte order, frame headers, coding/FEC/CRC, virtual channel
interpretation, and whether synchronization markers are included are defined by
the selected decoder and `frame_type`; ZMQ does not transform the payload.
`GOOD`/`BAD` describes the producer's validation result at its documented
boundary. `UNKNOWN` must not be assumed to mean a validated good frame.

For producers using the generic Java frame model, the mapping is deterministic:
`rx_time_ns` takes priority over `reception.timestamp`, which takes priority over
the frame timestamp. Explicit times accept integral epoch nanoseconds or an
ISO-8601 instant; the wire always contains integral epoch nanoseconds. Without an
explicit time annotation, `rx_time_source` defaults to `host`; with an explicit
time it defaults to `unknown`. A supplied source must belong to the list above.
`frame_type` takes priority over `frame.type`; `quality` takes priority over
boolean `frame.valid` (`true` -> `GOOD`, `false` -> `BAD`). Defaults are `RAW`
and `UNKNOWN`. These recognized annotations are removed; the explicit `metadata`
map is merged over remaining annotations and becomes envelope `metadata`.
Identity, sequence, publication time and frame length are publisher-owned.
A similarly named unrecognized annotation becomes an ordinary nested metadata
entry and cannot overwrite an envelope field.

## 5. Monitoring status

On `satdecode.v1.monitor.status`, `message_type` is `monitor.status`. Additional
required fields are:

| Key | Required CBOR type | Meaning |
| --- | --- | --- |
| `timestamp_ns` | Integer | Status publication time, UTC epoch nanoseconds, `0..2^63-1` |
| `metrics` | Map | Latest retained value for each known metric key |
| `metric_timestamps_ns` | Map of text -> integer | Most recent supplied observation time for each metric, UTC epoch nanoseconds, `0..2^63-1` |

The two maps have exactly the same keys. Values in `metrics` use the CBOR
vocabulary above. Most standardized metrics are scalar. Units and metric
descriptors are not transmitted as a separate schema; use the standardized
definitions below and decoder/source documentation for extension metrics.

Status is a **full snapshot of the publisher's retained cache**, not a delta.
Accepted source, decoder or application snapshots merge keys, replacing their
values and observation times. Keys absent from a later input snapshot retain
their last value and timestamp; there is no key deletion operation. Repeated
values may receive a newer observation timestamp. The timestamps represent
supplied snapshot observation times, not guaranteed last-change times.

The monitoring worker sends a status immediately after bind, then periodically
with a default interval of 0.5 seconds, and once after draining input during a
normal stop. Configurable `interval_s` is 0.001 through 86400 seconds. There are
no catch-up bursts after a delayed worker. The first status can have **empty
maps**, because input snapshots have not yet reached the worker. A standalone
monitoring distributor can publish a deliberately partial metric set. Status
envelopes remain valid in both cases.

The framework runtime polls/merges source, decoder and output metrics on its
management loop, normally up to four times per wall second, and submits a final
observation after source/decoder/frame-output drain. Wire cadence is independent
of this polling. A progressing status sequence or publication timestamp does
not guarantee that metric observations are fresh or decoding is active.

### Standard decoder metrics

Every conforming production decoder exposes these ten keys in every decoder
snapshot, including startup and terminal states. Once such a snapshot has been
merged into runtime monitoring, all ten are present in the retained status map.
The initial empty status and custom partial publishers described above are
exceptions to their presence on the wire, not alternative meanings for a key.
The authoritative Java constants are
`eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys`; the full producer
contract is in [the decoder specification](decoder-implementation-specification.md).

| Constant | Metric key | CBOR type / unit | Required meaning |
| --- | --- | --- | --- |
| `SIGNAL_SNR_DB` | `signal.snr_db` | Float, dB | Documented estimator; NaN before sufficient samples, when unreliable or inactive |
| `CARRIER_LOCK` | `carrier.lock` | Text | Independent carrier recovery state |
| `CARRIER_OFFSET_HZ` | `carrier.offset_hz` | Float, Hz | Tracked received carrier minus input I/Q centre; NaN unless running with carrier `LOCKED` |
| `SYMBOL_LOCK` | `symbol.lock` | Text | Independent timing/symbol recovery state |
| `SYMBOL_RATE_BAUD` | `symbol.rate_baud` | Float, baud | Documented nominal or measured symbol rate; NaN if unknown/unsupported |
| `FRAME_LOCK` | `frame.lock` | Text | Frame synchronization/validation state, with decoder-defined activity timeout |
| `FRAME_GOOD` | `frame.good` | Integer, frames | Nonnegative lifetime accepted valid frames after duplicate suppression, handed to the functional sink |
| `FRAME_BAD` | `frame.bad` | Integer, frames | Nonnegative lifetime rejected complete candidates at the documented validation boundary |
| `FRAME_TOTAL` | `frame.total` | Integer, frames | `frame.good + frame.bad` from the same captured counters |
| `FRAME_RATE_HZ` | `frame.rate_hz` | Float, Hz | Lifetime accepted frames divided by processed **signal** seconds; zero before signal time |

Each lock value is exactly one of `SEARCHING`, `LOCKED`, `UNLOCKED`, `UNKNOWN`.
`SEARCHING` means acquisition pending/in progress; `UNLOCKED` means absent/lost
lock; `UNKNOWN` means no supported/valid detector. Carrier, timing and frame locks
have independent meanings. Frame success must not be used to infer carrier or
symbol lock. After stop/EOF/failure/close supported stages become `UNLOCKED`;
unsupported stages remain `UNKNOWN`. Unknown floating measurements use NaN,
never null, infinity, guessed zero or a stale current measurement. A known
nominal symbol rate can remain valid after stop.

Counters do not reset at lock loss or repeated input. Duplicates and incomplete
candidates are excluded. `frame.bad` is not necessarily the number of lost
transmitted frames; multi-hypothesis decoders may count rejected complete branch
candidates. Counter overflow must fail rather than wrap. The good/bad/total
identity and carrier-lock/offset gating hold within a decoder snapshot; unrelated
metrics can otherwise be weakly consistent. Lifetime rate uses signal time, so
accelerated file replay does not inflate it. SNR and carrier offset retain each
decoder's estimator/reference/calibration limits; offset can include oscillator
error and modulation ripple in addition to Doppler.

### Runtime/output and extension metrics

Runtime monitoring also contains `output.frames_sent` and
`output.frames_dropped`, nonnegative integer totals across configured functional
frame distributors. They are runtime/output observations, not decoder acceptance
counters. Multiple distributors can count one decoded frame more than once.
For ZMQ, sent means local PUB acceptance, **not subscriber delivery**.

Current runtime extensions include `runtime.state`, `runtime.source_state`,
`runtime.decoder_state` (text enum names), and
`distribution.frames.<factory-id>.{received,sent,dropped,errors,queue_depth,queue_capacity,connected}`.
The counter/gauge fields are integers; `connected` is boolean and, for a ZMQ
publisher, means successfully bound. These are not mandatory decoder metrics.
Sources and decoders may add arbitrary documented keys, including
`decoder.<id>.*`. Clients should preserve unfamiliar values and must not require
a fixed overall metric set or infer lock states from other metrics. The current
publisher cache defaults to 1024 keys and fails explicitly when configured
capacity is exceeded; there is no silent eviction.

## 6. Monitoring events

On `satdecode.v1.monitor.event`, `message_type` is `monitor.event`. Additional
required fields are:

| Key | Required CBOR type | Meaning / permitted values |
| --- | --- | --- |
| `timestamp_ns` | Integer | Event occurrence/observation time, UTC epoch nanoseconds, `0..2^63-1` |
| `event` | Nonblank text | Extensible event name; no closed list |
| `severity` | Text | Exactly `TRACE`, `DEBUG`, `INFO`, `WARNING`, `ERROR` or `CRITICAL` |
| `details` | Map | Extensible event context, possibly empty |

Events are sent as accepted events are processed, interleaved with status, not
on a periodic event cadence. A producer's generic `reception.timestamp`
attribute can override event time and is removed from `details`. Remaining
attributes are preserved. Generic Java event `source` and `message` are not
separate wire fields; producers must explicitly include them in attributes if
needed by clients. Event names/details are not standardized into automatic
lock transitions; clients must handle unfamiliar names. There is no guaranteed
shutdown, disconnect or loss event, and a final event can itself be lost.

## 7. Time, ordering, restart and loss

All timestamp fields are integer nanoseconds since `1970-01-01T00:00:00Z`, not
monotonic clock readings or milliseconds. They fit signed 64-bit and are
nonnegative. Preserve integer precision: for example, a JavaScript client must
use a CBOR decoder capable of retaining 64-bit integers without IEEE-double
rounding. No relationship such as reception <= publication is required.
Accelerated file replay uses a processing-start UTC sample timeline and can
run ahead of host time; it does not reconstruct the original capture epoch.
Explicit hardware/recording times keep their own meaning. Publication/status
time reads host UTC on the publisher worker. Host clock accuracy and corrections
can affect it, and clocks on different hosts may disagree. Use a local monotonic
clock for receive timeouts, duration and rate measurement. See
[the UTC contract](time.md) for framework clock details.

Frame, status and event sequences are **three independent streams**. Each newly
constructed publisher starts its counters at zero. The first value a late
subscriber observes need not be zero. Within each kind, values on the wire are
strictly increasing until that publisher stops. Status allocates sequence on
publication; frames/events reserve sequence before queue admission. Single
producer queue drops can therefore produce gaps. Concurrent submissions retain
FIFO queue order, with sequence adjusted upward as necessary to preserve strict
increase. Values are exhausted after `2^63-1`; the worker fails instead of
wrapping.

Maintain sequence observations per `(endpoint, receiver_id, decoder,
message_type)`. A gap can indicate missed publications, queue drops or concurrent
submission adjustment; it does not identify the precise loss location or count
missing decoded frames. After a publisher restart, a lower sequence can be
legitimate. Since no instance ID exists, a client cannot unambiguously distinguish
a restart from a different publisher reusing the identity. No total order or
sequence correspondence is promised across endpoints or message types.

PUB/SUB is best effort. Subscription establishment takes time; a successful TCP
connection does not mean the publisher has received a subscription. Messages
published with no established subscriber, during reconnect, or beyond a peer's
high-water mark can disappear. A PUB send may still succeed locally. There are
no acknowledgments, retransmissions, replay, retained last value, persistence or
end-to-end delivery counters. Reconnecting subscribers resume future traffic.
Do not wait for a missed sequence to arrive or present publisher `sent` as a
delivery guarantee. Publisher queue overflow policies and local failure counters
describe only losses visible to that publisher.

## 8. Availability and receive-rate observations

The wire has no heartbeat on the frame service. Monitoring status acts as
periodic traffic only while its publisher worker is active. Three observations
must be kept distinct:

* **Transport connected:** the SUB transport has established a connection. It
  does not establish subscription propagation, decoder activity, signal lock or
  application-data freshness.
* **Data present:** at least one valid message arrived within a configured local
  inactivity window. A connected frame endpoint can legitimately be absent
  while no frames are decoded. A valid monitoring status can keep monitoring
  present while all decoder values are unchanged or stale.
* **Receive rate:** locally received valid message count/bytes divided by elapsed
  receive-observation time. This is not IQ sample rate, `frame.rate_hz`, or the
  publisher's enqueue/send rate. Define whether byte counts include topic and
  CBOR overhead; the Java reference client includes every multipart part.

Choose a monitoring inactivity window comfortably above the configured status
interval plus expected scheduling/network delay; the wire carries no negotiated
timeout. Frame inactivity windows depend on expected mission traffic. Silence
alone cannot distinguish idle decoding, absent signal, a lost subscription,
publisher failure or network loss. Inspect local transport events and metric
observation timestamps together. Use local monotonic receive instants for
inactivity instead of remote UTC timestamps.

## 9. Worked messages and independent Python receiver

A complete frame can consist of the following three parts:

```text
part 0 ASCII: satdecode.v1.frame
part 1 decoded CBOR:
{
  "schema_version": 1, "message_type": "tm.frame",
  "receiver_id": "rx-1", "decoder": "example", "sequence": 0,
  "rx_time_ns": 1700000000000000000, "rx_time_source": "recording",
  "publish_time_ns": 1700000000100000000,
  "frame_type": "RAW", "frame_length": 3, "quality": "UNKNOWN",
  "metadata": {"vcid": 3, "opaque": h'00ff', "optional": null}
}
part 2 hex: 00 7f ff
```

A status with a complete decoder observation can be:

```text
part 0 ASCII: satdecode.v1.monitor.status
part 1 decoded CBOR:
{
  "schema_version": 1, "message_type": "monitor.status",
  "receiver_id": "rx-1", "decoder": "example", "sequence": 4,
  "timestamp_ns": 1700000000500000000,
  "metrics": {
    "signal.snr_db": 9.25, "carrier.lock": "LOCKED",
    "carrier.offset_hz": -125.0, "symbol.lock": "LOCKED",
    "symbol.rate_baud": 9600.0, "frame.lock": "SEARCHING",
    "frame.good": 0, "frame.bad": 0, "frame.total": 0,
    "frame.rate_hz": 0.0
  },
  "metric_timestamps_ns": {
    "signal.snr_db": 1700000000400000000,
    "carrier.lock": 1700000000400000000,
    "carrier.offset_hz": 1700000000400000000,
    "symbol.lock": 1700000000400000000,
    "symbol.rate_baud": 1700000000400000000,
    "frame.lock": 1700000000400000000,
    "frame.good": 1700000000400000000,
    "frame.bad": 1700000000400000000,
    "frame.total": 1700000000400000000,
    "frame.rate_hz": 1700000000400000000
  }
}
```

An event on the same monitoring socket has its own independent sequence:

```text
part 0 ASCII: satdecode.v1.monitor.event
part 1 decoded CBOR:
{
  "schema_version": 1, "message_type": "monitor.event",
  "receiver_id": "rx-1", "decoder": "example", "sequence": 0,
  "timestamp_ns": 1700000000450000000,
  "event": "example.acquired", "severity": "INFO",
  "details": {"stage": "carrier"}
}
```

The following minimal receiver uses `pyzmq` and `cbor2`, and has no dependency
on a SatDecode client package. It demonstrates multipart boundaries and dispatch;
production code must add the full field/type/size validation in this document,
bounded receive storage, error reporting and inactivity/rate tracking. Python
`int` retains the timestamp precision, `bytes` represents CBOR byte strings,
`None` represents explicit null and `math.isnan(value)` detects unavailable
floating measurements.

```python
import io
import cbor2
import zmq

FRAME = b"satdecode.v1.frame"
STATUS = b"satdecode.v1.monitor.status"
EVENT = b"satdecode.v1.monitor.event"
expected = {FRAME: (3, "tm.frame"),
            STATUS: (2, "monitor.status"),
            EVENT: (2, "monitor.event")}

def receive(socket):
    parts = socket.recv_multipart()  # Consume one complete message.
    if not parts or parts[0] not in expected:
        return None
    count, kind = expected[parts[0]]
    if len(parts) != count or sum(map(len, parts)) > 16 * 1024 * 1024:
        raise ValueError("Invalid multipart size/count")
    stream = io.BytesIO(parts[1])
    env = cbor2.CBORDecoder(stream).decode()
    if stream.read(1) or not isinstance(env, dict):
        raise ValueError("Expected exactly one CBOR map")
    if type(env.get("schema_version")) is not int or env["schema_version"] != 1:
        raise ValueError("Unsupported version")
    if env.get("message_type") != kind:
        raise ValueError("Topic/type mismatch")
    if parts[0] == FRAME:
        if type(env.get("frame_length")) is not int or env["frame_length"] != len(parts[2]) or not parts[2]:
            raise ValueError("Invalid frame length")
        return kind, env, parts[2]
    return kind, env, None

with zmq.Context() as context:
    frames = context.socket(zmq.SUB)
    monitor = context.socket(zmq.SUB)
    for socket in (frames, monitor):
        socket.setsockopt(zmq.LINGER, 0)
        socket.setsockopt(zmq.RCVHWM, 4096)
    frames.setsockopt(zmq.SUBSCRIBE, FRAME)
    monitor.setsockopt(zmq.SUBSCRIBE, b"satdecode.v1.monitor.")
    frames.connect("tcp://127.0.0.1:5555")
    monitor.connect("tcp://127.0.0.1:5556")
    poller = zmq.Poller()
    poller.register(frames, zmq.POLLIN)
    poller.register(monitor, zmq.POLLIN)
    try:
        while True:
            for socket, flags in poller.poll(250):
                if flags & zmq.POLLIN:
                    try:
                        item = receive(socket)
                        if item:
                            kind, envelope, payload = item
                            print(kind, envelope, payload)
                    except (ValueError, cbor2.CBORDecodeError) as error:
                        print("Rejected message:", error)
    finally:
        frames.close()
        monitor.close()
```

To generate a deterministic test envelope, represent `h'00ff'` as
`bytes.fromhex("00ff")` and `null` as `None`, then call
`cbor2.dumps(envelope, canonical=True)`. Send topic, encoded envelope and payload
as one multipart operation; do not serialize the decoded notation above as JSON.

## 10. Java reference client

The reusable client is in
`eu.dariolucia.jsatdecode.distribution.zmq.client`. Its public API uses Java SE
types and exposes no ZeroMQ classes. It accepts a frame endpoint, a monitoring
endpoint, or both. Example:

```java
import eu.dariolucia.jsatdecode.distribution.zmq.client.ZmqClient;
import eu.dariolucia.jsatdecode.distribution.zmq.client.FrameKey;
import eu.dariolucia.jsatdecode.distribution.zmq.client.MonitoringKey;
import java.time.Duration;

try (var client = ZmqClient.builder()
        .framesEndpoint("tcp://127.0.0.1:5555")
        .monitoringEndpoint("tcp://127.0.0.1:5556")
        .availabilityTimeout(Duration.ofSeconds(2))
        .onFrame((payload, envelope) -> {
            // envelope is the complete frame envelope; mission data is in
            // envelope.get("metadata"). The payload is caller-owned.
            System.out.println(envelope.get(FrameKey.FRAME_TYPE.key()) + ": " + payload.length);
        })
        .onMonitoring(envelope -> {
            // Both monitor.status and monitor.event arrive here.
            System.out.println(envelope.get(MonitoringKey.MESSAGE_TYPE.key()));
        })
        .onStatus(status -> System.out.println(status))
        .onError((channel, error) -> error.printStackTrace())
        .build()) {
    client.start(); // Waits for local setup; remote service may still be absent.
    // Keep the management/application thread alive while receiving.
    Thread.sleep(30_000);
}
```

`onFrame` receives detached payload bytes and the complete frame envelope map;
`onMonitoring` receives a complete status or event envelope map. Maps and lists
are unmodifiable recursively; CBOR integers become `Long`, floats become
`Double`, binary values become caller-owned `byte[]`, and explicit null becomes
Java `null`. Applications can retain callback data. Metadata and metrics are
kept under their wire keys instead of being flattened. `FrameKey` and
`MonitoringKey` enumerate envelope keys; `MonitoringMetricKey` enumerates the
ten mandatory decoder keys plus the two runtime output totals. Their wire strings
are available through `key()` and retain the spellings specified above.

The client is one-shot. `start()` establishes the local receive worker and does
not wait for a remote connection or the first message. One owned daemon worker
serializes callbacks and socket operations for both channels. Callbacks should
return promptly; slow callbacks delay both channels, availability reports and
shutdown, and can cause receive backlog and PUB/SUB loss. No application delivery
queue or message history is retained. Runtime exceptions in callbacks are
reported through `onError` and reception continues. `requestStop()` is
callback-safe. Management code calls
`close()` for joined, idempotent shutdown; a callback must not join its own
worker. `isRunning()`, `failure()` and `status(Channel)` provide polling access.
Channels are `ZmqClient.Channel.FRAMES` and `.MONITORING`.

Each channel independently reports running/connected/data-present state, valid
message/byte totals, malformed-message count, last receive UTC time, current
observation UTC time and RX bytes/messages per second. Valid message byte counts
include topic, CBOR and payload parts, excluding ZMTP/TCP overhead. Rates use
monotonic elapsed time between periodic reports. Any valid message renews data
presence; it expires after
`availabilityTimeout` (default two seconds). Malformed input does not renew it.
Periodic status defaults to 250 ms, receive HWM to 4096 messages and accepted
multipart size to 16 MiB. Configure `statusInterval`, `receiveHighWaterMark` and
`maxMessageBytes` on the builder as needed. Client availability never rewrites
remote metric timestamps, locks or stale measurements.

These limits bound accepted messages and application-retained multipart input;
they are not a hard byte budget for transport queues. HWM counts messages, and
ZMQ may buffer multipart data before the application validates its aggregate size.

Complete malformed messages are counted, rejected and reported to `onError`;
they do not stop reception. A single transport part above `maxMessageBytes` can
instead cause transport disconnection before an application message is available
to count. Connection refusal while the service is absent reconnects normally
without an error callback. `failure()` retains fatal worker/setup failures;
nonfatal malformed-message and callback diagnostics are delivered to `onError`.
