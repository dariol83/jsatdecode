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

The updated decoder suite passes **317 checks**, including independent weak
carrier tones at +/-23 kHz with amplitude 1e-4, AFC two-FFT acquisition and
delay, silence behavior, and analytical normalization power-step and
silence-state checks.

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
sink failure. WAV, CS16 and Pluto examples are loaded by the core ConfigurationLoader.
Generated WAV input exercises the runtime, source rotator and synthetic
distribution through EOF.

Packaged CLI discovery can be checked with:

```powershell
java -cp 'decoder_elektrol_ggak/target/decoder-elektrol-ggak-0.1.0-SNAPSHOT.jar;decoder_elektrol_ggak/target/lib/*' eu.dariolucia.jsatdecode.cli.Main --list-decoders
```

The listing must include `elektrol_ggak` through its packaged provider resource.

## Independent GNU Radio comparison

[qualify_reference.py](../src/test/python/qualify_reference.py) constructs the
numerical [GRC v1.5 reference](../grc/decoder_elektrol_ggak.grc) chain with generated
input and headless outputs. It loads the embedded AFC analysis, PM converter,
power-normalization equations and deframer directly. The PMT timestamp
constructor is adapted to 64-bit values for Windows. GNU Radio 3.10.12, NumPy, SciPy and
PyYAML are required for this optional tool. Ordinary builds and operation do not
require Python.

The GRC's embedded AFC applies the final estimate from a scheduler `work` call
to that entire call, and its normalizer can end a 250-sample segment at a short
`work` boundary. The harness makes those update positions explicit: AFC
analysis occurs before mixing each 65536th channel sample, and normalization
uses complete 250-sample segments. It preserves the embedded analysis, power,
acceptance, delay and mixing equations. Neither stage drains buffered samples
at EOF. This canonical scheduling matches Java's sample clock independently of
source partitions; it does not claim identical output from every GNU Radio
scheduler partition. See [receiver scheduling details](receiver.md#numerical-identity-and-finite-input).

The generated transmitter independently computes candidate checksums. GNU Radio
must recover exactly the checksum-valid transmitted subset before Java output
is compared. The Java harness checks byte order/content, rejected candidate
counts, checksum metadata and repeatability across 4096- and 137-sample blocks.
Its stage comparisons require exact sample counts at zero lag, no phase
correction, pre-symbol relative RMS error <=1e-4, symbol relative RMS <=1e-3
and zero significant recovered-symbol sign disagreements. A sign disagreement
is excluded from that assertion only when both compared complex-symbol
magnitudes are <=1e-6; raw disagreements remain in the report. This accounts
for numerical silence as the AFC first enables its delayed output: a startup
symbol can have opposite real signs at approximately 1e-28 and 1e-25 while
both complex magnitudes are approximately 1e-12. Exact frame content and checksum
accounting remain mandatory. These bounds accommodate Java/GNU floating-point
implementation differences; they do not assert universal bit identity.

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

The v1.5 comparison was also run with a one-frame clean fixture at 250 kS/s
and four-candidate fixtures at 1 and 2 MS/s, corrupting candidate index 1.
GNU Radio and Java recovered the same one clean frame and the same three valid
frames plus one rejection in each mixed fixture. All nine stage sample counts
matched exactly; maximum pre-symbol relative RMS error was 2.68e-7 and maximum
symbol relative RMS error was 4.87e-5. There were zero significant symbol sign
disagreements and one numerical-silence sign disagreement per fixture. Java
payload output was byte-identical with source blocks of 4096 and 137 samples
and a repeated 4096-sample run. Reproduce these smaller fixtures with:

```powershell
python decoder_elektrol_ggak/src/test/python/qualify_reference.py --output decoder_elektrol_ggak/target/reference-v15-smoke --rates 250000 --frames 1
python decoder_elektrol_ggak/src/test/python/qualify_reference.py --output decoder_elektrol_ggak/target/reference-v15-mixed --rates 1000000 2000000 --frames 4 --corrupt-frame-indices 1
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference-v15-smoke/rate_250000 250000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference-v15-mixed/rate_1000000 1000000
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakQualification decoder_elektrol_ggak/target/reference-v15-mixed/rate_2000000 2000000
```

## CS16 mission-recording acceptance

`ElektroLGgakRecordingQualification` replays a complete raw CS16 recording
through the production `Cs16SampleSource` and `JSatDecodeRuntime`, with pacing
and repeat disabled. It independently validates each delivered frame's checksum
and annotations, hashes the ordered payload stream, checks decoder counters and
requires at least **272 valid frames**, at most **13 rejected candidates** and
strictly more than **2x realtime**. The private recording is not a build fixture;
ordinary module tests do not depend on its availability.

After the Maven build, run from the repository root:

```powershell
$cp = 'decoder_elektrol_ggak/target/test-classes;decoder_elektrol_ggak/target/classes;decoder_elektrol_ggak/target/lib/*'
$recording = 'C:/Users/dario/temp/Recordings/GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
java -cp $cp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRecordingQualification $recording decoder_elektrol_ggak/target/recording-qualification.json
$packagedCp = 'decoder_elektrol_ggak/target/test-classes;decoder_elektrol_ggak/target/decoder-elektrol-ggak-0.1.0-SNAPSHOT.jar;decoder_elektrol_ggak/target/lib/*'
java -cp $packagedCp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRecordingQualification $recording decoder_elektrol_ggak/target/recording-qualification-137.json --block-samples=137
```

Optional switches are `--block-samples=N` (default 4096), `--sample-rate=N`
(default 1000000), and `--dcblocker=true|false` (default false). The report path
is optional and otherwise defaults to `target/ggak-recording-qualification.json`.
It records input size and SHA-256, source samples, frame counters, output SHA-256,
timings, environment and whether all acceptance thresholds passed. The harness
fails after writing its report if acceptance fails.

The measured recording contains **415862508 bytes**, or **103965627 complex
samples / 103.965627 seconds** at 1 MS/s. Its SHA-256 is
`effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529`.
With 4096-sample source blocks and DC removal bypassed, Windows 11 amd64,
Java runtime `23.0.1+11-39` produced:

| Measurement | Result | Required |
| --- | --- | --- |
| Checksum-valid frames | 273 | >=272 |
| Rejected candidates | 11 | <=13 |
| Total candidates | 284 | good + bad |
| Processing time | 19.621456600 s | <51.9828135 s |
| Signal seconds / processing seconds | 5.298568252x | >2x |

The ordered 220-byte payload output SHA-256 was
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217`.
A second full replay with 137-sample source blocks and the packaged decoder JAR
produced the same 273 valid frames, 11 rejections and ordered payload hash,
confirming payload independence from these source partitions. It took
**26.922644300 seconds**, or **3.861642484x realtime**, and passed the same
acceptance thresholds.

Timing includes source startup, file I/O and CS16 conversion, sample-block
delivery, decoder DSP and taps, functional delivery with checksum/annotation
validation and output hashing, EOF and runtime stop. Runtime construction,
input hashing and report writing are excluded. This is a measurement on the
stated host; throughput on other hosts or input rates needs separate measurement.

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

The v1.5 isolated comparison was run against
`target/reference-v15-smoke/rate_250000/costas.cf32` using M&M `gain_mu=0.02`
in the GNU Radio probe. All **304 finite-input cases** passed across Java
partitions **1, 7, 137 and 4096** (**1216 executions**). Maximum component error
was **1.19209289551e-7**; omega and mu errors were zero. One case had a
scheduler-dependent GNU Radio output count; Java matched the explicit GNU
single-output forecast exactly. The generated report is
`target/reference-v15-smoke/rate_250000/mm_finite/java_qualification.json`.

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
