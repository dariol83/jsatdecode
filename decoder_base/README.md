# Shared decoder infrastructure

`eu.dariolucia.jsatdecode:decoder-base:0.1.0-SNAPSHOT` is a Java 21 library for
mission decoders. It depends on the core contracts and jradio **1.121**. Core
does not depend on this module. The library registers no factory, thread,
transport, UI or standalone receiver.

## Components and ownership

All packages below start with `eu.dariolucia.jsatdecode.decoder.base`.

| Class | Responsibility |
| --- | --- |
| `AbstractDecoder` | Immutable metadata, volatile lifecycle state, sink installation and one-shot start checks |
| `DecoderLockStates` | Compile-time `SEARCHING`, `LOCKED`, `UNLOCKED`, `UNKNOWN` strings |
| `FrameAnnotationKeys` | Shared annotation key strings |
| `config.DecoderConfiguration` | Setup-only strict booleans, bounded numbers and unknown-key validation; defaults, aliases and bounds belong to each decoder |
| `dsp.ComplexDcBlocker` | Push adapter for independent jradio long-form I/Q filters, with exact bypass and explicit averaging length |
| `dsp.SimpleDcBlocker` | Independent single-pole I/Q mean estimator with exact bypass; not wired into a mission decoder |
| `dsp.CarrierConfidence` | Worker-owned narrowband residual-carrier confidence and smoothed PLL frequency; callers select power floors |
| `monitoring.CarrierStatus` | Immutable carrier lock/offset publication pair |
| `monitoring.DecoderMetricSchema` | Ten mandatory metric types and units, using core `DecoderStatusKeys`; descriptions and order belong to each decoder |
| `monitoring.DecoderSnapshots` | Polling-only immutable snapshot assembly, checked total and signal-time frame rate |

The decoder owner serializes lifecycle operations with `accept`. Status fields
are published at each decoder's report cadence. Counters can be weakly consistent
across metrics, while captured good/bad values produce the total and one captured
`CarrierStatus` supplies lock and offset together.

Snapshots are assembled by polling callers. Snapshot maps and descriptors are
outside the DSP sample/symbol path. Hinode caches its immutable 18-descriptor list.

## DSP utilities

`ComplexDcBlocker.sample` reads I before Q and produces one output per supplied
input. State persists across arbitrary blocks; the adapter requests no library
EOF tail. The worker reads the primitive `i`/`q` output fields and treats them
as read-only. Enablement is fixed for the instance lifetime. Filter state is
private and confined to the processing worker.

| Decoder | Default DC switch | Averaging length | Enabled delay |
| --- | --- | --- | --- |
| Hinode | true | 16384 | 32766 input samples |
| Elektro-L GGAK | false | 16384; configurable 2..65536 | 2*(length-1) input samples |

Decoders can use `CarrierConfidence` after PLL derotation with a selected
narrowband power floor. Confidence and frequency are read through `coherence()`
and `frequency()`; lock thresholds, persistence and status publication belong to
the mission decoder.

PLL, FIR design/convolution, oscillator, AGC, timing, SNR, lock decisions,
framing/FEC, duplicate policy, sample-clock arithmetic, tap batching and failure
handling are implemented by each receiver. See [decoder utilities](docs/decoder-utilities.md)
for the DC filter interfaces and behavior.

## Build and verification

From the repository root with JDK 21 or later:

```powershell
mvn -pl decoder_base -am verify
```

The module runs `DecoderBaseTest` and `SimpleDcBlockerTest` through
exec-maven-plugin. These are plain Java test mains, independent of JUnit discovery.
