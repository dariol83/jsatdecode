# Elektro-L GGAK verification

## Automated module and framework checks

From the repository root with JDK 21 or later:

```powershell
mvn -pl decoder_elektrol_ggak -am verify
```

The module runs its plain Java `ElektroLGgakTest` main through
exec-maven-plugin. The absence of JUnit/Surefire cases does not mean these
checks are skipped. Runtime dependencies are copied into `target/lib/`.

The suite covers ServiceLoader discovery, direct construction, runtime-shaped
configuration including `type`, unknown/malformed settings, supported rates,
raw-bit DC bypass, independent I/Q DC rejection and whole-chain partition
invariance with enabled and bypassed DC removal. Framing checks cover bit
alignment, both polarities, MSB-first payloads, search/hold marker thresholds,
sticky hold behavior, repeated payloads and incomplete trailing candidates.

Checksum tests cover unsigned octets, big-endian fields, both coverage
boundaries, either checksum octet, reversed checksum byte order, invalid fill,
recovery after rejection and preservation of transmitted checksum bytes. Vectors
include a zero body with checksum `0202`, an all-FF body with checksum `DB28`
and a body beginning with `80` with checksum `0282`. A complete rejected
candidate activates the hold-marker threshold without refreshing accepted-frame
activity. Sink failure counts as neither delivery nor checksum rejection.

The suite checks the ten mandatory status keys/descriptors/types across
lifecycle states, counters, unavailable carrier/timing/SNR values, concurrent
tap/status polling, sample-clock UTC metadata and overflow, source continuity,
rate mismatch, nonfinite input, one-shot lifecycle, frame activity expiry and
sink failure. Both example files are loaded by the core ConfigurationLoader.
Generated WAV input exercises the runtime, source rotator and synthetic
distribution through EOF.

Packaged CLI discovery can be checked with:

```powershell
java -cp 'decoder_elektrol_ggak/target/decoder-elektrol-ggak-0.1.0-SNAPSHOT.jar;decoder_elektrol_ggak/target/lib/*' eu.dariolucia.jsatdecode.cli.Main --list-decoders
```

The listing must include `elektrol_ggak` through its packaged provider resource.

## Independent GNU Radio comparison

[qualify_reference.py](../src/test/python/qualify_reference.py) constructs the
numerical [GRC reference](../grc/decoder_elektrol_ggak.grc) chain with generated
input and headless outputs. It loads the embedded PM converter and deframer
directly. The PMT timestamp constructor is adapted to 64-bit values for Windows;
no sample or framing decision is changed. GNU Radio 3.10.12, NumPy, SciPy and
PyYAML are required for this optional tool. Ordinary builds and operation do not
require Python.

The generated transmitter independently computes candidate checksums. GNU Radio
must recover exactly the checksum-valid transmitted subset before Java output
is compared. The Java harness checks byte order/content, rejected candidate
counts, checksum metadata and repeatability across 4096- and 137-sample blocks.
Its stage comparisons require exact sample counts at zero lag, no phase
correction, pre-symbol relative RMS error <=1e-4, symbol relative RMS <=1e-3
and zero recovered-symbol sign disagreements. These bounds accommodate Java/GNU
floating-point implementation differences; they do not assert universal bit
identity.

With the GNU Radio Python environment activated, run after the Maven build:

```powershell
$cp = 'decoder_elektrol_ggak/target/test-classes;decoder_elektrol_ggak/target/classes;decoder_elektrol_ggak/target/lib/*'
python decoder_elektrol_ggak/src/test/python/qualify_reference.py --output decoder_elektrol_ggak/target/reference
python decoder_elektrol_ggak/src/test/python/qualify_reference.py --output decoder_elektrol_ggak/target/reference-mixed --corrupt-frame-indices 0,5,15
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference/rate_1000000 1000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference/rate_2000000 2000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference-mixed/rate_1000000 1000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference-mixed/rate_2000000 2000000
```

The default fixtures contain 16 complete candidates. The clean fixture expects
16 deliveries and zero rejects; corruption at indices 0, 5 and 15 expects 13
deliveries and three rejects. Reference comparisons bypass initial DC removal;
enabled-path DC rejection and partition invariance have separate module checks.
Transmitter waveform assumptions are test stimuli, not spacecraft interface
specifications. Generated IQ, expected/output frames, traces, manifests and JSON
reports reside under ignored `target/`.

## M&M finite-input behavior

The Java timing adapter uses a deterministic one-output forecast. GNU Radio's
batch forecast can expose a different terminal symbol count for a finite
stream, so universal scheduler output identity is not a receiver guarantee.
Java output is independent of `accept` block partitions and appends no EOF
padding. The finite-input tools compare the controller with independent
single-output GNU Radio results:

```powershell
python decoder_elektrol_ggak/src/test/python/probe_mm_scheduler.py decoder_elektrol_ggak/target/reference/rate_1000000/costas.cf32
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakMmQualification decoder_elektrol_ggak/target/reference/rate_1000000/mm_finite
```

## WAV, ZMQ and desktop verification

`ElektroLGgakRuntimeQualification` wraps generated IQ in a float32 stereo WAV,
reads it through the real source/runtime, and activates ZMQ frame/status
distribution with bounded functional capture. It runs twice with UI OFF and
once with the Swing desktop observer ON, with source pacing disabled:

```powershell
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRuntimeQualification decoder_elektrol_ggak/target/reference/rate_1000000 1000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRuntimeQualification decoder_elektrol_ggak/target/reference/rate_2000000 2000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRuntimeQualification decoder_elektrol_ggak/target/reference-mixed/rate_1000000 1000000
```

On a headless host, append `--headless-only` to run the two UI OFF cases;
those runs do not verify a desktop UI. The harness compares output and checksum
accounting with independent truth and writes `runtime_qualification.csv` in the
fixture directory. Timing includes file reads, DSP, taps, frame delivery,
distribution and runtime startup/drain/shutdown; fixture creation and GUI startup
are excluded. Repeated measurements are needed to assess isolated UI overhead.
ZMQ local acceptance does not establish remote subscriber delivery.

Generated-signal checks do not establish live RF or mission-recording performance.
Pluto startup, FPGA readback and RF acquisition/loss/recovery require hardware.
Carrier/timing lock detectors and SNR estimation are unavailable; frame activity
does not substitute for them. Throughput at other supported input rates must be
measured in the relevant environment.
