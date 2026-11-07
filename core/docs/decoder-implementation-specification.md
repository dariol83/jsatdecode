# Authoritative JSatDecode decoder implementation specification

**Status: authoritative.** Applies to every production JSatDecode decoder,
including the built-in integration decoder and independent mission modules.
This document defines the decoder implementation contract alongside the
[framework specification](../specs/JSatDecode_Specification.md#monitoring-and-lock-semantics)
and the Java API.
Synthetic failure/incomplete-provider test fixtures may deliberately violate this
contract to test observer isolation.

## 1. Module, factory and configuration

- Implement the existing `Decoder` and `DecoderFactory` interfaces; class names shall be
  `<decoder name>Decoder` and `<decoder name>DecoderFactory`; register the
  factory with ServiceLoader. Keep mission DSP, settings, GRC/reference artifacts,
  tests and documentation in the decoder's module.
- The decoder module shall reside in an independent top-level Maven module
  `decoder_<decoder name>`; Its numerical/DSP details remain local. DSP entry point
  shall be named `<decoder name>Dsp`; settings entry point (record class) shall be named
  `<decoder name>Settings`.
- Depend on JSatDecode public contracts and appropriate implementation DSP tools.
  jradio is permitted internally; its types must not leak into framework API.
  Do not add mission-specific methods to the framework or mission-specific UI.
- Factories receive the complete **unprefixed decoder section**, including the
  routing selector `type` from `decoder.type`. Accept it only when matching the
  registered factory ID. It may be absent for direct programmatic construction.
  Reject unknown/malformed settings and unsupported rates early, before workers
  or external resources start. Do not pass global/source/UI settings into DSP.
- Document the modulation, rates, frequency translations, acquisition/tracking
  transitions, filters, coding conventions, ambiguity handling, framing, FEC,
  duplicate policy and output bytes. Identify each DSP stage as J (jradio),
  A (adapted) or N (native), with provenance and numerical differences.

## 2. Functional path and lifecycle

### Mandatory initial DC blocker

The shared decoder library also offers the single-pole estimator
`eu.dariolucia.jsatdecode.decoder.base.dsp.SimpleDcBlocker`; see
[decoder utilities](../../decoder_base/docs/decoder-utilities.md).
It is an optional reusable utility; mission decoders use the jradio blocks.

Every signal decoder MUST support a DC blocker as its first DSP stage, before
translation, filtering, decimation, gain control or carrier recovery. The standard
configuration property is `decoder.dcblocker=true` to enable it and
`decoder.dcblocker=false` to bypass it. Factories receive the unprefixed
`dcblocker` key and MUST validate it as a boolean during construction.
Support is mandatory; the property may be omitted only when a documented
decoder default applies.
The optional runtime `source.output_frequency_offset` rotator precedes decoder
input. It uses the source output sample rate and does not change this requirement:
the DC blocker remains the first decoder-owned DSP stage. See
[runtime translation](runtime.md#source-output-frequency-translation).
Mission decoders use jradio 1.121 `ru.r2cloud.jradio.blocks.DcBlocker`,
long form with independent I/Q instances, behind the shared `decoder_base`
push/pull adapter.
The adapter requests one output per input and never requests the library's EOF padding tail.
Bypass retains the input stream unchanged.

Bypass MUST pass original I/Q values without numerical alteration. Enabled
operation MUST remove DC independently on I and Q, retain bounded state across
arbitrary input block boundaries, and allocate no per-sample objects.
Document the algorithm, coefficients, startup behavior and default. Apply the
switch for the decoder lifetime; changing it requires a new decoder instance.
Raw RF observation may remain before the blocker and must be documented.
Validate enabled DC rejection, exact bypass, malformed configuration rejection
and block-partition invariance. Reference comparisons must use the same DC
blocker setting on both paths.

The integration-only dummy decoder serializes original IQ bits rather than
processing a radio signal and is exempt from this DSP-stage requirement.

- The management owner serializes lifecycle changes; the functional/source
  worker calls `accept` serially. Monitoring and tap readers may be concurrent.
  Validate configuration without starting work. Install the frame sink before
  start; enforce one-shot lifecycle where restart is unsupported.
- Read immutable interleaved complex sample blocks through primitive accessors.
  Preserve processing state across arbitrary block boundaries. Never infer
  sample rate, bit conventions or framing from the observer/UI.
- Emit immutable `AnnotatedFrame` objects through the supplied bounded sink.
  Document payload layout, validation outcome and annotations. Sink exceptions
  must propagate and fail decoding; do not silently discard functional frames.
- Quiesce producers before stop/close. Flush only data justified by the actual
  algorithm, without fabricating samples or valid trailing frames. Stop and close
  are idempotent; expose failure and release owned resources.
- No network/filesystem I/O, blocking observation queues, observer callbacks,
  Swing, logging sinks, FFT observation or management polling in the DSP hot path.
  Bound every buffer, history and queue. Avoid per-sample/symbol allocation.
  Prefer existing low-rate statistics/control points for status computation.

## 3. Mandatory decoder status

The public Java vocabulary is
`eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys`. Its public
`static final String` fields are the sole source of key literals in Java code.
Every decoder MUST expose **all ten keys**, with a matching `MonitoringDescriptor`
and a non-null value in every `MonitoringSnapshot`: configured, running,
searching/unlocked, stopped, failed and closed. A decoder may add documented
`decoder.<id>.*` diagnostics; optional common metrics are not mandatory here.

| API constant | Wire/status key | MonitoringType / Java value | Unit | Required meaning |
| --- | --- | --- | --- | --- |
| `SIGNAL_SNR_DB` | `signal.snr_db` | DOUBLE / Double | dB | Documented estimator; NaN before sufficient samples, when unreliable, or when inactive |
| `CARRIER_LOCK` | `carrier.lock` | STRING / String | empty | Independent carrier recovery state |
| `CARRIER_OFFSET_HZ` | `carrier.offset_hz` | DOUBLE / Double | Hz | Tracked received carrier minus input I/Q centre; NaN unless running with carrier LOCKED |
| `SYMBOL_LOCK` | `symbol.lock` | STRING / String | empty | Independent timing/symbol recovery state |
| `SYMBOL_RATE_BAUD` | `symbol.rate_baud` | DOUBLE / Double | baud | Documented nominal or measured symbol rate; NaN if unknown/unsupported |
| `FRAME_LOCK` | `frame.lock` | STRING / String | empty | Frame synchronization/validation state, with documented activity timeout |
| `FRAME_GOOD` | `frame.good` | LONG / Long | frames | Accepted valid frames after duplicate suppression, successfully handed to the functional sink |
| `FRAME_BAD` | `frame.bad` | LONG / Long | frames | Rejected complete candidates at the documented validation boundary |
| `FRAME_TOTAL` | `frame.total` | LONG / Long | frames | `frame.good + frame.bad`, calculated from the same captured counter values |
| `FRAME_RATE_HZ` | `frame.rate_hz` | DOUBLE / Double | Hz | Lifetime accepted frames divided by processed signal seconds; zero before any signal time |

The canonical offset key is **`carrier.offset_hz`**, not `carrier.offset`.
Frame annotations such as `frame_type`, `quality` or `frame_length` are separate
from monitoring keys.

### Availability, locks and measurements

Use the exact lock strings `SEARCHING`, `LOCKED`, `UNLOCKED`, `UNKNOWN`.
SEARCHING means acquisition is pending/in progress; UNLOCKED means lock is absent/lost;
UNKNOWN means the decoder has no supported/valid detector. Unsupported recovery
must not claim a lock. Successful frame/FEC extraction must not manufacture
carrier or timing lock. A symbol detector may require carrier lock if this
dependency is physically meaningful and documented.

After stop/EOF/close/failure, applicable recovery stages must not remain LOCKED;
use UNLOCKED, or UNKNOWN for unsupported stages. Unknown floating measurements
use **Double.NaN**, never Java null, infinity, a guessed zero or a stale value
presented as a current measurement. Known nominal rates remain valid after stop.
Counters and lifetime frame rate remain available after shutdown. Zero counters
before classification are legitimate. Snapshot construction stays on the polling
  caller; the schema is fixed before DSP starts. Descriptors may be materialized
  on the polling caller, but no registration or schema mutation belongs in DSP.

SNR documentation MUST specify the measurement point, estimator, smoothing,
cadence, useful range and calibration/reference limits. It is not automatically
RF power, calibrated SNR, Eb/N0 or an absolute quality percentage.

Carrier offset MUST use the initial received carrier tracking/reference, not an
unrelated translated subcarrier loop without undoing its translation. Document
the rad/sample-to-Hz conversion, input rate, sign, update cadence, tracking bounds
and estimator limitations. A measured offset can contain oscillator error and
modulation ripple as well as Doppler; do not claim a calibrated orbital Doppler
measurement. Publish lock and offset coherently; snapshots must never report
finite offset with a non-LOCKED carrier state or an inactive decoder.

### Frame accounting and ownership

Counters are nonnegative lifetime counts, with no reset at lock loss or source
repeat. Document where a candidate becomes complete and is accepted/rejected.
Exclude exact duplicates, unfinished trailing candidates and unmatched/noise
samples from classified totals. In a multi-hypothesis decoder, rejected complete
branch candidates may be counted individually, but this must be explicit:
`frame.bad` is not a count of lost transmitted frames or a calibrated FER.
Detect arithmetic overflow rather than wrapping counters or totals.

Frame rate uses **signal time**, not wall time, so faster-than-real-time replay
does not inflate the reported rate. Distinguish nominal rates from measurements.
Cross-metric observations may be weakly consistent, but the good/bad/total identity
must hold within one snapshot; a coherent carrier state/offset pair is mandatory.

`output.frames_sent`, `output.frames_dropped`, output connection/queue/error
metrics and output sequence numbers are **runtime/distributor-owned**, not
mandatory decoder keys. Decoder acceptance does not prove network delivery or
durable disk storage. Source activity/rate/frequency and runtime elapsed time are
likewise owned by their respective components. Do not fabricate them in a decoder.
Optional FEC corrected/uncorrectable counts or path metrics must document units,
candidate boundaries and unavailable behavior; do not invent unsupported values.

## 4. Taps, UTC and transport independence

Provide bounded, nonblocking RF, baseband and recovered-symbol taps when meaningful.
RF is the decoder input; baseband is a documented carrier-corrected/filter point;
symbols are the actual recovered constellation. Report correct rates. Unsupported
taps are absent rather than fake data. Observation failures must not affect DSP.
Never run FFT or constellation analysis in the source/decoder path or on Swing EDT.

All exposed sample/frame/status/event/tap timestamps use UTC epoch nanoseconds;
use `UtcTime.nowNanos()` for allocation-free host observations. Preserve source
UTC when deriving a frame sample time; check offset arithmetic. Only internal
elapsed-time, timeout and cadence calculations use raw monotonic time. See
[time.md](time.md) for host clock accuracy and recorded-file epoch semantics.
Pass generic immutable models to the framework. No decoder-to-ZMQ/file/UI imports
or mission-specific protocol transport code.

## 5. Required verification and handoff

- Verify factory discovery and actual runtime-shaped configuration, including type.
- Check the mandatory descriptor/value schema and types before start, during
  processing and after shutdown; validate unavailable values, independent locks,
  carrier offset sign/units/gating and frame accounting.
- Test arbitrary input block partitions, exact frame bytes/count/order and FEC
  outcomes. Validate UTC propagation and metadata without changing payloads.
- Compare recorded reference processing with UI disabled/enabled and distribution
  enabled, preserving frames; measure speed and keep observation overhead bounded.
  Verify missing/slow/failing observers do not stall or change DSP.
- For live decoders, test real source startup/acquisition, recovery and shutdown.
  Document the required recordings/hardware, commands and coverage limits.
- Keep module docs, this contract and the framework specification aligned with
  the implemented behavior.

## Shared decoder implementation support

The independent `decoder_base` module depends on core and jradio 1.121; core never
depends on a mission or on this support library. It implements shared DC adaptation,
minimal one-shot lifecycle bookkeeping, configuration helpers, exact string constants,
mandatory metric metadata and polling-only snapshot construction. Each mission
owns its DSP equations, primitive precision, update order, recovery cadence,
lifecycle cleanup, frame validation and tap buffering/failure semantics.
See [shared infrastructure](../../decoder_base/README.md).
