# IQ memory optimisation: Step 2 evidence

Baseline commit: `70bfd6a68ae6d796b3a57281e40847adc6fb0ff3` (Step 1).
Windows 11 Pro 10.0.26300 amd64; OpenJDK 25.0.2+10-69; Maven 3.10.0.
The commit containing this report is the Step 2 candidate. Its exact commit ID,
source hashes, commands and raw logs are recorded in the ignored local bundle
`regression/iq-memory-step2-evidence/`; preserve it before cleaning ignored files.

The migration is opt-in at the public API boundary. File and FMComms2 sources
publish read-only pooled IQ; the runtime forwards zero-offset blocks directly
to leased decoders and uses a distinct pooled output when translating frequency.
Inspector borrows samples for analysis and its recorder retains an independent
claim before enqueueing. The source-owned FMComms2 recorder does the same before
runtime translation. Legacy sources, decoders and sinks keep their independent
`ComplexSampleBlock` behavior. The mission DSP arithmetic is unchanged.

## Verification

- Isolated `mvn -o clean verify`: **PASS**. This includes the existing module,
  decoder, source, Inspector, ZMQ/JTDP and deployment tests, plus the new
  leased ownership, file-format, rotator, recorder and simulated IIOD cases.
  The final source-recording/translation test was added afterward and passed
  in a separate `-pl integration_tests -am verify` run. Additional API
  exception-path checks passed with `-pl api -am verify`; production code was
  unchanged between these runs.
- Independent `jsatdecode` and `jsatinspector` profile builds and launch tests:
  **PASS**, including the Windows batch and POSIX shell launch checks.
- Leased detailed and packaged-runtime replay of both private recordings:
  **PASS**. IQ, ordered frames, block/frame metadata and all deterministic
  source/decoder monitoring values match the Step 1 baseline exactly. Both
  runtime replays finish with zero outstanding pool leases.
- Physical Pluto/PlutoPlus live acquisition, recording and stop/restart:
  **NOT RUN — RELEASE GATE OPEN**. No compatible hardware was available in
  this session. On the later Step 2 resumption, the committed Step 2 baseline
  was rebuilt in isolation and all three Inspector desktop smoke tests passed,
  including the rendered spectrum/waterfall, source switching, settings,
  recording controls, cancellation, application startup and shutdown. The
  screenshot and logs are retained in the Step 3 local evidence bundle.

**G2: RELEASE GATE OPEN.** The software checks below passed. The production
release gate remains open until the physical-radio smoke test required by the
plan is completed; this report does not certify physical hardware.

The new tests compare CF32, WAV, RF64 and CS16 IQ bits, partial blocks, empty
ranges, repeat and normalized timestamps against the legacy path. Rotator tests
compare both paths bit-for-bit over split blocks, rate changes, positive and
negative offsets, and retained input/output blocks. Inspector tests compare
CF32/CS16 recording bytes and decimated IQ, and exercise stalled writes, queue
overflow, rejection, disk failures, shutdown and rapid stop races. Simulated
IIOD tests cover conversion, live settings, disconnect and new-source recovery,
recording failures and drain. A nonzero-offset runtime test verifies raw IQ in
the source-owned file and translated IQ at the decoder. Tests assert that
retained samples survive source shutdown and pool counts reach zero after the
final owner closes.
The existing distribution compatibility test also passed with the Step 1
SatDecode, frame-file and JTDP fixture hashes documented in
[Step 1 evidence](iq-memory-step1.md).

The following actual recording files were produced separately with the Step 1
baseline and the Step 2 leased path. Each pair matched byte for byte; the
candidate pools had zero outstanding leases after writer drain. The Inspector
fixture includes signed zeros, NaNs, infinities and conversion boundaries. The
FMComms2 fixture uses five samples from the simulated IIOD server.

| Recording fixture | Bytes | Baseline and candidate SHA-256 |
| --- | ---: | --- |
| Inspector CF32 | 88 | `686784361667c128f719e1dc0529da08fb9462b1bcbd6a6d13fa9f5d3d4bd048` |
| Inspector CS16 | 44 | `aac6a8a57f8ff396534ac29ee38320a686acd193f51cfb9122e6b351d7a76062` |
| FMComms2 CF32 | 40 | `1b057834438a24836d893c4976127026f635a32fa8ffdfa54b3121c6e8ce63c4` |

## Reference recordings

| Mission | Input SHA-256 | Samples / blocks | Accepted / bad / total | Ordered frame SHA-256 |
| --- | --- | --- | --- | --- |
| Hinode | `025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0` | 45,023,232 / 10,992 | 309 / 81 / 390 | `caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472` |
| Elektro-L | `effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529` | 103,965,627 / 25,383 | 273 / 11 / 284 | `f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217` |

The detailed audit hashes all delivered IQ pairs, ordered block metadata and
normalized frame timestamps/annotations. Absolute UTC start anchors and elapsed
wall time are run-dependent. The runtime replay separately captures ordered
frame payloads and verifies successful leased delivery and zero outstanding
pool leases. Comparison uses the retained Step 1 baseline files rather than
historical frame counts alone.

| Mission | IQ SHA-256 | Block metadata SHA-256 | Frame metadata SHA-256 |
| --- | --- | --- | --- |
| Hinode | `b4e037ae2e30e166f3b650a28f213f277b5d4a78f5d489eb2ecabdbd9fbd1756` | `6f2558ae807797e43858faee4ab338f2ff8c9d087546c38fe161095997519847` | `a5f962b7c314c253793b9da22dac04a31bc215891cb3707a54854533582931d4` |
| Elektro-L | `fcaea1bf7d1d1f4bedeae80c855b3739135a5882691f3023e8886bfef42a6fd6` | `8d02e027b0c6e4e9015d209f1f21c6383d8343258a587cc4b884f6db0e181d7e` | `f68805a69c9813efee2e2d5006856b77fd518a5c065e3f6340319dba0237f769` |

## Performance and allocation

Matched runs use two synthetic warmups and three measured iterations per
process, with three alternating process pairs. The real-recording comparison
additionally warms each JVM on the first 8,388,608 samples. All runs use
`-Xms256m -Xmx512m`, JFR profile settings and GC logging. The predeclared
synthetic threshold is a maximum 5% median throughput loss, with a baseline
spread above 5% marked inconclusive.

| Synthetic mission | Baseline process medians (Msamples/s) | Candidate process medians | Baseline spread | Median change |
| --- | --- | --- | ---: | ---: |
| Hinode | 1.391194, 1.391608, 1.391085 | 1.391725, 1.390854, 1.392532 | 0.038% | +0.038% |
| Elektro-L | 4.162959, 4.167455, 4.171646 | 4.167124, 4.171947, 4.179749 | 0.209% | +0.108% |

Both synthetic comparisons meet the predeclared threshold. The 250 ms runtime
completion polling affects these short timings; the complete per-iteration
results are in the bundle.

| Reference mission | Baseline wall seconds | Candidate wall seconds | Baseline spread in these runs | Median wall-time change |
| --- | --- | --- | ---: | ---: |
| Hinode | 26.990464, 27.062630, 26.862845 | 27.560906, 27.544178, 27.389880 | 0.740% | +2.05% |
| Elektro-L | 19.332348, 19.330144, 19.327497 | 19.833555, 19.830647, 19.821489 | 0.025% | +2.59% |

Candidate wall time was higher in each of these pairs. The Step 1 reference
measurements had already documented wider baseline spreads of 2.17% for
Hinode and 3.87% for Elektro-L; these changes are within those previously
observed spreads. The current runs have narrower baseline ranges, so the
consistent difference remains visible rather than being described as an
improvement. Runtime completion polling affects the Elektro-L wall times. These
observations do not establish a new threshold for real recordings. Every
reference benchmark frame file matched the Step 1 baseline hash.

JFR allocation weights are sampled estimates, not exact allocated-byte counts.
For the synthetic processes, the median estimated whole-process allocation rate
fell from 23,279,978 to 7,661,340 bytes/s; median sampled peak heap fell from
150,261,400 to 111,300,264 bytes. Each baseline had two GCs and each candidate
had one; median summed GC pause fell from 9.523 to 5.049 ms. The identified
`ComplexSampleBlock` source-copy stack appeared in 303–307 baseline float-array
allocation samples and zero candidate samples. Source pool counters separately
show one reused array for the 10,992 Hinode blocks and two size-class arrays
for the 25,383 Elektro-L blocks. No source copy is performed along the leased
zero-offset handoff. Sampling can miss allocations, and the heap sampler can
miss short peaks.

For the warmed full recordings, the median JFR estimated whole-process
allocation rates were approximately 13.64 MB/s baseline and 1.16 MB/s candidate
for Hinode, and 41.24 MB/s baseline and 2.19 MB/s candidate for Elektro-L. Each
baseline had respectively four and seven collections; none of these candidate
runs recorded a GC. Consequently their JFR GC checkpoints do not provide a
candidate peak heap estimate.

## Commands

Build in an isolated copy of the candidate to avoid altering existing ignored
build artifacts:

```powershell
$env:JAVA_HOME = 'C:\Users\dario\.jdks\openjdk-25.0.2'
$env:PATH = "$env:JAVA_HOME\bin;C:\Windows\System32;C:\Windows;$env:PATH"
& 'C:\Program Files\Maven\bin\mvn.cmd' -o clean verify
& 'C:\Program Files\Maven\bin\mvn.cmd' -o -pl deployment -am -Pjsatdecode verify
& 'C:\Program Files\Maven\bin\mvn.cmd' -o -pl deployment -am -Pjsatinspector verify
& 'C:\Program Files\Maven\bin\mvn.cmd' -o -pl integration_tests -am verify
& 'C:\Program Files\Maven\bin\mvn.cmd' -o -pl api -am verify
```

After building clean Step 1 and Step 2 workspaces, the following uses their
packaged module paths and validates the three input hashes before replaying:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/RunIqStep2Benchmarks.ps1 `
  -BaselineWorkspace target/iq-memory-step1-20261126/committed `
  -CandidateWorkspace target/iq-memory-step2-candidate-clean `
  -EvidenceDirectory regression/iq-memory-step2-evidence `
  -SyntheticInput regression/iq-memory-step1-evidence/input.cs16 `
  -HinodeInput 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav' `
  -ElektroInput 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
```

The harnesses use new output paths and refuse to overwrite replay artifacts.
`IqJfrSummary.java` summarizes JFR allocation weights and GC pauses;
`IqJfrAllocationPaths.java` identifies the source-copy allocation stacks.
`CompareIqStep2Evidence.ps1` compares both mission replays against the Step 1
baseline, excluding only elapsed wall time and realtime factor from monitoring.

The final detailed and runtime comparisons used the frozen candidate package
libraries copied to the evidence bundle. With a fresh bundle/output prefix:

```powershell
$bundle = (Resolve-Path 'regression/iq-memory-step2-evidence').Path
$lib = Join-Path $bundle 'final-benchmark-lib'
$classes = Join-Path $bundle 'final-audit-classes'
$modules = 'eu.dariolucia.jsatdecode.decoder.hinode,eu.dariolucia.jsatdecode.decoder.elektrol.ggak,eu.dariolucia.jsatdecode.source.file,eu.dariolucia.jsatdecode.core'
$javaBin = 'C:\Users\dario\.jdks\openjdk-25.0.2\bin'
New-Item -ItemType Directory -Path $classes | Out-Null
& "$javaBin\javac.exe" --module-path $lib --add-modules $modules -d $classes regression/IqMissionMetadataReplay.java regression/IqMissionRuntimeReplay.java
$missions = @(
  @('hinode', 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav'),
  @('elektro', 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16')
)
foreach ($mission in $missions) {
  $prefix = Join-Path $bundle "final-$($mission[0])-detail"
  & "$javaBin\java.exe" -Xms256m -Xmx512m '-Djsatdecode.replay.leased=true' --module-path $lib --add-modules $modules -cp $classes IqMissionMetadataReplay $mission[0] $mission[1] $prefix
  & "$javaBin\java.exe" -Xms256m -Xmx512m --module-path $lib --add-modules $modules -cp $classes IqMissionRuntimeReplay $mission[0] $mission[1] (Join-Path $bundle "runtime-$($mission[0])-frames.bin")
}
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/CompareIqStep2Evidence.ps1 `
  -Baseline regression/iq-memory-step1-evidence/mission-replay -Candidate $bundle
```

Detailed run logs were redirected to `final-<mission>-detail.log`, and runtime
logs to `runtime-<mission>.log`. `final-mission-comparison.json` records the
exact matches. The frozen candidate library hashes are in
`final-benchmark-lib-sha256.txt`.

Recording evidence was produced with the same compiled harness class on each
workspace's classpath. The `leased` mode uses reflection solely so that the
class also compiles against Step 1, before the leased-block API existed:

```powershell
$baseline = (Resolve-Path 'target/iq-memory-step1-20261126/committed').Path
$candidate = (Resolve-Path 'target/iq-memory-step2-candidate-clean').Path
$recorderClasses = Join-Path $bundle 'recorder-evidence-classes'
$fmClasses = Join-Path $bundle 'fm-recording-evidence-classes'
New-Item -ItemType Directory -Path $recorderClasses, $fmClasses | Out-Null
$baselineInspector = "$baseline\inspector\target\classes;$baseline\inspector\target\test-dependencies\*"
$candidateInspector = "$candidate\inspector\target\classes;$candidate\inspector\target\test-dependencies\*"
& "$javaBin\javac.exe" -cp $baselineInspector -d $recorderClasses regression/IqRecorderEvidence.java
& "$javaBin\java.exe" -cp "$recorderClasses;$baselineInspector" eu.dariolucia.jsatdecode.inspector.IqRecorderEvidence (Join-Path $bundle 'recording-baseline') legacy
& "$javaBin\java.exe" -cp "$recorderClasses;$candidateInspector" eu.dariolucia.jsatdecode.inspector.IqRecorderEvidence (Join-Path $bundle 'recording-candidate') leased
$baselineFm = "$baseline\integration_tests\target\test-classes;$baseline\core\target\classes;$baseline\core\target\test-dependencies\*"
$candidateFm = "$candidate\integration_tests\target\test-classes;$candidate\core\target\classes;$candidate\core\target\test-dependencies\*"
& "$javaBin\javac.exe" -cp $baselineFm -d $fmClasses regression/FmcommsRecordingEvidence.java
& "$javaBin\java.exe" -cp "$fmClasses;$baselineFm" eu.dariolucia.jsatdecode.source.fmcomms2.FmcommsRecordingEvidence (Join-Path $bundle 'recording-fm-baseline') legacy
& "$javaBin\java.exe" -cp "$fmClasses;$candidateFm" eu.dariolucia.jsatdecode.source.fmcomms2.FmcommsRecordingEvidence (Join-Path $bundle 'recording-fm-candidate') leased
```

JFR analysis and the public API Javadoc check used:

```powershell
$analysisClasses = Join-Path $bundle 'classes'
& "$javaBin\javac.exe" -d $analysisClasses regression/IqJfrSummary.java regression/IqJfrAllocationPaths.java
$jfrFiles = @(Get-ChildItem -LiteralPath $bundle -File -Filter 'final-*.jfr' | Sort-Object Name | ForEach-Object { $_.FullName })
& "$javaBin\java.exe" -cp $analysisClasses IqJfrSummary $jfrFiles
& "$javaBin\java.exe" -cp $analysisClasses IqJfrAllocationPaths $jfrFiles
$apiPath = 'api/src/main/java/eu/dariolucia/jsatdecode/api'
& "$javaBin\javadoc.exe" -quiet -Xdoclint:all -Werror -d target/iq-memory-step2-javadoc-final -sourcepath api/src/main/java `
  "$apiPath/source/IqBufferPool.java" "$apiPath/source/IqBufferLease.java" `
  "$apiPath/source/IqReadAccess.java" "$apiPath/source/IqWriteAccess.java" `
  "$apiPath/source/IqSampleView.java" "$apiPath/source/LeasedSampleBlock.java" `
  "$apiPath/source/LeasedSampleSink.java" "$apiPath/source/LeasedSampleSource.java" `
  "$apiPath/decoder/LeasedDecoder.java"
```

Javadoc passed with `-Xdoclint:all -Werror`. The JFR summaries and stack-specific
sample counts and weights are retained in `final-jfr-summary.txt` and
`final-allocation-paths.txt`.
