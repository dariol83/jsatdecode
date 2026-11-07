# Architecture

The functional path is `SampleSource -> optional frequency rotator -> Decoder ->
AnnotatedFrame -> FrameDistributor`. Monitoring snapshots, runtime events and
RF/baseband/symbol taps are independent observations. Observers must never block,
pace or backpressure acquisition or DSP.

The root Maven reactor builds core, `decoder_base`, `decoder_elektrol_ggak`,
`decoder_hinode` and `distribution` in dependency order. Decoder modules depend
on core, the shared library and jradio 1.121. Core has no dependency on a mission
module or the shared decoder library. Shared infrastructure is documented in
[decoder_base](../../decoder_base/README.md); numerical DSP, framing and mission
policies belong to each decoder.

## Project boundaries

Core contains the framework and [JSatInspector](jsatinspector.md), selected by
the first launcher argument `--inspector`. Inspector owns its application
lifecycle and reuses the PlutoPlus source adapter, FFT primitives and spectrum
renderer. It supplies application-local software filtering/decimation, spectrum
and waterfall analysis, and a bounded asynchronous CF32/CS16 writer. It does not
instantiate a decoder runtime or distributors.

Inspector's queue retains float sample blocks for either output format;
conversion belongs to the writer. Spectrum and waterfall drawing rates are
independent, and preferences retain storage format and waterfall cadence.
Count-only test runs own separate sources and preallocated primitive read
diagnostics. Their functional counting callback performs no Swing or filesystem
work. Management stops and joins each run before logging its diagnostics.

Core builds the application JAR and a thin JAR with classifier `modular` for the
[standalone distribution](../../distribution/README.md). The distribution
includes core, the shared library, both mission JARs and their runtime
dependencies. Launchers use `--module-path` and core's automatic module name
`eu.dariolucia.jsatdecode.core`. Service-provider resources support factory
discovery. Individual modules can be built with their dependencies through the
root reactor.

All packages below start with `eu.dariolucia.jsatdecode`.

| Package | Responsibility |
| --- | --- |
| `api.config` | Immutable configuration, strict conversion and value vocabulary |
| `api.lifecycle` | Lifecycle contracts, states and failures |
| `api.source` | Source/factory, sample format, immutable block and synchronous sink |
| `api.decoder` | Decoder/factory and metadata |
| `api.monitoring` | Pull observations, descriptors, types, immutable snapshots and `DecoderStatusKeys` |
| `api.taps` | Bounded latest-data observation contracts |
| `api.frame` | Annotated frame and functional sink contract |
| `api.distribution` | Distributors/factories, events, counters and overflow policies |
| `core.buffers` | Primitive complex taps with independent snapshot readers |
| `core.monitoring` | Setup metrics registry and fixed-schema monitoring store |
| `core.config`, `core.runtime`, `core.registry` | Properties/aliases, lifecycle ownership, frequency translation, merged observations and discovery |
| `core.distribution` | Bounded synthetic frame and monitoring outputs |
| `dsp.fft` | Reusable FFT/window plans, spectrum conversion, averaging and reduction |
| `source.file` | Shared file engine, range/pacing settings and source monitoring |
| `source.cf32`, `source.cs16`, `source.wav` | File sources/factories and WAV/RF64 metadata validation |
| `source.fmcomms2` | TCP IIOD acquisition, hardware configuration, diagnostics and IQ recording |
| `decoder.dummy` | Deterministic raw-IQ integration decoder |
| `distribution.zmq` | SatDecode v1 PUB sockets, canonical CBOR, UTC mapping, bounded workers and frame-file recording |
| `distribution.zmq.client` | SUB client, bounded wire decoding, callbacks and channel observations |
| `ui.swing` | Optional status/quality window, spectra, constellation and bounded EDT models |
| `inspector` | Independent acquisition, spectrum/waterfall display and IQ recording application |
| `cli` | Configuration/overrides, listing, runtime execution and shutdown hook |
| `util` | Shared UTC timeline |

The API imports Java SE and other API packages only. Configuration lives in
`api.config`, so factories do not depend on management implementations.

## Lifecycle and threading

Factories validate immutable component settings and return `CONFIGURED` objects
without starting workers. The runtime owner serializes lifecycle mutations on a
management thread. Successful start passes `CONFIGURED -> STARTING -> RUNNING`.
Stop passes `RUNNING -> STOPPING -> STOPPED`; failures enter `FAILED`. Close
releases resources even after failure, and `CLOSED` is terminal. Stop/close are
idempotent, including before start. The runtime and file sources are one-shot.
Lifecycle methods may join workers and therefore run outside DSP; state reads
are safely published.

A source invokes its sample sink synchronously and serially on its worker.
Decoder `accept` performs block DSP there. Sink wiring occurs before start.
Monitoring and tap reads may run concurrently. Frame submission queues work
without protocol I/O or waiting; output workers own transport operations.
Cross-thread observation models are immutable and safely published.

Runtime starts outputs before the decoder and source. Management
`awaitCompletion` polls component metrics outside DSP, detects EOF/failures, then
stops acquisition before decoder flush and output drain. Construction/startup
failures clean owned resources. First failure diagnostics survive close. CLI
shutdown hooks and normal cleanup share idempotent ownership; there is no
automatically started runtime supervisor.

## Data ownership and time

Samples use Java float `I0,Q0,I1,Q1` order. CF32/WAV sources read little-endian
float32 IQ; CS16 reads little-endian signed 16-bit components and scales each by
`1/32768` into float samples. `ComplexSampleBlock` copies the valid prefix at
construction and returns a copy from `iq()`. Indexed `i`/`q` access avoids allocation inside sample
loops. Sample rate is positive and finite; sample count and absolute stream
index are nonnegative. Zero-length blocks are valid. `ComplexSnapshot` owns its
data permanently.

`AnnotatedFrame` copies its payload on construction and access, and compares
payloads by content. Maps/lists are recursively snapshotted for configuration,
annotations, observations and events. Supported leaves are strings, primitive
numeric wrappers, booleans, characters, enums and immutable `BinaryValue`.
`NullValue.INSTANCE` represents explicit metadata null. Raw Java null, arrays in
metadata, arbitrary objects and non-string map keys are rejected. `NaN` is valid
monitoring data, including unavailable SNR, but is not a numeric configuration
setting. Callers supply acyclic metadata/configuration graphs.

Public boundaries provide lifetime isolation without per-sample objects. All
`timestampNanos` fields denote UTC nanoseconds since `1970-01-01T00:00:00Z`.
`UtcTime` supplies an allocation-free host-anchored timeline. Transports preserve
these instants without a second calibration. Explicit recording/hardware UTC
annotations can override reception time. Durations and scheduling use monotonic
time. See [time](time.md).

## Configuration

Required getters fail with `ConfigurationException` containing the key when
absent or malformed. Booleans accept `Boolean` or trimmed, case-insensitive
`true`/`false` strings. Integers accept numeric values or decimal strings only
when exactly representable in the requested signed range. Double conversion
rejects `NaN` and infinities. `getSection("source")` selects `source.*` and strips
the prefix, excluding `sourced.*`; missing sections are empty. Factories validate
domain-specific ranges before processing.

`ConfigurationLoader` reads UTF-8 properties and merges CLI overrides on
management threads. `FactoryRegistry` freezes `ServiceLoader` discovery before
construction and rejects duplicate IDs. `DistributionConfiguration` normalizes
supported identity/network aliases with explicit canonical keys taking
precedence. See [runtime](runtime.md).

## Monitoring, locks and output

Monitoring is pull-based: immutable snapshots are assembled at observer cadence,
not each DSP block. Every decoder exposes all ten `DecoderStatusKeys`, with
matching descriptors and typed values throughout its lifecycle. The
[decoder implementation specification](decoder-implementation-specification.md)
defines unavailable measurements, independent lock detectors and frame
accounting. Runtime and distributors own output delivery/drop counters.
Optional taps represent decoder input/preprocessing RF, post-carrier baseband
and recovered symbols.

Frame queue overflow defaults to `FAIL_RUNTIME`. `DROP_NEWEST` and `DROP_OLDEST`
require explicit selection and counters. Blocking upstream is not offered, and
functional loss must not be silent. Monitoring snapshots can be coalesced;
operational event loss has explicit policy and accounting. Distributors consume
transport-neutral objects. Producer APIs expose no ZMQ, jradio, hardware or
Swing types.

## Buffer mechanics

`ComplexFloatRingBuffer` implements `ComplexTap`. Each endpoint has its own
capacity and sample rate. Storage uses one atomic primitive long per complex
sample, packing raw I and Q float bits into eight bytes for atomic pair reads.

Exactly one producer owns nonoverlapping write calls; ownership moves require
external synchronization. Multiple observers may poll concurrently. Scalar
writes have constant work, and block writes perform
`O(min(input count, capacity))` work, skipping obsolete oversized prefixes.
Input arrays remain caller-owned and are not retained. Ranges are checked
before mutation; zero-size writes are no-ops. Sample-count overflow is rejected
before changing storage.

The producer marks a batch with an odd volatile revision, writes atomic cells,
publishes count/time and finishes with an even revision. Readers copy a
chronological suffix only when the revision is even and unchanged across the
copy. Readers make at most three attempts, then return empty. Producer writes
never allocate, lock, invoke observers, retry or wait for readers. FFT/UI
consumers tolerate empty snapshots.

Raw float bits, including nonfinite values, are retained. Snapshot allocation
and defensive copying occur on observer threads and are bounded by capacity.
The ring retains no snapshots. Its timestamp describes completion of the latest
write, rather than hardware reception, and is zero before the first write.

| Observation | Meaning |
| --- | --- |
| `samplesWritten` | Lifetime input count, including oversized prefixes |
| `overwrittenSamples` | `max(0, samplesWritten - capacity)`, independent of reader activity |
| `missedSnapshots` | Requests abandoned after three contention attempts |
| `capacitySamples` | Fixed complex sample capacity |

Taps have no consumer cursors or delivery acknowledgements. Overwrite counts do
not establish loss for a particular observer. Counters are independent gauges,
not a transactional status bundle.

## Monitoring storage

`MetricsRegistry` has an explicit maximum metric count. A setup thread registers
descriptors and initial values; duplicate keys, mismatched types, capacity
overflow and registration after build fail immediately. Build freezes the
schema and returns the same `MonitoringStore` on repeated calls.

Producers cache typed handles before start. Primitive setters do not box,
allocate, build maps or invoke consumers. Boolean/double values use volatile
fields; integer/long counters use atomics. Strings and enums use volatile
references with null/type checks. Integer additions use signed wraparound, so
producers select suitable ranges and reset policies. Updates to separate metrics
are independent; concurrent setters are last-writer-wins and additions are
atomic.

Polling reads fixed handles into immutable snapshots with registration-ordered
descriptors. Values from different instants may coexist in one snapshot. The
store retains only current values, starts no sampling thread, and has no
observer queue. Producers control string creation and size.

## Spectrum analysis

`dsp.fft` uses Java SE double arrays without UI or transport dependencies.
`ComplexFft` plans precompute bit reversal/twiddles for an explicit size; window
plans precompute symmetric coefficients. Plans are immutable and shareable with
disjoint caller buffers. Repeated operations use caller-owned destinations and
avoid per-bin objects or temporary arrays. `ExponentialAverager` is fixed-size
state confined to one analysis stream.

Forward FFT is unnormalized with a negative exponent; inverse is positive and
divides by N. Symmetric windows use denominator N-1 without automatic
normalization. Magnitude/power and relative-dB display are uncalibrated; no
FFT-size, window or bandwidth correction is implied. See
[spectrum conventions](spectrum.md) for formulas, aliasing rules and reusable
buffers. UI analysis runs on observer workers outside decoder/producer paths.

## File-source execution

`Cf32SampleSource` and `WavSampleSource` share a bounded float32 streaming engine.
`Cs16SampleSource` has its own bounded streaming implementation for headerless
signed 16-bit IQ. All three use `FileSourceSettings` for range selection, repeat,
block size and pacing. `WavHeader` scans RIFF/RF64 metadata with checked long
arithmetic and constant working memory, seeking over chunks rather than loading
them. Source APIs expose no UI, decoder or transport type.

Construction validates files without retaining a channel. Start opens the
channel, allocates reusable byte/float staging and starts one delivery worker.
Synchronous sink work paces functional processing. Optional realtime pacing
compares each block's cumulative end time with one monotonic start, avoiding
accumulated timer drift. Public sample blocks take an ownership-isolating copy.

EOF closes resources and enters `STOPPED`; startup/read/sink failures enter
`FAILED` with retained diagnostics. Management stop interrupts reads/pacing and
joins while preserving caller interruption. Callbacks use `requestStop`;
worker-thread stop/close is rejected to prevent self-join.

Block indices refer to the recording and rewind on repeat. Timestamps follow
cumulative signal time from a UTC start anchor and continue across repeats.
Wall-clock timestamps vary between runs; sample bytes, block boundaries, order
and recording indices are deterministic. Monitoring computes elapsed time and
realtime factor on polling. See [file sources](file-sources.md).

## Runtime translation and recording

Runtime inserts a synchronous rotator only for nonzero
`source.output_frequency_offset`, consuming that setting before source-factory
validation. Rotation uses each block's output sample rate, preserves phase and
timing metadata, and retains immutable ownership. Primitive scratch is bounded
by the largest source block. Source-owned IQ recording precedes translation.
See [runtime](runtime.md) for signs and replay semantics.

Bounded `AsyncOutput` mailboxes isolate synthetic consumers. A nonwaiting
semaphore bounds concurrent reservations; capacity excludes the executing
callback. Output workers own callbacks, and accounting reconciles after joined
shutdown. Optional verbose logging formats merged observations on the
management caller, logging independent lock changes and full metric tables
without adding a DSP callback or polling thread.

Frame-file recording adds a bounded output with the shared wire encoder,
length-prefixed messages, management drain/flush and `CREATE_NEW` protection.
See [frame recording](frame-recording.md).

## ZMQ boundary

The [distribution interface](zmq-distribution-interface.md) defines the wire
format and reusable `distribution.zmq.client.ZmqClient` API. One daemon client
worker owns subscriptions, socket monitors and serial callbacks. Connection
events are independent of monotonic inactivity and receive-rate observations.
Decoded maps/lists are recursively unmodifiable; arrays belong to callback
callers. Input retention is bounded, and there is no application delivery queue.
Management close joins; callbacks use `requestStop`.

`distribution.zmq` isolates production JeroMQ dependencies. PUB workers own
context/socket creation, bind, serialization, periodic full-status publication,
send and close. Producer calls enqueue immutable references in fixed-capacity
mailboxes without waiting. No encoding or network I/O occurs on sample workers.
Monitoring retains a separately bounded latest-metric map. Worker failure stops
submission, accounts for visible loss and retains the first diagnostic.

`SatDecodeWire` maps generic frames/events to v1 envelopes. `CanonicalCbor`
implements canonical ordering and shortest exact numeric forms. `UtcClock`
validates supplied UTC observations without recalibrating them. Explicit
recording/hardware UTC takes priority. Generic snapshots retain observation
times, not per-metric last-change times. PUB sends cannot establish downstream
delivery; bound status is not a subscriber acknowledgement. See
[ZMQ compatibility](zmq-compatibility.md).

Runtime forwards merged source/decoder/distribution observations and a final
snapshot after functional output drain without importing a protocol
implementation. Binary/null metadata uses the API's immutable `BinaryValue`
and `NullValue` models. Jackson is test-only.

## Desktop observation

Core runtime/source/decoder APIs import no desktop types. Runtime exposes
concurrent `Monitorable` and optional tap access without taking its lifecycle
lock. CLI loads monitoring UI lazily on an enabled request. Headless,
missing-desktop and ordinary UI failures leave decoding active.

`ui.swing` owns windows and one bounded observer scheduler. The worker polls
providers, formats rows and smooths relative SNR. EDT consumes immutable
`UiViewModel` instances and owns Swing mutations/disposal. A replaceable model
slot and one pending EDT callback bound display backlog. Metadata, rows, display
strings and tap requests have explicit limits.

Closing the window stops observation without runtime lifecycle access.
Management joins observation with a two-second budget and posts disposal
without waiting for EDT. UI failure stops its observer/window while source,
decoder and frame delivery continue. See [Swing UI](swing-ui.md).

Independent RF/baseband `SpectrumAnalysis` instances reuse lazy plans and
primitive scratch on the observer worker. Complete finite snapshots update
linear-power averaging and relative-dB spectra; empty/partial snapshots retain
the last frame. Axis changes, missing taps and invalid input reset averaging.
Per-spectrum failures disable only that trace.

`SpectrumFrame` owns peak-preserving reduced columns. EDT publishes plot widths
through atomics, then paints immutable frames with reusable paths. Painting
performs no provider reads or analysis. See [spectrum UI](spectrum-ui.md).

`ConstellationAnalysis` shares the worker with independent cadence and bounded
primitive history. It copies finite pairs into immutable models and computes
equal-axis bounds. Empty/unchanged reads retain the frame; missing taps, rate
changes and all-invalid data reset history. Symbol failures disable only that
stream. `ConstellationPanel` owns no provider/history and allocates no per-symbol
objects. See [constellation UI](constellation-ui.md).

## FMComms2 / PlutoPlus source

`source.fmcomms2` implements TCP IIOD in Java SE without native/vendor
libraries. Factory setup is inert until start. Management configures hardware;
one worker owns `READBUF`, normalized conversion and synchronous sample
delivery. XML metadata controls channel numbers, mask width, scan order and
signed-endian conversion. Exactly one RF receiver supplies the complex stream.

Socket-close cancellation unblocks startup and reads; management joins.
Monitoring uses cached values without network queries. Staging is bounded, UTC
is host-anchored sample time, and XML support is loaded when this source starts.
The source configures `source.fpga_decimation=1/2/4/8/16` independently of the PHY
rate. Metadata, sample clocks and buffer durations use the divided output rate.
Startup checks advertised capabilities and exact ADC/PHY readback; missing
support fails without software decimation or fallback. See
[FMComms2 source](fmcomms2-source.md).

Optional CF32 recording retains immutable block references in a bounded
source-owned queue. One writer serializes IQ through direct-buffer batches.
The producer performs no recording serialization or filesystem work. Source
completion includes writer finalization. `STOP_RECORDING` isolates storage
failures; `FAIL_SESSION` cancels acquisition. Runtime maps configuration-file
identity into recording settings. See [IQ recording](iq-recording.md).
