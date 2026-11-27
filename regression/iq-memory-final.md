# Final IQ memory optimisation verification

**Findings:** Both reference recordings match the original output exactly, and
the predeclared synthetic throughput threshold passes. Memory allocation, heap
peaks and GC counts fell substantially. Strictly unchanged performance cannot
be claimed: the full Hinode recording was consistently slower, including
without profiling, where median throughput fell by 2.20%. Elektro-L also had
a small increase in median source elapsed time, with greater run variation.
The physical-radio acquisition, recording and stop/restart check subsequently
passed, including sustained runs on both mission configurations. The user has
accepted the observed real-recording throughput tradeoff in exchange for the
memory reduction. **G2 and G3 are closed with an approved performance
exception**; see the live qualification and gate assessment below.

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

## PlutoPlus release-gate checks

On 2026-11-27, a PlutoPlus was reachable at `ip:192.168.3.1` on IIOD port
30431. The live acquisition, source-owned CF32 recording, intentional stop and
fresh-source restart checks **passed** for both the Step 2 build and the final
build. The Step 2 packaged libraries identify commit
`fb98b1698881f0bc2ae24d15c10f37457fd03d70`; the final libraries identify
`6db5d3a5dd32714d00eab8a7b556144944941ac3`. The current commit at the time of
the checks, `65fd503ada39db934272a36fecb2c97b8cd4b4e0`, differs from that final
build only by the addition of this report. These checks used OpenJDK 25.0.2
with `-Xms256m -Xmx512m` on the same Windows host documented above. All 19
packaged-library hashes for each tested build match their retained manifests.

The source was wired through `JSatDecodeRuntime` to the actual mission decoder.
The test observes the leased source stream before runtime translation and
compares its independently computed little-endian CF32 SHA-256 with the source
recording. Every block's rate, sample index and sample-clock timestamp are
checked, as are source, decoder, recording and pool accounting. Hinode uses
zero translation; Elektro-L uses a +250 kHz runtime rotation at 1 MS/s. Each
translated sample is compared numerically against the expected quarter-turn
sequence; signed zero is treated as equal. The existing software rotator tests
provide the separate bit-exact baseline comparison. A new source/runtime is
created for the second acquisition because these components are one-shot.

| Build and mission | Runs | Samples / blocks per run | Delivery rate during final sustained runs | CF32 bytes per run |
| --- | ---: | ---: | ---: | ---: |
| Step 2 Hinode | 2 x 4 s | 2,002,944 / 489 | — | 16,023,552 |
| Step 2 Elektro-L | 2 x 4 s | 4,001,792 / 977 | — | 32,014,336 |
| Final Hinode | 2 x 30 s | 15,003,648 / 3,663 | 501,511 and 501,394 samples/s | 120,029,184 |
| Final Elektro-L | 2 x 30 s | 30,003,200 / 7,325 | 1,000,195 and 1,000,467 samples/s | 240,025,600 |

Delivery rates above are calculated from the first to last observed delivery
block, excluding the first block's samples. They include the test observer and
decoder work, and are not estimates of device-clock accuracy. Each recording
contained exactly the samples accepted by the source recorder and delivered to
the observer. The four final recording hashes, also checked by an independent
file hash, are:

| Final recording | SHA-256 |
| --- | --- |
| Hinode run 1 | `17339a8ef54b8a6aa8bd0d1a08914fbca99954b0c1a72bdee617ef9b9830fbf8` |
| Hinode run 2 | `f7d1de521a09b59680caf66441972c2a87e7121e978206a0b6ccb770146f4df6` |
| Elektro-L run 1 | `1a0622e48fe291899ee3d38bc4e671a66e62e4c5f8dca0bf3c5bd5819354c03b` |
| Elektro-L run 2 | `68046df753985bfba4aa9a6a1cd21b6b9273e2ab845061654ebeb3b9e8cedb13` |

Across these runs, source errors, reported overruns, recorder errors, pool
exhaustions, invalid releases and outstanding leases were zero. After final
drain, the source and rotator pools had no active leases; the rotator pool was
closed with no retained bytes. The final source pool peaked at 294,912 bytes
against a 268,566,528-byte cap; the translated path's separate rotator pool
peaked at 32,768 bytes against a 268,435,456-byte cap. The largest observed
recorder queue high-water mark was 294,912 bytes against a 67,108,864-byte
queue budget.

Hinode used LO 2,256,220,000 Hz, 4 MS/s PHY, FPGA decimation 8, 500 kS/s
output, 4 MHz bandwidth and manual gain 32 dB. Elektro-L used LO 1,693,250,000
Hz, 8 MS/s PHY, FPGA decimation 8, 1 MS/s output, 1.5 MHz bandwidth, manual
gain 40 dB and the +250 kHz runtime translation. RX1 was selected. The
repository's Elektro-L example requests 4 MS/s PHY with decimation 4; this
particular firmware advertised only 4 MS/s and 500 kS/s ADC rates at that PHY
rate, so startup correctly rejected the requested 1 MS/s ADC rate. The failed
attempt is retained in `step2-elektro.log`. The 8 MS/s / 8 setting delivered
the same decoder input rate and passed; support for other decimation settings
is device/firmware dependent. The original pre-optimisation build was also
tested on this device with the example's 4 MS/s / 4 settings and rejected them
with the same advertised-rate error; see `original-example-rate-probe.log` and
`PlutoExampleRateProbe.java` in the evidence directory. Thus this configuration
failure was observed before and after the memory changes. The device was then
reconfigured and successfully restarted using 8 MS/s / 8; that follow-up is in
`final-elektro-post-probe.log`.

No valid satellite frames or signal lock were observed during these live runs.
Therefore they qualify the bounded acquisition/recording/restart path, not
on-air reception or live frame correctness. Frame equivalence is established by
the reference-recording comparisons above. `source.reported_overruns=0` means
no IIOD overrun was reported; the source does not measure silent FPGA or
kernel FIFO loss. A known input signal or device-specific loss instrumentation
would be needed to validate that separately.

**Gate assessment: G2 and G3 CLOSED — PASS WITH AN APPROVED PERFORMANCE
EXCEPTION.** On 2026-11-27 the user explicitly instructed us to close the
release performance gate and accepted the measured throughput in exchange for
contained memory use. This decision applies to the tested candidate and
workloads; it does not establish an acceptance threshold for future changes.

The physical smoke-test criterion of G2 and the physical source-path smoke test
in G3 pass for the settings and device tested. The predeclared synthetic
performance criterion also passes, and the reference frame bytes and accounting
remain identical. Step 2's earlier profiled Hinode wall-time increase was
2.05%, within the 2.17% baseline range observed in an earlier run. The later
final-build unprofiled Hinode replay showed a 2.20% throughput decrease in
every pair, with 0.66% baseline spread in that set. Its cause remains unknown.
The final build processed the 500 kS/s reference at approximately 1.66 MS/s,
or 3.31 times real time, on the tested host.

The original plan requires no G2 throughput regression beyond documented noise
and no material G3 worsening. The user accepts this measured tradeoff as an
explicit exception to the G2 performance requirement and as acceptable for G3.
The original 5% threshold remains unchanged and applies only to the synthetic
workload; no real-recording threshold is invented after the fact. The measured
slowdown is not a changed frame output or a proven decoder-only CPU regression.

| Gate evidence | Result |
| --- | --- |
| G2 build, formats, rotator, Inspector recording, simulated IIOD recovery, replay and lifecycle checks | PASS in the earlier software qualification |
| G2 PlutoPlus acquisition, recording and stop/restart | PASS for the tested configurations |
| G2 real-recording throughput versus documented noise | WAIVED by explicit user acceptance of the observed tradeoff; cause remains unknown |
| G3 clean builds, deterministic mission replay, desktop checks, stress, docs and allocation reduction | PASS in the earlier software qualification |
| G3 physical source-path smoke test | PASS for the tested configurations; on-air frame reception and silent hardware loss remain unverified |
| G3 real-recording materiality | PASS with user acceptance of the observed tradeoff; no new threshold is set |

The earlier software evidence remains applicable because the production code in
the tested final libraries is unchanged at the current commit. No full Maven
reactor rebuild was repeated during the live-radio follow-up.

The reproducible opt-in helper is
[`PlutoLiveGateEvidence.java`](PlutoLiveGateEvidence.java), SHA-256
`381bdb2389941b206e12453235cdad6962342b69538219fa99006f679040367c`.
It uses a test-only source observer and reflectively invokes the runtime
constructor used for frequency translation; it does not change the production
runtime. With fresh output directories, the commands are:

```powershell
$bundle = 'target/iq-memory-pluto-20261127'
$java = 'C:/Users/dario/.jdks/openjdk-25.0.2/bin'
$step2 = 'regression/iq-memory-step2-evidence/final-benchmark-lib/*'
$final = 'target/iq-memory-final-20261127/evidence/candidate-lib/*'
New-Item -ItemType Directory "$bundle/classes"
& "$java/javac.exe" -cp $step2 -d "$bundle/classes" regression/PlutoLiveGateEvidence.java
& "$java/java.exe" -Xms256m -Xmx512m -cp "$bundle/classes;$step2" PlutoLiveGateEvidence ip:192.168.3.1 hinode "$bundle/step2-hinode-checked" 4
& "$java/java.exe" -Xms256m -Xmx512m -cp "$bundle/classes;$step2" PlutoLiveGateEvidence ip:192.168.3.1 elektro "$bundle/step2-elektro-checked" 4
& "$java/java.exe" -Xms256m -Xmx512m -cp "$bundle/classes;$final" PlutoLiveGateEvidence ip:192.168.3.1 hinode "$bundle/final-hinode-sustained" 30
& "$java/java.exe" -Xms256m -Xmx512m -cp "$bundle/classes;$final" PlutoLiveGateEvidence ip:192.168.3.1 elektro "$bundle/final-elektro-sustained" 30
```

Use a new bundle path on rerun: the helper refuses to reuse its output
directory. The local ignored `target/iq-memory-pluto-20261127/` directory holds
the exact logs, failed-startup evidence and CF32 files; preserve it before
cleaning `target`.

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

The original full software audit ran before the radio was connected; the later
physical-radio results and their limits are recorded above. The live runs did
not receive a known satellite signal, and the IIOD source does not expose a
complete hardware FIFO-loss counter. The real-recording throughput difference
remains unexplained and is accepted by the user as documented above.
