# JSatDecode implementation specification

This document describes the framework implemented in this repository. The Java
interfaces in `core/src/main/java/eu/dariolucia/jsatdecode/api` define its public
contracts. Configuration details and application behavior are documented in the
linked component guides.

## Modules and dependencies

The Maven reactor contains these modules:

| Module | Responsibility |
| --- | --- |
| `core` | Public API, configuration, runtime, sources, outputs, monitoring, FFT, Swing observer and JSatInspector |
| `decoder_base` | Shared decoder lifecycle, monitoring, configuration and DSP adapters |
| `decoder_elektrol_ggak` | Elektro-L GGAK decoder |
| `decoder_hinode` | Hinode decoder |
| `distribution` | Standalone application archive and launch scripts |

Java 21 is the compilation baseline. Public API signatures use Java SE and other
framework API types. Core uses JeroMQ internally for ZMQ transport. The shared
decoder library depends on core and jradio 1.121; mission modules depend on the
shared library. Core has no dependency on mission modules or jradio.

The four Java JARs contain explicit JPMS descriptors. Core packages the
`MainLauncher` entry point in `eu.dariolucia.jsatdecode.core`. The standalone
distribution includes the same core JAR, shared decoder support, the two mission
modules and their runtime dependencies. The distribution launchers use the Java
module path and discover factories through descriptor `uses`/`provides`
declarations. Service-provider resources also retain classpath compatibility.
See [Java modules](../docs/java-modules.md) and the
[distribution guide](../../distribution/README.md).

## Functional path and observation

```text
SampleSource -> optional source frequency translation -> Decoder
             -> AnnotatedFrame -> independent FrameDistributor workers

Source / decoder / runtime -> monitoring snapshots and runtime events
                           -> MonitoringDistributor workers
                           -> optional Swing observer
Decoder RF / baseband / symbol taps -> optional Swing analysis and plots
```

The source owns acquisition timing. Its worker calls the sample sink serially;
the decoder performs block DSP on that functional caller. Decoder frame
submissions use bounded, nonwaiting output queues. Workers own network/file I/O.
A frame can be submitted to multiple independent outputs; a failure after partial
fanout does not undo deliveries already accepted.

Monitoring and tap reads run on management or observer callers. They may occur
concurrently with sample processing. They must not block acquisition or DSP.
The Swing observer uses bounded snapshots and replaces stale display updates.
No display rendering, observation FFT, transport I/O or management polling belongs
on the sample-processing path.

## Public contracts and discovery

| Package | Contracts |
| --- | --- |
| `api.config` | Immutable configuration and supported value types |
| `api.lifecycle` | Lifecycle methods, component states and failures |
| `api.source` | Sources, factories, sample blocks, format and sinks |
| `api.decoder` | Decoders, factories and descriptors |
| `api.frame` | Immutable annotated frames and functional frame sink |
| `api.monitoring` | Pull snapshots, descriptors, value types and standard decoder keys |
| `api.taps` | Optional RF, baseband and recovered-symbol observations |
| `api.distribution` | Frame/monitoring outputs, factories, events and loss counters |

`SampleSourceFactory`, `DecoderFactory`, `FrameDistributorFactory` and
`MonitoringDistributorFactory` use `ServiceLoader`. Factory IDs are unique within
each contract. `FactoryRegistry` rejects duplicates and unknown selections.
Factories validate settings and return configured components without starting
workers. Embedders can also supply explicit providers and configured components
to `JSatDecodeRuntime`.

The core source IDs are `wav`, `cf32`, `cs16` and `fmcomms2`. Core provides the
integration decoder `dummy`. Mission providers are supplied by the independent
decoder modules. Frame-output IDs are `synthetic`, `zmq` and `frame-file`;
monitoring-output IDs are `synthetic` and `zmq`.

## Lifecycle and runtime ownership

Component states are `CONFIGURED`, `STARTING`, `RUNNING`, `STOPPING`, `STOPPED`,
`FAILED` and `CLOSED`. The management owner serializes lifecycle mutations;
state reads are safely published. Start moves through `STARTING` to `RUNNING`,
or enters failure. Stop and close are idempotent, including before start and
after failure. Close releases resources and makes the component terminal.
The runtime and its supplied implementations use a one-shot lifecycle.

`JSatDecodeRuntime.create` resolves all selected factory IDs before constructing
components. It closes successfully constructed components if a later construction
fails. Sink wiring happens before start. The runtime takes exclusive lifecycle
ownership of its components and requires distinct, configured instances.

Start order is frame outputs, monitoring outputs, decoder, then source. A startup
failure cleans all owned components, including partially started components.
`awaitCompletion()` runs on a management thread, polls for completion/failure at
250 ms intervals, and forwards merged monitoring observations. Calling `start()`
alone does not create a supervisor thread.

Shutdown stops and joins acquisition, stops/flushes decoder processing, drains
frame workers, publishes the final observation and event, then drains monitoring
workers. Close releases components in reverse construction order. `failure()`
retains the first failure after cleanup; further cleanup failures are suppressed
on it. Interrupted waiting stops components, restores interruption and propagates
`InterruptedException`.

Output workers drain accepted work during a healthy stop. Worker callbacks must
eventually return; output stop joins without an arbitrary drain timeout.
Lifecycle operations belong on management callers. Output callbacks must not
stop or close their own worker or runtime.

## Immutable data and memory ownership

`ComplexSampleBlock` contains interleaved float `I0,Q0,I1,Q1` samples, a valid
complex-sample count, a nonnegative stream/recording sample index, a positive
finite complex sample rate, and a UTC timestamp. Construction copies the valid
prefix. `iq()` returns a copy; primitive `i(index)` and `q(index)` accessors
avoid copying inside DSP loops. Empty blocks are valid and delivered blocks
remain retainable after a callback returns.

`AnnotatedFrame` snapshots its byte payload and annotations. Accessing `data()`
returns a copy. Equality compares payload content and metadata. `ComplexSnapshot`
owns its data and is retainable independently of subsequent tap writes.

Configuration, annotations, event attributes and monitoring values use the
`ImmutableValues` vocabulary: recursively copied string-keyed maps and lists,
immutable primitive wrappers, strings, enums and `BinaryValue`. Explicit null is
represented by `NullValue`; Java null and arbitrary mutable objects are rejected.
Raw binary arrays cross value-map boundaries through the copying `BinaryValue`
wrapper.

Primitive sample/tap processing reuses bounded storage and avoids objects per
sample. Block and frame boundaries perform the copies required by the immutable
public API. Monitoring maps, snapshot arrays and display models are materialized
on observer/management callers. Mission filter and framing state belongs to its
decoder instance and survives arbitrary input block boundaries.

## UTC timestamps and elapsed time

Public `timestampNanos` fields represent nanoseconds since
`1970-01-01T00:00:00Z`, including samples, frames, taps, monitoring and runtime
events. Process-relative `System.nanoTime()` values are used internally only for
elapsed durations, pacing and timeout budgets.

`UtcTime` anchors once to the host UTC clock and advances with the monotonic
clock. Reads allocate nothing and do not observe subsequent host clock steps.
Accuracy therefore depends on host synchronization at initialization; starting a
new process recalibrates that anchor. Conversion to epoch nanoseconds checks
arithmetic overflow.

File sources without an original capture epoch use processing-start UTC plus
cumulative delivered signal duration. The sample index identifies the selected
recording position; repeating a segment rewinds its index while its generated
timestamp timeline continues. This timeline identifies replay processing, not
the original capture time. Accelerated replay can place it ahead of current host
time.

Transports preserve UTC reception timestamps without a second epoch calibration.
Explicit `rx_time_ns` annotations take precedence over `reception.timestamp`,
which takes precedence over the frame timestamp. Wire times retain the
nonnegative signed-long epoch constraint. Textual instants use an explicit offset
and normalize to UTC. Decoded-frame filenames use UTC with
`yyyy_MM_dd_HH_mm_ss`; source IQ and Inspector recorders use their documented
UTC naming patterns. Encoded mission payload bytes are passed through without
host-time rewriting.

## Configuration and command line

Global configuration is UTF-8 Java properties. Precedence is defaults, properties
file, repeated `--set key=value` overrides, then dedicated CLI switches. The last
occurrence within a level wins. Component factories receive their unprefixed
section, including the optional `type` selector. `Configuration` performs strict
typed conversion with key-specific diagnostics.

| Section | Purpose |
| --- | --- |
| `source.*` | Source selection, file/radio settings and runtime frequency translation |
| `decoder.*` | Decoder selection and decoder-specific settings |
| `distribution.frames.*` | Selected frame output and queue/transport settings |
| `distribution.monitoring.*` | Selected status/event output and settings |
| `storage.folder` | Additional independently queued frame-file recording |
| `logging.verbose` | Management-side monitoring logs |
| `ui.*` | Optional observer and plot settings |

Source and decoder types are required for decoding. Output defaults select
synthetic frame and monitoring consumers; UI and verbose logging are disabled
by default. Identity/network aliases are normalized without implicitly selecting
a transport. Relative paths are resolved against the process working directory.
Java-properties escaping or forward slashes are required for Windows paths.

The CLI supports help/version, source/decoder listings, `--config`, `--set`,
`--headless`, `--ui`, `--ui-demo`, `--store` and `-v`. Help and listings need no
source or decoder configuration. The launcher selects JSatInspector when
`--inspector` is the first argument. See the [runtime guide](../docs/runtime.md)
for complete commands and property definitions.

A finite nonzero `source.output_frequency_offset` inserts a complex rotator before
decoder acceptance:

```text
y[n] = x[n] * exp(+j * phase[n])
phase increment = 2 * pi * offset_hz / source_output_sample_rate
```

Positive offsets shift frequencies upward. Phase starts at zero and continues
across blocks and file repeats. A rate change updates the increment without
resetting phase. Absent and zero offsets retain the direct source/decoder path
and preserve sample bits. Rotation retains block count, indices, timestamps and
sample rate; source-owned recordings contain the acquired IQ before translation.

Verbose logs run on the management caller, report observed lock changes and
complete sorted monitoring snapshots, and use single physical lines on standard
error. Observation timestamps use UTC. Snapshot logging has a two-second wall-time
cadence independent of replay speed; lock polling follows runtime monitoring.

## Sources

| Source | Implemented input |
| --- | --- |
| `cf32` | Headerless little-endian interleaved IEEE float32 IQ |
| `cs16` | Headerless little-endian signed 16-bit interleaved IQ, converted to float samples |
| `wav` | RIFF/RF64 stereo IEEE float32 IQ with header sample rate |
| `fmcomms2` | FMComms2/PlutoPlus IIOD TCP acquisition using Java sockets |

File sources share bounded block delivery, selected ranges, optional repeat and
optional real-time pacing. Range offsets and lengths count complex samples;
`start_offset` is inclusive, `end_offset` exclusive, and `length` is mutually
exclusive with `end_offset`. Factories reject invalid geometry, unsupported WAV
metadata, unreadable files and ranges outside the recording. Repeating an empty
selection is rejected. The source closes channels during normal completion,
cancellation and failure.

The `fmcomms2` source validates device configuration and sample-format/rate
readback at startup. `source.sample_rate` is the PHY rate; with native FPGA
decimation, delivered block rates use the verified output rate. Sample conversion,
stream delivery, source monitoring and optional asynchronous IQ recording belong
to this adapter. No native library is needed for this network interface. See
[file sources](../docs/file-sources.md) and the
[IIOD source](../docs/fmcomms2-source.md) for configuration and
firmware-dependent decimation behavior.

## Decoder implementations

`Decoder.accept` is serial functional work. It receives immutable blocks,
preserves DSP state between calls, emits immutable frames and propagates sink
failures. Monitoring/tap observers can read concurrently. DSP implementations do
not own runtime/UI lifecycle or call transports directly.

The built-in `dummy` decoder emits one little-endian float-IQ payload per input
block with its sample index and source UTC timestamp. It performs no mission
DSP, reports unavailable measurements and unsupported locks, and serves as an
integration component.

The independent [Hinode](../../decoder_hinode/README.md) and
[Elektro-L GGAK](../../decoder_elektrol_ggak/README.md) modules contain their
mission DSP, settings, service factories and tests. Their documented input
rates, frame-validation boundaries, ambiguity handling, taps and output payload
layouts define their operational behavior.

Signal decoders support `decoder.dcblocker` as their first decoder-owned DSP
stage, before translation, filtering, decimation, gain or recovery. The optional
runtime source rotator precedes decoder input. Disabled filtering preserves
original I/Q values. Enabled filtering uses the shared jradio long-form
`DcBlocker` adapter with independent I/Q instances, bounded state across blocks,
and one output per input without requesting EOF padding. Hinode defaults to
enabled with length 16384; Elektro-L GGAK defaults to disabled and accepts a
configurable length. See the
[decoder implementation contract](../docs/decoder-implementation-specification.md)
and [shared utilities](../../decoder_base/docs/decoder-utilities.md).

## Monitoring and lock semantics

Every decoder exposes these ten `DecoderStatusKeys` in its descriptors and
snapshots throughout its lifecycle:

| Key | Type / unit | Meaning |
| --- | --- | --- |
| `signal.snr_db` | double / dB | Documented estimator; `NaN` when unavailable |
| `carrier.lock` | string | Independent carrier recovery state |
| `carrier.offset_hz` | double / Hz | Received carrier minus input center; `NaN` unless running with carrier lock |
| `symbol.lock` | string | Independent timing recovery state |
| `symbol.rate_baud` | double / baud | Documented nominal or measured symbol rate; `NaN` if unknown |
| `frame.lock` | string | Frame synchronization/validation state |
| `frame.good` | long / frames | Accepted valid frames after duplicate suppression and successful sink submission |
| `frame.bad` | long / frames | Rejected complete candidates at the documented validation boundary |
| `frame.total` | long / frames | Good plus bad from the same captured counter values |
| `frame.rate_hz` | double / Hz | Accepted frames per processed signal second; zero before signal arrives |

Lock values are `SEARCHING`, `LOCKED`, `UNLOCKED` and `UNKNOWN`. Unsupported
detection uses `UNKNOWN`. Successful frame/FEC extraction does not manufacture
carrier or symbol lock. Inactive recovery stages do not retain a current locked
claim. Unknown floating measurements use `Double.NaN`, while lifetime counters
remain available after shutdown. Source/runtime/output delivery counters are
separate from decoder validation counters.

Snapshots are immutable observations and may be weakly consistent. The runtime
merges source, decoder and frame-output metrics, along with runtime/source/decoder
lifecycle states. Decoder descriptors take precedence over source descriptors
with the same key. Distribution snapshots report received/sent/dropped counts,
errors, queue depth/capacity and connection status without protocol I/O.

## Taps, FFT and display processing

`TapProvider` exposes optional RF, baseband and recovered-symbol taps.
`ComplexFloatRingBuffer` is a bounded latest-data store with exactly one producer
and independent concurrent readers. It packs each float I/Q pair into an atomic
primitive long. Producer writes neither wait nor allocate nor invoke observers;
overwritten observation samples do not affect decoder output. Readers retry at
most three times and return an empty snapshot under contention. Snapshot copying
and allocation occur on the reader.

`dsp.fft` implements reusable radix-2 complex FFT plans over double arrays,
forward/inverse transforms, symmetric rectangular/Hann/Hamming/Blackman windows,
shifted spectra, linear-power averaging, dB conversion and bounded display
reduction. Plans and scratch arrays are reused by observation analysis. FFT work
for spectrum display runs on the observer worker; decoder DSP can use its own
algorithm-specific processing independently.

The optional Swing/Java2D window includes the generic metric table, SNR quality
bar, three independent lock indicators, RF/baseband spectra and constellation.
The generic quality profile interpolates -5/0/5/10/15 dB to 0/25/50/75/100 percent
and applies configurable smoothing; it is a relative display scale. Missing or
nonfinite SNR displays as unavailable. Lock observations remain separate from
the SNR score.

All Swing construction/model changes/rendering run on the EDT. One observer
worker polls providers and builds immutable bounded display models. An atomic
latest-model slot coalesces slow display updates; at most one update runnable
waits on the EDT. Disabled UI starts no observer or analysis worker. Closing the
window ends observation while decoding continues. Headless or UI failures disable
observation without changing functional runtime state. See
[Swing UI](../docs/swing-ui.md), [spectra](../docs/spectrum-ui.md) and
[constellation](../docs/constellation-ui.md).

## Outputs and delivery semantics

All output submissions are bounded and nonwaiting. The default overflow policy
is `FAIL_RUNTIME`; explicit `DROP_NEWEST` and `DROP_OLDEST` policies count loss.
Workers own serialization and transport operations. Frame delivery must drain
accepted messages or expose failure/loss. Observation coalescing is distinct from
functional frame dropping.

Synthetic outputs consume/count queued messages and retain no data history.
Their callbacks support embedding and tests. Monitoring snapshots and events
share the synthetic output FIFO. A running synthetic worker's connection status
identifies its worker state.

ZMQ outputs implement SatDecode v1 PUB services through JeroMQ:

| Topic | Multipart parts |
| --- | --- |
| `satdecode.v1.frame` | Topic, canonical CBOR envelope, raw payload |
| `satdecode.v1.monitor.status` | Topic, canonical CBOR envelope |
| `satdecode.v1.monitor.event` | Topic, canonical CBOR envelope |

Frame payload bytes are unchanged. Envelopes include schema/message identity,
receiver/decoder identity, sequence and UTC metadata. ZMQ monitoring retains the
latest metric values and observation timestamps and publishes at its own cadence.
Connection/sent counters describe local publishing, not subscriber receipt; PUB
has no subscriber acknowledgement. The exact schema, validation rules and
receiver behavior are specified in the
[distribution interface](../docs/zmq-distribution-interface.md).

`frame-file` and `storage.folder` record frame messages through an independent
bounded worker. Files preserve the ZMQ topic/envelope/payload and add big-endian
length prefixes for disk boundaries. Filenames use processing-start UTC. See
[frame recording](../docs/frame-recording.md). The Java ZMQ client in
`distribution.zmq.client` provides bounded wire validation and callback delivery.

## JSatInspector

JSatInspector is a separate Swing application in core that directly reuses the
IIOD source, FFT and spectrum renderer. It constructs no decoder, runtime or
frame/monitoring distributors. It supports configurable spectrum/waterfall
refresh, stateful software filtering/decimation, and independently controlled
asynchronous CF32/CS16 recording. Test mode uses a separate count-only source and
reports sample-delivery measurements. See the
[Inspector guide](../docs/jsatinspector.md) for controls, acquisition settings,
recording layout and test-mode behavior.

## Build and verification

Build the repository with Java 21 or later:

```text
mvn verify
```

`mvn -pl core verify` builds and checks the independent framework. The reactor
also runs shared-library and mission tests, packages the standalone distribution,
and validates its content/launchers. Maven invokes the repository's Java test
entry points; core's tests cover API contracts, observations, FFT, file sources,
runtime ownership, Inspector behavior, the IIOD adapter and ZMQ clients. Desktop,
live-hardware and recording-driven tools require their stated environment or
input fixtures. Component guides and test entry points document those
requirements.
