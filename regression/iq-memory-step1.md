# IQ memory optimisation: Step 1 evidence

Baseline commit: `f7a75d8303ded19ce09ee831059b20c2d9606373` (clean checkout).
Windows 11 Pro 10.0.26300 amd64; OpenJDK 25.0.2+10-69; Maven 3.10.0.
Baseline `mvn -o clean verify`: **PASS**, in an isolated archive of that commit.

Before implementing the pool, the synthetic CS16 replay in
[`IqMemoryReplay.java`](IqMemoryReplay.java) was profiled with 2 warm-ups and 3
measured iterations per decoder, 4096 complex samples per block, no pacing or
frequency rotation. Its 2,097,152-sample generated input has SHA-256
`beb56883ee339d919630ac097e7e238f0397a31ff1d1efdf72cdf92a6f8b4c8e`.
Both missions processed 512 blocks and emitted zero frames for this synthetic
noise; this profile is not a substitute for real mission frame qualification.
With `-Xms256m -Xmx512m`, JFR `settings=profile`, GC logging, and a 10 ms heap
sampler, measured median throughput was 1.643931 Msamples/s for Hinode and
4.066589 Msamples/s for Elektro-L. The sampled peak heap was 165,826,216 bytes;
two young collections paused for 4.623 and 4.706 ms. The sampler may miss a
shorter-lived peak. An earlier profile produced a Hinode median of 1.392467
Msamples/s, demonstrating significant between-process variability.

**Performance qualification threshold established before implementation:**
under matched, warmed, alternating baseline/candidate runs, median throughput
for each mission must not fall by more than 5%; use at least three process runs
per revision, and treat a baseline spread above 5% as inconclusive rather than
claiming a pass. Step 3 must additionally demonstrate a reduction in measured
IQ-processing allocation rate against paired baseline profiles; sampled JFR
allocations are estimates, not exact byte counts. This synthetic workload does
not establish a threshold for real recordings or other machines.

Baseline artifacts are in the ignored local directory
`regression/iq-memory-step1-evidence/`; they survive a Maven clean. The isolated
build workspaces and additional intermediate files are under
`target/iq-memory-step1-20261126/`.
`baseline-final.jfr` SHA-256:
`d69a119c9a005fb4ee53b7bc772d96f3ea047b90a2f571ac52cbfb41990cbc16`;
`baseline-final-gc.log` SHA-256:
`434c2e8a8ac64db06e4f23096e98ebbfc107cf60504c7f82f88f2c519f783656`.

## Source-level allocation and lifetime inventory

The following inventory is based on the checked-out source, not just the
architecture documents. These are **remaining** production-path sites; Step 1
does not migrate them.

| Path | Allocation, copies and retention |
| --- | --- |
| CF32/WAV file source | [`AbstractFileSampleSource`](../source_file/src/main/java/eu/dariolucia/jsatdecode/source/file/AbstractFileSampleSource.java) allocates one byte staging buffer and one `float[]` per source start, reuses them, and constructs a `ComplexSampleBlock` for every delivered block. Its constructor copies the valid prefix. The default is 4096 complex samples; configured sizes are 1..1,048,576, with a shorter final block. |
| CS16 file source | [`Cs16SampleSource`](../source_file/src/main/java/eu/dariolucia/jsatdecode/source/cs16/Cs16SampleSource.java) likewise reuses byte and float staging buffers, converts CS16 to float, then copies each valid block into `ComplexSampleBlock`. |
| FMComms2 | [`Fmcomms2SampleSource`](../source_fmcomms2/src/main/java/eu/dariolucia/jsatdecode/source/fmcomms2/Fmcomms2SampleSource.java) reuses a raw byte buffer and float staging array, converts into blocks, constructs a copying immutable block, offers it to source recording **before** calling the sink, and defaults to 65,536 acquisition samples and 4096 samples per delivery block. |
| Runtime and decoder | [`SourceFrequencyRotator`](../core/src/main/java/eu/dariolucia/jsatdecode/core/runtime/SourceFrequencyRotator.java) forwards the same block when the offset is zero. For a nonzero offset it uses growing scratch storage and constructs another copying immutable block. Both mission decoders use indexed I/Q reads. Hinode also stages RF/baseband/symbol tap values in three fixed 8192-float arrays; Elektro-L stages each tap in its own reusable array. Both copy these observations into their respective rings. DSP and tap storage remain untouched. |
| FMComms2 recorder | [`Cf32Recorder`](../source_fmcomms2/src/main/java/eu/dariolucia/jsatdecode/source/fmcomms2/Cf32Recorder.java) asynchronously queues references to immutable source blocks, then serializes them on its writer. Its default queue budget is 64 MiB, subject to configured slots and block size. Queue overflow and write failures follow existing recording policy. |
| Inspector | [`InspectorReceiver`](../inspector/src/main/java/eu/dariolucia/jsatdecode/inspector/InspectorReceiver.java) synchronously passes factor-one blocks through; [`InspectorDecimator`](../inspector/src/main/java/eu/dariolucia/jsatdecode/inspector/InspectorDecimator.java) copies into reusable scratch and constructs another copying block when decimating. [`IqRecorder`](../inspector/src/main/java/eu/dariolucia/jsatdecode/inspector/IqRecorder.java) retains references in a bounded asynchronous queue, default 64 MiB, and serializes CF32 or CS16 on its writer. [`InspectorAnalysis`](../inspector/src/main/java/eu/dariolucia/jsatdecode/inspector/InspectorAnalysis.java) synchronously copies a bounded suffix into scratch and the observation ring; the FFT worker observes ring snapshots independently. |
| Legacy boundary/taps | [`ComplexSampleBlock`](../api/src/main/java/eu/dariolucia/jsatdecode/api/source/ComplexSampleBlock.java) copies on construction and `iq()` returns another copy. [`ComplexFloatRingBuffer`](../api/src/main/java/eu/dariolucia/jsatdecode/core/buffers/ComplexFloatRingBuffer.java) copies a bounded snapshot into a new array; `ComplexSnapshot` then uses the legacy copying boundary for independent observation data. No mission decoder or Inspector production call to `ComplexSampleBlock.iq()` was found in this audit. |

The pool's `toComplexSampleBlock` boundary intentionally constructs independent
legacy storage, including the legacy constructor's defensive copy. Other copies
remain where transformations, independent snapshots, or existing contracts
require them. At Step 1 no asynchronous consumer received a pooled lease; see
[Step 2 evidence](iq-memory-step2.md) for the subsequent migration.

## Commands and raw evidence

Raw artifacts and their SHA-256 manifest are local and ignored by Git; preserve
them separately before removing ignored files. To avoid altering pre-existing
artifacts, the builds below ran in separate workspaces extracted from
`git archive HEAD`, with the candidate's changed files copied into the candidate
workspace. Measurements were made from the uncommitted candidate source, whose
individual file hashes are in `SHA256SUMS.txt`. The local
`candidate-revision.txt` records its resulting commit when one exists; otherwise
it states `UNCOMMITTED`.

Build environment and commands in the respective workspace:

```powershell
$env:JAVA_HOME = 'C:\Users\dario\.jdks\openjdk-25.0.2'
$env:PATH = "$env:JAVA_HOME\bin;C:\Windows\System32;C:\Windows;$env:PATH"
& 'C:\Program Files\Maven\bin\mvn.cmd' -o clean verify
& 'C:\Program Files\Maven\bin\mvn.cmd' -o -pl api -am verify
```

To reproduce the profile, build the relevant archived revision first, use a
fresh evidence directory, copy `regression/IqMemoryReplay.java` there, and run
the following from that directory. Set `$base` to the built workspace. The
generator uses `CREATE_NEW` and will not overwrite a recording.

```powershell
$javaHome = 'C:\Users\dario\.jdks\openjdk-25.0.2'
$base = (Resolve-Path -LiteralPath 'baseline').Path
$cp = ".;$base\decoder_hinode\target\classes;$base\decoder_elektrol_ggak\target\classes;$base\decoder_hinode\target\lib\*"
& "$javaHome\bin\javac.exe" -cp $cp -d '.' 'IqMemoryReplay.java'
& "$javaHome\bin\java.exe" -cp $cp IqMemoryReplay generate input.cs16
& "$javaHome\bin\java.exe" -Xms256m -Xmx512m '-XX:StartFlightRecording=filename=baseline-final.jfr,settings=profile,dumponexit=true' '-Xlog:gc*:file=baseline-final-gc.log:time,level,tags' -cp $cp IqMemoryReplay replay input.cs16
& "$javaHome\bin\jfr.exe" summary baseline-final.jfr
& "$javaHome\bin\jfr.exe" print --events jdk.ObjectAllocationSample --json baseline-final.jfr
```

The additional paired processes used the same input and flags, in this order:

```powershell
foreach ($run in @('baseline-2','candidate-2','baseline-3','candidate-3')) {
    $kind = $run.Split('-')[0]
    $base = (Resolve-Path -LiteralPath $kind).Path
    $cp = ".;$base\decoder_hinode\target\classes;$base\decoder_elektrol_ggak\target\classes;$base\decoder_hinode\target\lib\*"
    $jfr = "-XX:StartFlightRecording=filename=$run.jfr,settings=profile,dumponexit=true"
    $gc = "-Xlog:gc*:file=$run-gc.log:time,level,tags"
    & "$javaHome\bin\java.exe" -Xms256m -Xmx512m $jfr $gc -cp $cp IqMemoryReplay replay input.cs16 *> "$run-replay.log"
    if ($LASTEXITCODE -ne 0) { throw "Replay failed: $run" }
}
```

The baseline recording has 467 allocation samples with total sample weight
239,449,520 bytes over approximately 10 seconds (approximately 23.9 MB/s).
This is a **sampled estimate for the entire process**, including JVM startup,
not an exact IQ-only allocation rate; 170,977,992 weighted bytes were attributed
to `float[]` samples. The candidate's comparable recording has 477 samples,
238,918,664 weighted bytes, a 165,975,960-byte 10 ms sampled heap peak, and two
young GC pauses of 5.166 and 3.926 ms. These observations do not establish an
allocation reduction. JFR retains sampled paths and environment information;
review it before sharing raw recordings.

Three alternating baseline/candidate process pairs used the same options and
input. The following values are the median of the three measured iterations in
each process, in Msamples/s:

| Mission | Baseline processes | Candidate processes | Baseline spread | Median change |
| --- | --- | --- | ---: | ---: |
| Hinode | 1.643931, 1.375104, 1.638653 | 1.625613, 1.657735, 1.378254 | 16.41% | -0.80% |
| Elektro-L | 4.066589, 4.075523, 4.082176 | 4.056674, 4.052598, 4.068207 | 0.38% | -0.46% |

Spread is `(maximum - minimum) / median` of the three baseline process
medians. The Hinode synthetic performance comparison is **INCONCLUSIVE**
because its baseline spread exceeded the predefined 5%; the Elektro-L
synthetic comparison falls within the 5% threshold. Both produced the same
sample/block counts and empty ordered-frame hash in every process. These
results are not qualification on actual mission recordings. Raw paired logs,
JFR/GC files, and `paired-throughput.json` are in the local evidence directory.
The short replay calls `JSatDecodeRuntime.awaitCompletion`, which polls every
250 ms; the observed roughly 250 ms separation between the two Hinode timing
clusters is consistent with polling quantization, although it does not prove
that all variation came from that cause.

## Reference mission replay

The private recordings were subsequently located and their SHA-256 hashes
verified before any replay. These reference-capture runs were performed after
the pool implementation, using the previously built clean baseline archive and
the isolated candidate; the earlier synthetic JFR was captured before source
implementation began.

| Mission | Input | Input SHA-256 |
| --- | --- | --- |
| Hinode | `C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav` | `025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0` |
| Elektro-L | `C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16` | `effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529` |

The existing [`HinodeBaselineReplay.java`](HinodeBaselineReplay.java) and
[`ElektroBaselineReplay.java`](ElektroBaselineReplay.java) ran against the
respective packaged application's JARs on the JPMS module path. Hinode uses
the synchronous source-to-decoder path; Elektro-L uses the runtime with zero
frequency offset. Each harness checks input size and modification time across
the replay. Baseline and candidate ordered frame files were compared byte for
byte against each other and against the preserved Step 9 frame files.

| Mission | Samples / blocks | Accepted / bad / candidates | Ordered frame bytes / SHA-256 |
| --- | --- | --- | --- |
| Hinode | 45,023,232 / 10,992 | 309 / 81 / 390 | 275,628 / `caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472` |
| Elektro-L | 103,965,627 / 25,383 | 273 / 11 / 284 | 60,060 / `f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217` |

All source and decoder monitoring values matched between baseline and
candidate, excluding only the explicitly nondeterministic `source.elapsed_s`
and `source.realtime_factor`. The comparison and the complete raw snapshots
are in `mission-replay/mission-comparison.json` and the corresponding logs.
Both runs retained the expected frame locks, counts, and source EOF/accounting.

A separate instrumented synchronous source-to-decoder replay used
[`IqMissionMetadataReplay.java`](IqMissionMetadataReplay.java) to hash every
delivered IQ pair and record every block's count, absolute sample index, sample
rate and timestamp, as well as every frame's timestamp and sorted, typed
annotations. Timestamps and the `rx_time_ns` annotation were normalized by
subtracting the first source-block UTC timestamp; all other values were
compared unchanged. Baseline and candidate metadata files were also compared
byte for byte.

| Mission | IQ SHA-256 | Block metadata SHA-256 | Frame metadata SHA-256 |
| --- | --- | --- | --- |
| Hinode | `b4e037ae2e30e166f3b650a28f213f277b5d4a78f5d489eb2ecabdbd9fbd1756` | `6f2558ae807797e43858faee4ab338f2ff8c9d087546c38fe161095997519847` | `a5f962b7c314c253793b9da22dac04a31bc215891cb3707a54854533582931d4` |
| Elektro-L | `fcaea1bf7d1d1f4bedeae80c855b3739135a5882691f3023e8886bfef42a6fd6` | `8d02e027b0c6e4e9015d209f1f21c6383d8343258a587cc4b884f6db0e181d7e` | `f68805a69c9813efee2e2d5006856b77fd518a5c065e3f6340319dba0237f769` |

The raw files and `detail-comparison.json` are under the ignored evidence
directory. This extra auditing path reads IQ and metadata for comparison and
is not used as a throughput measurement.

### Warmed reference performance and profiling

Each of three alternating baseline/candidate process pairs warmed the source
and decoder in the same JVM on the first 8,388,608 samples, then measured a
full replay. JFR and GC logging were enabled for both. Every full replay's
frame file was compared byte for byte with the reference, and all deterministic
source/decoder monitoring values matched. Wall times include the existing
runtime completion polling for Elektro-L. The raw values and verification
results are in `mission-replay/warm-benchmark-results.json`.

| Mission | Baseline wall seconds | Candidate wall seconds | Baseline spread | Median wall-time change |
| --- | --- | --- | ---: | ---: |
| Hinode | 28.955501, 28.730257, 28.332507 | 28.647586, 28.796763, 29.037476 | 2.17% | +0.23% |
| Elektro-L | 19.084918, 19.478973, 19.837813 | 19.545318, 19.643255, 19.116352 | 3.87% | +0.34% |

The median changes are smaller than the observed baseline spread. These are
observations on this host and these recordings, not a new threshold for real
recordings or a claim that the short synthetic comparison passed.

The initial packaged reference replay JFRs (without the additional warmup pass)
have the following whole-process summaries. Allocation values are sampled JFR
weights divided by the observed event span, not exact IQ-only rates. The heap
column is the maximum seen at a GC checkpoint, **not** a true peak; the 10 ms
sampled synthetic peak is documented above. Pauses are summed GC pauses.

| Replay | Event span | Estimated allocation | GC count / pauses | Maximum heap at GC |
| --- | ---: | ---: | ---: | ---: |
| Baseline Hinode | 30.199 s | 13,083,182 bytes/s | 3 / 12.918 ms | 161,846,488 bytes |
| Candidate Hinode | 30.055 s | 13,146,245 bytes/s | 3 / 13.510 ms | 162,027,208 bytes |
| Baseline Elektro-L | 22.236 s | 39,289,897 bytes/s | 6 / 26.261 ms | 178,000,200 bytes |
| Candidate Elektro-L | 22.214 s | 39,315,275 bytes/s | 6 / 24.547 ms | 178,115,432 bytes |

[`IqJfrSummary.java`](IqJfrSummary.java) generated
`mission-replay/mission-jfr-summary.txt` from the raw JFRs; the event span
includes startup and input hashing. No reduction is claimed for Step 1, which
does not use the new pool in the production path.

```powershell
$javaHome = 'C:\Users\dario\.jdks\openjdk-25.0.2'
$bundle = (Resolve-Path 'regression\iq-memory-step1-evidence\mission-replay').Path
& "$javaHome\bin\javac.exe" -d "$bundle\classes" regression\IqJfrSummary.java
$recordings = @(Get-ChildItem -LiteralPath $bundle -File -Filter '*.jfr' | Sort-Object Name | ForEach-Object { $_.FullName })
& "$javaHome\bin\java.exe" -cp "$bundle\classes" IqJfrSummary $recordings > "$bundle\mission-jfr-summary.txt"
```

The unchanged baseline and candidate full builds also passed the same
recording and distribution byte fixtures. The distribution test checks
SHA-256 `896f994510837121bbc40baa0cbff4fe2658623758715373d11348aa05c84f81`
for SatDecode, `02a9eb1b7bc6fa3fc6b023d0025fe73681ba328bd74e7e4db4b0bf658f63635b`
for frame recording, and `5aa661598390cc38067c8dfb5249554715ffa436914f168f7cfd085c5c6369f8`
for JTDP. Inspector's CF32/CS16 recorder tests compare their actual bytes
against independently constructed expected samples, and the FMComms2
recording tests compare bytes and ordering across partial writes and failures.

### Reproducing packaged replays

Set `$kind` to `baseline` or `candidate`; each workspace must first have been
built and its JSatDecode archive extracted by the deployment checks. The same
module path, heap flags and harness classes were used for the reference and
detailed captures. Choose fresh output filenames; the harnesses reject existing
output files. Compile the harnesses from the corresponding archived workspace:
later steps can extend their copies for newer optional APIs. For the original
JFR capture, add the indicated JFR and GC flags.

```powershell
$javaHome = 'C:\Users\dario\.jdks\openjdk-25.0.2'
$bundle = (Resolve-Path 'regression\iq-memory-step1-evidence\mission-replay').Path
$classes = Join-Path $bundle 'classes'
$kind = 'baseline'
$workspace = (Resolve-Path "target\iq-memory-step1-20261126\$kind").Path
$root = Get-ChildItem "target\iq-memory-step1-20261126\$kind\deployment\target\deployment-smoke-test" -Directory | Where-Object { $_.Name -like 'jsatdecoder package*' }
$lib = Join-Path $root.FullName 'jsatdecode-0.1.0-SNAPSHOT\lib'
$modules = 'eu.dariolucia.jsatdecode.decoder.hinode,eu.dariolucia.jsatdecode.decoder.elektrol.ggak,eu.dariolucia.jsatdecode.source.file,eu.dariolucia.jsatdecode.core'
& "$javaHome\bin\javac.exe" --module-path $lib --add-modules $modules -d $classes "$workspace\regression\HinodeBaselineReplay.java" "$workspace\regression\ElektroBaselineReplay.java" "$workspace\regression\IqMissionMetadataReplay.java"
& "$javaHome\bin\javac.exe" --module-path $lib --add-modules $modules -cp $classes -d $classes "$workspace\regression\IqMissionWarmBenchmark.java"
$hinode = 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav'
$ggak = 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
$jfr = "-XX:StartFlightRecording=filename=$bundle\example.jfr,settings=profile,dumponexit=true"
$gc = "-Xlog:gc*:file=$bundle\example-gc.log:time,level,tags"
& "$javaHome\bin\java.exe" -Xms256m -Xmx512m $jfr $gc --module-path $lib --add-modules $modules -cp $classes HinodeBaselineReplay $hinode "$bundle\example-hinode-frames.bin"
& "$javaHome\bin\java.exe" -Xms256m -Xmx512m --module-path $lib --add-modules $modules -cp $classes IqMissionMetadataReplay elektro $ggak "$bundle\example-elektro-detail"
$jfr = "-XX:StartFlightRecording=filename=$bundle\example-warm.jfr,settings=profile,dumponexit=true"
$gc = "-Xlog:gc*:file=$bundle\example-warm-gc.log:time,level,tags"
& "$javaHome\bin\java.exe" -Xms256m -Xmx512m $jfr $gc --module-path $lib --add-modules $modules -cp $classes IqMissionWarmBenchmark hinode $hinode "$bundle\example-warm-hinode-frames.bin"
```

Use distinct JFR/GC paths for every process. The actual reference captures used
the original harness corresponding to each mission; the detailed audit used
`IqMissionMetadataReplay` for each mission and revision. Warmed benchmark pairs
were run in baseline/candidate order, three pairs per mission, with each process
warming on the first 8,388,608 input samples before its full-recording replay.

The API documentation for the two new classes was also checked with:

```powershell
& "$javaHome\bin\javadoc.exe" -quiet -Xdoclint:all -Werror -d '..\candidate-iq-javadoc' -sourcepath 'api\src\main\java' 'api\src\main\java\eu\dariolucia\jsatdecode\api\source\IqBufferPool.java' 'api\src\main\java\eu\dariolucia\jsatdecode\api\source\IqBufferLease.java'
```

That direct JDK check passed. The optional offline Maven shorthand
`mvn -o -pl api javadoc:javadoc` failed because its plugin prefix/version was
not resolved in the local cache; it was not used as evidence of a pass.

## Candidate verification and gate status

- Baseline and candidate isolated `mvn -o clean verify`: **PASS**; the reactor
  includes the existing Inspector, recording, distribution, decoder and
  deployment regression suites. Candidate `mvn -o -pl api -am verify`: **PASS**.
- Ownership, size classes, budget accounting, eviction, invalid ranges, exact
  windows, legacy copies, duplicate close, shutdown/cancellation, publication
  and retain/close races, independent active buffers, fixed-seed concurrent
  retention/acquisition, and a real allocation failure in a 32 MiB JVM are
  covered by `IqBufferPoolTest` (113 explicit checks plus the repeated inner
  stress assertions, in the final full and API builds). Each completed case
  checks zero active leases.
- All 328 pre-existing production class files built from the baseline and
  candidate are byte-identical; the opt-in pool adds new API classes only.
  `jdeps --multi-release 25 --print-module-deps` on the candidate API JAR
  reports only `java.base`. The module descriptor is unchanged.
- Packaged baseline/candidate replay of both verified private inputs, ordered
  nonempty frame bytes, deterministic monitoring, IQ values, block/frame
  metadata, and warmed real-recording performance comparisons: **PASS**, with
  nondeterministic UTC anchors and timing handled as stated above. The earlier
  short synthetic Hinode timing comparison remains **INCONCLUSIVE**.
- Desktop acceptance and live hardware: **NOT RUN** for this change; the
  automated simulated-source and Inspector tests are not hardware evidence.

The G1 functional, ownership, build and real-recording performance checks pass
for the tested candidate source. The separate Step 1 commit and its local
revision manifest must be recorded to complete the evidence bundle. This is not
release qualification; Steps 2 and 3 have not been implemented or validated.
