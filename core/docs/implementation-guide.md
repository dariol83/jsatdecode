# Implementation guide

This guide describes the implemented components and the constraints for changing
them. See [architecture](architecture.md) for ownership and threading details and
the component documents for configuration and public contracts.

## Build and module structure

The repository targets Java 21. The root Maven reactor builds these modules in
dependency order:

| Module | Responsibility |
| --- | --- |
| `core` | Public framework APIs, sources, runtime, distribution adapters, monitoring UI and JSatInspector |
| `decoder_base` | Shared lifecycle, configuration, DSP and monitoring helpers for decoder implementations |
| `decoder_elektrol_ggak` | Elektro-L GGAK decoder |
| `decoder_hinode` | Hinode telemetry decoder |
| `distribution` | Standalone application packages, launchers and packaged-distribution checks |

`decoder_base` depends on core and jradio 1.121. Mission DSP, framing, lock
detectors and accounting remain in the decoder modules. Core has no dependency
on these modules. Factories are discovered through `ServiceLoader`.

The four Java modules each contain a `module-info.java`; `distribution` is a
Maven packaging module. JPMS names, exported packages, service declarations and
module-path development commands are documented in [Java modules](java-modules.md).

Run from the repository root:

~~~text
mvn verify
mvn -pl decoder_hinode -am verify
~~~

The first command builds and checks the complete reactor; the second selects
one decoder and its reactor dependencies. The standalone ZIP and tar.gz archives
are created in `distribution/target`. Their launchers select JSatDecode or
JSatInspector and supply the module path and runtime dependencies. See the
[distribution guide](../../distribution/README.md).

Core tests use executable Java test harnesses bound to Maven's test phase.
These cover API contracts, bounded observation, FFT utilities, file sources,
runtime behavior, native-source protocol handling, ZMQ clients, Swing models and
Inspector behavior. Desktop and recording-dependent runners require their
respective environments and inputs; use their component documentation when
running them directly. A build result does not establish live hardware behavior
or mission performance on recordings that were not used in that run.

## Implementation constraints

- Source delivery and decoder DSP own the functional timing. UI, logging,
  analysis and transport work must not block or pace that path.
- Observations use bounded storage and best-effort snapshots with explicit loss
  accounting. Functional frame queues have an explicit overflow policy;
  `FAIL_RUNTIME` is the default.
- Public framework APIs use Java SE types. Source, decoder and monitoring
  contracts do not expose transport, hardware, jradio or Swing types.
- All public `timestampNanos` values are UTC epoch nanoseconds. `UtcTime`
  supplies the shared host-anchored timeline. Only durations and scheduling use
  monotonic time; transports preserve supplied UTC instants. See [time](time.md).
- Every decoder exposes the ten `DecoderStatusKeys` in descriptors and
  snapshots throughout its lifecycle. Unavailable measurements use `NaN` and
  unsupported lock detectors use `UNKNOWN`. Carrier, symbol and frame lock
  describe independent internal detectors. Output delivery/drop counters belong
  to runtime and distributors. See the
  [decoder implementation specification](decoder-implementation-specification.md).
- Processing uses blocks, primitive reusable buffers, bounded memory and
  explicit ownership. Public immutable blocks and snapshots retain their own
  storage; callbacks must not assume that caller arrays can be reused as shared
  mutable storage.
- Runtime owns configured components, stops acquisition before decoder flush,
  drains functional output, and closes each component once. Callbacks use
  nonblocking stop requests; management operations perform joins.
- Public and protected APIs document ownership, threading, time units and
  blocking behavior. Complex processing methods explain their state changes and
  numerical decisions.

## Sources, runtime and recording

CF32 and WAV sources share bounded float32 little-endian file streaming,
sample-range selection, repeat, optional realtime pacing and pull monitoring.
WAV accepts float32 stereo RIFF, extensible float and common RF64 recordings.
The CS16 source reads headerless little-endian signed 16-bit IQ and normalizes
components to floats by dividing by 32768.
File sources are one-shot and preserve recording indices while timestamps
continue across repeat passes. See [file sources](file-sources.md).

The FMComms2/PlutoPlus source implements TCP IIOD in Java SE. Setup validates
settings before acquisition; hardware configuration and buffer reads belong to
the source. Exactly one RF receiver supplies the public complex stream.
`source.fpga_decimation=1/2/4/8/16` selects native streaming decimation, with
capability/readback checks and no software fallback. See
[FMComms2 source](fmcomms2-source.md).

`source.output_frequency_offset` applies a signed IQ rotation between source and
decoder. Zero or absence selects the direct path. Runtime consumes this setting
before source-factory validation, uses each block's output sample rate and
preserves phase, indices, timestamps and immutable ownership. Source-owned IQ
recording precedes translation. See [runtime](runtime.md).

FMComms2 CF32 recording uses a bounded source-owned queue and writer. Defaults
are disabled recording, `user.home`, a 64 MiB queued-IQ bound, 4 MiB serialization
batches and `STOP_RECORDING` failure handling. Configuration identity defaults
to the properties-file stem; `source.recording.name` overrides it. Acquisition
performs no recording filesystem work, and completion joins writer finalization.
See [IQ recording](iq-recording.md).

`--store` / `storage.folder` adds frame-file output alongside the selected live
distributor. It records length-prefixed topic, CBOR metadata and payload bodies
using bounded `FAIL_RUNTIME` queueing, management drain/flush and `CREATE_NEW`
file protection. See [frame recording](frame-recording.md).

The management caller drives `awaitCompletion`, including failure detection and
merged monitoring publication. `-v` / `logging.verbose` logs observed lock
changes and periodic metric tables from that caller. See [runtime](runtime.md)
for CLI precedence, lifecycle, callback and configuration contracts.

## Distribution and reusable subscriber

ZMQ publishers use SatDecode v1 frame, status and event envelopes. Frames have
three multipart parts; monitoring messages have two. Socket operations,
serialization and publication run on bounded-output workers, outside sample
delivery. PUB acceptance cannot establish downstream delivery. Generic monitoring
snapshots provide observation times, not per-metric last-change times. See
[ZMQ compatibility](zmq-compatibility.md).

The [ZMQ distribution interface](zmq-distribution-interface.md) defines the
complete client-facing contract. `distribution.zmq.client` exposes `ZmqClient`,
`ClientStatus`, `FrameKey`, `MonitoringKey` and `MonitoringMetricKey` through Java
SE callbacks. Callbacks receive complete envelopes, including nested metadata,
UTC nanoseconds, integer/floating values, binary arrays and explicit null.
Containers are unmodifiable; arrays belong to the caller and are never reused.

One daemon worker owns SUB sockets, monitors and serial callbacks. Connection
state, valid-message inactivity and receive rate are independent observations.
Callbacks return promptly and use `requestStop`; management close joins the
worker. The client retains bounded multipart input, rejects malformed CBOR or
schemas, and drains rejected multipart messages to preserve message boundaries.
It provides no application delivery queue, replay or acknowledgements.

## Monitoring UI and JSatInspector

The optional Swing UI reads monitoring and bounded taps on an observer worker.
The EDT consumes immutable models through a coalescing publication slot. FFT,
averaging, display reduction and constellation history run outside painting and
DSP. Missing observations and unavailable SNR remain explicit; the UI does not
infer decoder locks. Closing the monitoring window stops observation while
decoding continues. See [Swing UI](swing-ui.md),
[spectrum UI](spectrum-ui.md) and [constellation UI](constellation-ui.md).

JSatInspector is a separate application in core, selected by the first launcher
argument `--inspector`. It uses the source adapter, FFT utilities and spectrum
renderer with application-local software filtering/decimation. Its independent
writer offers headerless little-endian CF32 and normalized, saturated CS16.
Queue overflow ends recording while acquisition continues. Spectrum refresh and
waterfall cadence are independently configurable. See
[JSatInspector](jsatinspector.md).

Inspector RX buffers are measured in complex samples, with default 16384 and
bounds 1..1048576. FFT sizes extend through 131072. Its count-only PlutoPlus test
mode owns fresh sources and bounded preallocated read diagnostics; management
handles progress, cancellation, joins and post-run logging. Counts include reads
after T1 through the T2 evaluation read exactly once. Loss significance uses
`max(0.1% of expected samples, 2 ms at the configured rate)`. The explicit test
lists contain 120 combinations. See the Inspector and source documents for
measurement-boundary and native-rate constraints.
