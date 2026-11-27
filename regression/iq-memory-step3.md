# IQ memory optimisation: Step 3 evidence

Baseline commit: `fb98b1698881f0bc2ae24d15c10f37457fd03d70` (Step 2).
The commit containing this report is the Step 3 candidate. Windows 11 Pro
10.0.26300 amd64; OpenJDK 25.0.2+10-69; Maven 3.10.0. Source revisions,
library hashes, commands and raw output are retained in the ignored local
`regression/iq-memory-step3-evidence/` bundle. Preserve it before cleaning.

At the time this Step 3 report was written, the Step 2 physical Pluto/PlutoPlus
test had not run. The subsequent live checks passed for the tested settings;
the [final report](iq-memory-final.md#plutoplus-release-gate-checks) records
their limits and the user's acceptance of the measured throughput tradeoff.
**G2 and G3 are CLOSED — PASS WITH AN APPROVED PERFORMANCE EXCEPTION.**

## Measured change

Step 2's warmed mission JFR recordings sampled the source worker allocating
`LeasedSampleBlock.Shared` and `IqBufferLease` on Elektro-L and sampled
jradio's Reed–Solomon code and immutable frame metadata on Hinode. JFR samples
are sparse and their allocation weights are statistical; they cannot assign the
full sampled weight to a particular object. Inspection confirmed that the
existing FIR, Costas, timing, tap and carrier scratch is already instance-owned
and reused. The single sampled jradio FEC site remains unchanged: the public
jradio interface returns fresh decode arrays, and replacing its FEC arithmetic
would require separate qualification.

Each published block previously constructed a separate `AtomicInteger` in its
shared ownership record. The shared record now contains an atomically updated
integer field instead. It still has one independent close flag per block,
nonblocking retain/rejection and one final release of its underlying pool lease.
No DSP equations, frame processing, ownership API or numeric operation order
changed.

On the same JVM and pool-publication benchmark, after warm-up, measured
thread-allocated bytes were **168 bytes/block before and 152 bytes/block
after** (500,000 blocks in each stable trial). This is an exact thread-allocation
measurement for that benchmark, not an estimate of whole-process savings.
Both runs reused one 32,768-byte buffer and ended with zero outstanding leases.

## Functional verification

- Isolated full reactor `mvn -o clean verify`: **PASS**, including JPMS/service
  discovery, source, decoder, distributor, recording and deployment tests.
- Independent `jsatdecode` and `jsatinspector` deployment-profile builds and
  packaged launcher checks: **PASS**.
- Inspector desktop acceptance: **PASS** for the committed Step 2 baseline
  and Step 3 candidate. The window, application and drop-sample smoke tests
  exercise source selection, synthetic spectrum/waterfall painting, settings,
  recording controls, cancellation, startup and orderly close. Screenshots and
  logs are in the evidence bundle. These tests do not emulate a physical radio.
- Pool ownership and race harness: **PASS** in the clean build and three
  further repetitions. Retained grandchildren stay valid after the original
  claim closes; exception, concurrent retain/close, cancellation, exhaustion
  and shutdown checks leave no outstanding leases. Existing source, writer and
  distribution tests exercise queue saturation and failure paths. Two Hinode
  decoder instances also processed the same generated signal concurrently and
  each produced the same frame count as the isolated decoder.
- API Javadoc: **PASS** with `-Xdoclint:all -Werror`.
- Physical Pluto/PlutoPlus acquisition, recording and stop/restart: **NOT RUN
  during the original Step 3 verification**. Simulator tests do not establish
  physical hardware behavior; the later live results are linked above.

The private inputs were fingerprinted before replay. The detailed audit hashes
every IQ pair, ordered block metadata, frame bytes and normalized frame
timestamps/annotations; it also compares all deterministic source and decoder
monitoring values with the locally captured Step 1 baseline. Absolute UTC
anchors, elapsed time and realtime factors are run-dependent. The packaged
runtime replay independently compares the frame files byte for byte.

| Mission | Input SHA-256 | Samples / blocks | Accepted / bad / total | Ordered frame SHA-256 |
| --- | --- | --- | --- | --- |
| Hinode | `025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0` | 45,023,232 / 10,992 | 309 / 81 / 390 | `caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472` |
| Elektro-L | `effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529` | 103,965,627 / 25,383 | 273 / 11 / 284 | `f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217` |

| Mission | IQ SHA-256 | Block metadata SHA-256 | Frame metadata SHA-256 |
| --- | --- | --- | --- |
| Hinode | `b4e037ae2e30e166f3b650a28f213f277b5d4a78f5d489eb2ecabdbd9fbd1756` | `6f2558ae807797e43858faee4ab338f2ff8c9d087546c38fe161095997519847` | `a5f962b7c314c253793b9da22dac04a31bc215891cb3707a54854533582931d4` |
| Elektro-L | `fcaea1bf7d1d1f4bedeae80c855b3739135a5882691f3023e8886bfef42a6fd6` | `8d02e027b0c6e4e9015d209f1f21c6383d8343258a587cc4b884f6db0e181d7e` | `f68805a69c9813efee2e2d5006856b77fd518a5c065e3f6340319dba0237f769` |

The two source pools peaked at 32,768 and 40,960 reserved payload bytes,
respectively, against a 134,316,032-byte cap each. Both reported zero
outstanding leases, active bytes, exhaustions and invalid releases after
runtime drain. This does not include independent rotator or Inspector pools.

Inspector CF32 and CS16 recording fixtures matched the Step 1 and Step 2 file
hashes `686784361667c128f719e1dc0529da08fb9462b1bcbd6a6d13fa9f5d3d4bd048`
and `aac6a8a57f8ff396534ac29ee38320a686acd193f51cfb9122e6b351d7a76062`.
The simulated IIOD source CF32 recording matched
`1b057834438a24836d893c4976127026f635a32fa8ffdfa54b3121c6e8ce63c4`.
All fixture pools drained to zero outstanding leases.

## Performance

Paired runs use identical inputs, `-Xms256m -Xmx512m`, JFR profile settings,
GC logging, two synthetic warmups and three measured iterations per process.
Each full-recording JVM first processes 8,388,608 warm-up samples. Three
baseline/candidate process pairs run in alternating order. The Step 1 synthetic
threshold was fixed before implementation: no more than 5% median throughput
loss per mission; a baseline spread above 5% is inconclusive.

| Synthetic mission | Step 2 process medians (Msamples/s) | Step 3 process medians | Baseline spread | Median throughput change | Result |
| --- | --- | --- | ---: | ---: | --- |
| Hinode | 1.390280, 1.391738, 1.390783 | 1.392450, 1.389818, 1.391704 | 0.105% | +0.066% | PASS |
| Elektro-L | 4.170964, 4.173155, 4.173540 | 4.179779, 4.173695, 4.169249 | 0.062% | +0.013% | PASS |

The short synthetic timings include the runtime's 250 ms completion polling.
Each process delivered identical sample/block counts and the same ordered-frame
hash. The full-recording wall-time medians were 27.877 to 28.004 seconds for
Hinode (+0.45%) and 19.824 to 19.832 seconds for Elektro-L (+0.04%). These
recording timings are observations, not a separately established threshold.

For each warmed full recording, a 10 ms sampler reads the processing thread's
`ThreadMXBean` allocated-byte counter until shortly before that thread exits.
The values therefore can miss a short tail and include other source-thread work,
including frame/FEC processing. The table reports the median sampled bytes and
the median per-process sampled bytes divided by the replay wall time. It is not
the JFR whole-process allocation rate.

| Reference mission | Step 2 source bytes | Step 3 source bytes | Median source bytes/s before → after | Sampled heap peak before → after |
| --- | ---: | ---: | ---: | ---: |
| Hinode | 7,709,064 | 7,532,768 | 276,546 → 268,993 | 50,482,856 → 50,482,856 |
| Elektro-L | 8,115,528 | 7,709,464 | 409,386 → 388,760 | 81,940,136 → 81,940,136 |

The source-thread medians decreased by 176,296 and 406,064 sampled bytes,
respectively, consistent with removing one 16-byte object per delivered block.
The heap peaks are sampled at 10 ms and may miss short peaks; both are below the
512 MiB heap cap. Median synthetic sampled heap peaks were 111,300,264 bytes on
both revisions. Pool payload peaks and independent caps are reported above.

JFR's sampled **whole-process** allocation rates include startup, warm-up,
profiling and the sampler. Their full-recording medians were 1,426,398 →
1,383,929 bytes/s for Hinode and 2,678,831 → 2,626,719 bytes/s for Elektro-L.
The synthetic whole-process median went from 7,703,196 to 7,909,587 bytes/s,
so it does not demonstrate a reduction. Each process in these comparisons
recorded one GC; no reduction in collection count is established. Median summed
GC pauses were 5.034 → 5.006 ms (Hinode), 5.344 → 5.109 ms (Elektro-L), and
5.354 → 4.737 ms (synthetic). These sparse timings do not establish a pause
improvement. JFR weights and GC-checkpoint heap values are estimates, not
exact allocation totals or true heap peaks; raw profiles remain in the bundle.

## Reproduction and rollback

Build a fresh archived copy of the candidate with Java 25 and Maven, then run:

```powershell
mvn -o clean verify
mvn -o -pl deployment -am -Pjsatdecode verify
mvn -o -pl deployment -am -Pjsatinspector verify
```

In each built workspace, run the graphical tests on an interactive Windows
desktop using the generated test dependencies. The screenshot path must be new:

```powershell
$testRoot = (Resolve-Path 'inspector/target').Path
$testPath = "$testRoot\test-classes;$testRoot\classes;$testRoot\test-dependencies\*"
java -Djava.awt.headless=false -cp $testPath eu.dariolucia.jsatdecode.inspector.InspectorWindowDesktopTest <new-screenshot-path>
java -Djava.awt.headless=false -cp $testPath eu.dariolucia.jsatdecode.inspector.InspectorApplicationDesktopTest
java -Djava.awt.headless=false -cp $testPath eu.dariolucia.jsatdecode.inspector.DropSampleTestDesktopTest
```

Compile `IqMissionMetadataReplay.java` and `IqMissionRuntimeReplay.java` against
the frozen packaged candidate modules, run both missions and compare with the
Step 1 bundle using `CompareIqStep2Evidence.ps1`. The Step 2 report documents the
exact commands and fixture generation. Use `IqJfrHotspots.java` on the retained
Step 2 candidate JFRs to reproduce source-thread allocation samples.

`IqBlockAllocationBenchmark.java` is compiled once against the Step 2 API and
run with each API jar on the classpath. `RunIqStep3Benchmarks.ps1` checks all
input hashes and compiles the same replay harness against the Step 2 packaged
modules, then runs paired Step 2 and Step 3 processes. With fresh output paths:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/RunIqStep3Benchmarks.ps1 `
  -BaselineLib regression/iq-memory-step2-evidence/final-benchmark-lib `
  -CandidateLib regression/iq-memory-step3-evidence/candidate-lib `
  -EvidenceDirectory regression/iq-memory-step3-evidence `
  -SyntheticInput regression/iq-memory-step1-evidence/input.cs16 `
  -HinodeInput 'C:\Users\dario\temp\Mullberg28092026\Hinode_90secs.wav' `
  -ElektroInput 'C:\Users\dario\temp\Recordings\GGAK_1Msps_2026-11-09_19-05-52_1000000SPS_1693000000Hz.cs16'
powershell.exe -NoProfile -ExecutionPolicy Bypass -File regression/CompareIqStep3Benchmarks.ps1 `
  -EvidenceDirectory regression/iq-memory-step3-evidence
```

If the candidate fails a required gate, retain the evidence, revert the Step 3
commit independently, and rerun the last passing full suite. Do not change the
reference inputs, copy/immutability contracts or acceptance thresholds to make
a failing result pass. See the final report for the later physical-source check.
