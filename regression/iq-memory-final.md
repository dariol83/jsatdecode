# Final IQ memory optimisation verification

**Findings:** Both reference recordings match the original output exactly, and
the predeclared synthetic throughput threshold passes. Memory allocation, heap
peaks and GC counts fell substantially. Strictly unchanged performance cannot
be claimed: the full Hinode recording was consistently slower, including
without profiling, where median throughput fell by 2.20%. Elektro-L also had
a small increase in median source elapsed time, with greater run variation.
The required physical-radio release check remains open.

Test date: 2026-11-27. The unmodified pre-optimisation baseline was commit
`f7a75d8303ded19ce09ee831059b20c2d9606373`; the final candidate was commit
`6db5d3a5dd32714d00eab8a7b556144944941ac3`. Both were built from separate Git
archives in this workspace. Windows 11 Pro 10.0.26300 amd64, Intel Core i7-9750H
(6 cores/12 logical processors), OpenJDK 25.0.2+10-69 and Maven 3.10.0.

The baseline archive contains the original production source. The baseline
benchmark and metadata harnesses were taken separately from Step 1 commit
`70bfd6a68ae6d796b3a57281e40847adc6fb0ff3`; they select the legacy input path
against the original packaged modules. The final candidate uses its own
corresponding harnesses and the leased path. All three input hashes were
verified before measurement.

Raw build logs, packaged-library hashes, replay outputs, JFR/GC recordings,
desktop screenshots and helper scripts are in the ignored local directory
`target/iq-memory-final-20261127/evidence/`. Preserve that directory before
cleaning build artifacts. The private recordings are not included in the repo.

## Build and functional checks

- `mvn -o clean verify` passed for both archived revisions. The baseline had
  138 Surefire tests and the candidate had 139, with zero failures, errors or
  skips; the reactor also runs additional executable test harnesses.
- Independent `jsatdecode` and `jsatinspector` deployment profiles and their
  packaged Windows/POSIX launcher checks passed for the candidate. The three
  Inspector desktop smoke tests passed, including synthetic spectrum/waterfall
  rendering, source/settings controls, application startup/close and
  cancellation.
- The API Javadoc passed `-Xdoclint:all -Werror`. The pool test passed three
  extra repetitions, each with 151 checks and three fixed concurrency seeds.
- Inspector CF32 and CS16 recording fixtures and the simulated FMComms2 CF32
  fixture matched the previously captured baseline bytes. Their SHA-256 values
  were respectively `686784361667c128f719e1dc0529da08fb9462b1bcbd6a6d13fa9f5d3d4bd048`,
  `aac6a8a57f8ff396534ac29ee38320a686acd193f51cfb9122e6b351d7a76062` and
  `1b057834438a24836d893c4976127026f635a32fa8ffdfa54b3121c6e8ce63c4`.
  All fixture pools drained to zero outstanding leases.

### Reference recording equivalence

The freshly rebuilt original revision and final candidate produced identical
ordered frame payloads, IQ values, block metadata, normalized frame timestamps
and annotations, and deterministic source/decoder monitoring values. The
candidate packaged-runtime frame files also matched the freshly generated
original frame files byte for byte. A second comparison against the earlier
locally captured Step 1 baseline passed. Absolute UTC anchors, wall elapsed
time and realtime factors are run-dependent and were excluded from the
deterministic monitoring comparison.

| Mission | Input SHA-256 | Samples / blocks | Accepted / bad / total | Ordered frame SHA-256 |
| --- | --- | --- | --- | --- |
| Hinode | `025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0` | 45,023,232 / 10,992 | 309 / 81 / 390 | `caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472` |
| Elektro-L GGAK | `effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529` | 103,965,627 / 25,383 | 273 / 11 / 284 | `f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217` |

| Mission | IQ SHA-256 | Block metadata SHA-256 | Frame metadata SHA-256 |
| --- | --- | --- | --- |
| Hinode | `b4e037ae2e30e166f3b650a28f213f277b5d4a78f5d489eb2ecabdbd9fbd1756` | `6f2558ae807797e43858faee4ab338f2ff8c9d087546c38fe161095997519847` | `a5f962b7c314c253793b9da22dac04a31bc215891cb3707a54854533582931d4` |
| Elektro-L | `fcaea1bf7d1d1f4bedeae80c855b3739135a5882691f3023e8886bfef42a6fd6` | `8d02e027b0c6e4e9015d209f1f21c6383d8343258a587cc4b884f6db0e181d7e` | `f68805a69c9813efee2e2d5006856b77fd518a5c065e3f6340319dba0237f769` |

The final source pools reserved at most 32,768 bytes for Hinode and 40,960
bytes for Elektro-L, each against a 134,316,032-byte payload cap. After runtime
drain both reported zero outstanding leases, active bytes, exhaustions and
invalid releases. Separate rotator and Inspector pools are not included in
those figures.

## Performance and allocation

### Method

The paired baseline and candidate processes used identical recordings,
`-Xms256m -Xmx512m`, JFR `profile` settings and GC logging. The synthetic
workload used two warm-up and three measured iterations per mission per
process. Each full-recording process first warmed the source and decoder with
8,388,608 samples. Three baseline/candidate process pairs ran in alternating
order. Frame files from **all 12** warmed recording runs matched the original
ordered-frame hash. Input SHA-256 hashes were unchanged after the runs.

The throughput acceptance criterion was declared in
[Step 1](iq-memory-step1.md): at least three matched warmed process runs, no
more than a 5% decline in median synthetic throughput per mission, and a
baseline spread above 5% is inconclusive. It was not declared as a threshold
for the full recordings. The short synthetic and Elektro-L runtime timings
include 250 ms completion polling; Hinode's full-recording timing waits for
source completion directly.

### Synthetic throughput

| Mission | Original process medians (Msamples/s) | Final process medians (Msamples/s) | Original spread | Median throughput change | Result |
| --- | --- | --- | ---: | ---: | --- |
| Hinode | 1.392130, 1.392350, 1.392254 | 1.392856, 1.392762, 1.393459 | 0.016% | +0.043% | PASS |
| Elektro-L | 4.172261, 4.172579, 4.166355 | 4.172376, 4.172442, 4.165943 | 0.149% | +0.003% | PASS |

Every synthetic iteration processed 2,097,152 samples in 512 blocks. This
noise-only fixture produced zero frames, so the real-recording checks above are
the evidence for actual frame equivalence.

### Full recordings with profiling

| Mission | Original wall seconds, three runs | Final wall seconds, three runs | Median wall-time change | Median throughput before → after |
| --- | --- | --- | ---: | ---: |
| Hinode | 27.032439, 26.938148, 27.019175 | 27.428527, 27.525921, 27.572656 | +1.88% | 1.666344 → 1.635667 Msamples/s (-1.84%) |
| Elektro-L | 19.567531, 19.582656, 19.329240 | 19.824658, 19.572542, 19.573605 | +0.03% | 5.313170 → 5.311522 Msamples/s (-0.03%) |

All three profiled Hinode candidate times were higher than their corresponding
baseline times. The baseline spread in this set was 0.35%; a prior independent
baseline set documented 2.17%. This is a small measured slowdown, not proof of
strictly unchanged real-recording performance. The Elektro-L baseline spread
was 1.30%, and its 250 ms polling affects these short timing differences. No new
acceptance threshold for the real recordings has been invented after the fact.

### Allocation, heap and GC

A 10 ms management-thread sampler read `ThreadMXBean` allocation counters on
the source processing thread. These counts include decoding and source work,
and may miss the short interval between the final sample and thread exit. The
table reports median observed bytes during the full replay and median heap
peaks sampled every 10 ms across each warmed process. Heap samples can miss
short peaks and do not include native memory.

| Mission | Source-thread allocated bytes, original → final | Reduction | Sampled peak Java heap, original → final | Reduction |
| --- | ---: | ---: | ---: | ---: |
| Hinode | 365,274,840 → 7,533,056 | 97.94% | 161,344,824 → 50,482,856 bytes | 68.71% |
| Elektro-L | 834,301,848 → 7,733,288 | 99.07% | 193,089,192 → 81,940,136 bytes | 57.56% |

Median observed source-thread allocation rates were approximately
13,519,095 → 273,683 bytes/s for Hinode and 42,615,240 → 394,009 bytes/s for
Elektro-L, calculated per replay from the sampled counters and wall times.
These are not whole-process totals. The median sampled synthetic heap peak was
150,356,536 → 111,300,264 bytes. All sampled heap peaks were below the
512 MiB JVM maximum; pool payload caps are separate from the total heap.

JFR allocation weights below are statistical **whole-process** estimates over
the recorded event span, including JVM startup, warm-up and the sampler. GC
counts and summed pause times also cover the complete process. They are not
exact IQ-only allocation counts or proof of application latency.

| Workload | Median JFR allocation rate, original → final | GC events per process, original → final | Median summed GC pause, original → final |
| --- | ---: | ---: | ---: |
| Synthetic (both missions) | 23,242,781 → 7,737,711 bytes/s | 2 → 1 | 9.175 → 5.127 ms |
| Hinode recording | 13,921,327 → 1,409,790 bytes/s | 4 → 1 | 16.922 → 5.315 ms |
| Elektro-L recording | 41,126,468 → 2,634,557 bytes/s | 7 → 1 | 28.209 → 4.880 ms |

The identified source-side `ComplexSampleBlock` float-array copy appeared in
732–765 allocation samples per original Hinode process and 1,746–1,758 per
original Elektro-L process; it appeared in zero final-process samples. The
synthetic originals had 300–304 such samples and the final processes had zero.
Sampling can miss allocations; the count is supporting evidence rather than an
exact number of copies. Source pool diagnostics independently show one reused
array for Hinode and two size-class arrays for Elektro-L, with no outstanding
leases after drain.

### Unprofiled follow-up

To check whether profiling caused the observed timing difference, another three
warmed baseline/candidate pairs were run for each full recording without JFR,
GC logging or the heap sampler. The second pair ran candidate first; the other
two ran baseline first. All 12 additional frame files matched the original
ordered-frame hash, and the input hashes remained unchanged.

| Mission | Original wall seconds, three runs | Final wall seconds, three runs | Median wall-time change | Median throughput before → after |
| --- | --- | --- | ---: | ---: |
| Hinode | 26.573959, 26.602429, 26.425845 | 27.086948, 27.324933, 27.171311 | +2.25% | 1.694261 → 1.657014 Msamples/s (-2.20%) |
| Elektro-L | 19.076537, 18.823180, 18.825037 | 19.082746, 19.332928, 19.083671 | +1.37% | 5.522732 → 5.447884 Msamples/s (-1.36%) |

Every unprofiled Hinode pair was slower on the candidate, including the pair
run in reversed order. Baseline spread was 0.66%. The Hinode difference is
therefore visible in both profiled and unprofiled measurements on this machine;
the precise cause was not established. This is a source-to-decoder replay,
not a measurement of decoder CPU time in isolation.

Elektro-L's end-to-end wall time includes the runtime's 250 ms completion
polling, which is of the same order as the median difference. Its source's own
`source.elapsed_s` measures acquisition and synchronous decoding through EOF
without that runtime polling interval. The following medians are from the same
recorded runs, not additional measurements:

| Workload | Source elapsed, original → final | Median elapsed change | Median throughput change |
| --- | ---: | ---: | ---: |
| Profiled Hinode | 27.018828 → 27.525636 s | +1.88% | -1.84% |
| Profiled Elektro-L | 19.321353 → 19.548655 s | +1.18% | -1.16% |
| Unprofiled Hinode | 26.573691 → 27.171057 s | +2.25% | -2.20% |
| Unprofiled Elektro-L | 18.811068 → 18.980443 s | +0.90% | -0.89% |

For unprofiled Elektro-L, the baseline source-elapsed spread was 0.69% and one
candidate run was faster than its paired baseline. The small difference should
therefore not be overstated. These source elapsed times include input I/O and
decoding; they are not decoder-only CPU measurements. The predeclared 5%
criterion applies to the synthetic workload, so it does not certify strict
equivalence for these full recordings.

## Commands and artifacts

The baseline and candidate were each built in their own archived directory:

```powershell
mvn -o clean verify
mvn -o -pl deployment -am -Pjsatdecode verify
mvn -o -pl deployment -am -Pjsatinspector verify
```

The independent profile commands were run on the candidate. With fresh output
paths, the full replays and comparisons used:

```powershell
$bundle = 'target/iq-memory-final-20261127/evidence'
$auditArgs = @('-BaselineLib', "$bundle/baseline-lib", '-CandidateLib', "$bundle/candidate-lib",
  '-BaselineHarness', 'target/iq-memory-final-20261127/harness/regression',
  '-CandidateHarness', 'target/iq-memory-final-20261127/candidate/regression',
  '-EvidenceDirectory', $bundle)
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$bundle/RunFinalAudit.ps1" @auditArgs `
  -Mission hinode -InputFile 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$bundle/RunFinalAudit.ps1" @auditArgs `
  -Mission elektro -InputFile 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/CompareIqStep2Evidence.ps1 `
  -Baseline $bundle -Candidate $bundle
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/CompareIqStep2Evidence.ps1 `
  -Baseline regression/iq-memory-step1-evidence/mission-replay -Candidate $bundle
```

The recording fixtures used `IqRecorderEvidence.java` and
`FmcommsRecordingEvidence.java`; the [Step 2 report](iq-memory-step2.md)
documents their compilation and invocation. The three desktop tests were
`InspectorWindowDesktopTest`, `InspectorApplicationDesktopTest` and
`DropSampleTestDesktopTest` from the candidate's test classpath.

The exact profiled invocation, using fresh output paths, was:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File target/iq-memory-final-20261127/evidence/RunFinalBenchmarks.ps1 `
  -BaselineLib target/iq-memory-final-20261127/evidence/baseline-lib `
  -CandidateLib target/iq-memory-final-20261127/evidence/candidate-lib `
  -BaselineHarness target/iq-memory-final-20261127/harness/regression `
  -CandidateHarness target/iq-memory-final-20261127/candidate/regression `
  -WrapperSource regression/IqMissionWarmHeapBenchmark.java `
  -EvidenceDirectory target/iq-memory-final-20261127/evidence `
  -SyntheticInput regression/iq-memory-step1-evidence/input.cs16 `
  -HinodeInput 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav' `
  -ElektroInput 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/CompareIqStep3Benchmarks.ps1 `
  -EvidenceDirectory target/iq-memory-final-20261127/evidence
```

The unprofiled follow-up was run after the profiled pairs, using the same frozen
libraries and compiled harnesses:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File target/iq-memory-final-20261127/evidence/RunNoJfrCheck.ps1 `
  -EvidenceDirectory target/iq-memory-final-20261127/evidence `
  -HinodeInput 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav' `
  -ElektroInput 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
```

`IqJfrSummary.java` and `IqJfrAllocationPaths.java` generated the retained
`jfr-summary.txt` and `allocation-paths.txt`. The benchmark and audit helpers
refuse existing output paths. Preserve their scripts and the full evidence
directory before cleaning `target`.

## Limitations

Physical Pluto/PlutoPlus acquisition, recording and stop/restart were not
tested in this run. The Plug and Play check found no matching device, the
example address `192.168.3.1` was unreachable, and no verified IIOD URI was
available. The simulated IIOD and desktop tests do not validate physical radio
behavior. The plan's G2 and G3 production release gates remain open pending the
required live-hardware check.
