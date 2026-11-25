# Final Java 25 modular migration verification

**Status: Steps 7, 8 and 9 gates passed with no observed functional regression.**
Live hardware and external remote peers were unavailable; coverage limits are
listed below. SoapySDR and native dependencies were not introduced.

The immediate baseline is revision
`98acf7698d5b636d3ff511af1b24e09fe658c6e9`. The original comparison is the
[Step 1 Java 21/25 baseline](step-1-baseline.md), revision
`f84667fe48adb5abcb90fd2e7f3d49c7f5863e2f`. Checks use Windows 11 amd64,
Intel Core i7-9750H, OpenJDK 25.0.2 and Maven 3.9.11 with the offline cache.
Isolated baseline/final workspaces preserve the checkout's earlier evidence.

## Implementation and dependency audit

- [Step 7](step-7-generic-inspector.md) was already implemented in Step 5:
  factory discovery, generic property controls, runtime updates, file-source
  reception/recording and FMComms2 preference/default compatibility. No further
  source or API change was required.
- [Step 8](step-8-deployments.md) renames the packaging project to `deployment`
  and supplies independent `jsatdecode` / `jsatinspector` profiles and archives.
  Inspector contains only five project JARs; mission decoders, distribution
  implementation modules and their external dependencies are confined to
  JSatDecode. Ordinary builds create both.
- Every source/distribution provider's POM has API as its only project
  dependency. JPMS descriptors and the resolved packaged graphs confirm the
  same rule, including transitively. API has no project or third-party runtime
  dependencies; `jdeps` reports only `java.base`.
- Across the entire migration, decoder production-source diffs from the Step 1
  revision contain only the three permitted `module-info.java` changes. Decoder
  algorithms, structures, contracts, protocol handling and example properties
  are unchanged. This task changes no production Java code: all 328 production
  class files in the ten Java modules match the immediate baseline byte-for-byte.

## Automated and desktop checks

Both the pre-change and final `mvn -o clean verify` runs passed. The final
reactor includes 138 JUnit tests, 25,722 file-source checks, 197 simulated
FMComms2 checks, 1,101 source-recording checks, 599 ZMQ reference-client checks,
and the existing API, runtime, decoder and numerical suites. Inspector includes
8,279 receiver, 74 recorder, 549 dropped-sample and 708 analysis checks, settings
persistence/boundaries and seven generic-source JUnit tests.

Both selected deployment profiles also package and launch independently from
fresh workspaces. Package checks validate module graphs, service discovery,
isolated API/source/distribution consumers, actual mission DSP, Windows/POSIX
scripts, quoted paths, caller working directory and error exit codes. Both
applications launch with Java 25. No split package or module cycle was found.

The packaged Inspector passes window/control and actual-application desktop
tests: source selection/drafts, live/static fields, FFT/recording controls,
painting, settings restoration/persistence, disconnected startup and worker
cleanup. Its real JPMS window opens/closes with its module-owned icon and
unchanged 1280 by 850 default size. The packaged decoder monitoring window also
opens/closes at the prior 1936 by 1048 size. Inspector preferences are isolated
from the user's settings.

Unchanged Elektro-L WAV/CS16 and Hinode WAV example properties run headless
against generated short inputs with only `source.file` overridden. Configuration
files are hash-checked before/after. ZMQ, JTDP and frame recording retain their
fixed baseline byte fixtures, framing/order and lifecycle/interoperability
checks; local independent clients exercise both transports.

## Unchanged mission recording replay

The private inputs are the exact files hashed in Step 1. Final captures use
only the **packaged JSatDecode JARs on the module path**. Ordered output bytes
compare equal to the immediate baseline captures and the preserved Step 4
captures, which match the Step 1 output hashes.

| Measurement | Step 1, Java 25 | Immediate baseline | Final deployment |
| --- | ---: | ---: | ---: |
| Hinode samples / accepted frames | 45,023,232 / 309 | 45,023,232 / 309 | 45,023,232 / 309 |
| Hinode bad / total candidates | 81 / 390 | 81 / 390 | 81 / 390 |
| Hinode replay seconds / real-time factor | 27.792 / 3.240x | 29.148 / 3.089x | 28.049 / 3.210x |
| Elektro-L samples / accepted frames | 103,965,627 / 273 | 103,965,627 / 273 | 103,965,627 / 273 |
| Elektro-L bad / total candidates | 11 / 284 | 11 / 284 | 11 / 284 |
| Elektro-L replay seconds / real-time factor | 18.176 / 5.720x | 19.082 / 5.448x | 19.183 / 5.420x |

Hinode input SHA-256:
`025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0`.
Its 275,628 ordered output bytes have SHA-256
`caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472`.
All deterministic decoder monitoring values match, including three `LOCKED`
states, four duplicates, 81 RS rejects, carrier offset and carrier/symbol SNR.

Elektro-L input SHA-256:
`effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529`.
Its 60,060 ordered output bytes have SHA-256
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217`.
All decoder monitoring values match, including post-stop lock states and frame
accounting. Both sources reach EOF exactly once with identical sample/block
counts and selection/position metadata.

Wall time, `source.elapsed_s` and `source.realtime_factor` are the excluded
nondeterministic measurements; they are reported separately rather than used
for exact equality. No other source or decoder monitoring value is excluded.
These are single-run timings on the same host, without concurrent build/replay
workloads, and both final replays exceed 2x real time. They support practical
equivalence on these inputs, not a cross-machine performance guarantee.

## Coverage and evidence

The default IIOD endpoint `192.168.2.1:30431` was not reachable within two
seconds, so live FMComms2 acquisition was not performed. Local IIOD peers cover
acquisition, cancellation, runtime updates, failures and recording. External
remote SatDecode/JTDP peers were not supplied; protocol coverage uses local
independent clients and fixed reference bytes. No new regression or exception
to the migration constraints was identified.

Source reports and build/run helpers remain in `regression/`. Ignored evidence
is under `target/step8-*` and `target/step9-*`, including:

- `step9-baseline-build.log` and `step9-final-build.log` for clean reactor runs;
- the two Step 8 profile build logs with Maven runtime dependency trees;
- `step9-*-hinode.log`, `step9-*-elektro.log` and ordered frame captures;
- dependency/bytecode audits and `step9-recording-comparison.json`;
- `step9-*-desktop.log`, the Inspector screenshot and legacy-example logs.

Reproduce recording captures with the existing `HinodeBaselineReplay.java` and
`ElektroBaselineReplay.java` helpers, the unchanged private input files and an
unused output filename. The build workspaces and logs may be removed by a
future clean; preserve evidence separately when needed. See the
[deployment guide](../deployment/README.md) for build and launch commands.
