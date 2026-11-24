# Java 25 migration: Step 1 regression record

**Status: Java 21 baseline and Java 25 clean build passed.** Live FMComms2
acquisition and external remote protocol peers remain unverified as described
below. No decoder implementation or example configuration changed.

## Environment and builds

- Source baseline: commit `f84667fe48adb5abcb90fd2e7f3d49c7f5863e2f`, with the
  test-only fixes described below and all production code unchanged.
- Windows 11 amd64, Intel Core i7-9750H, Maven 3.9.11.
- Java 21: JetBrains Runtime 21.0.9+1-b1163.94; Java 25: OpenJDK 25.0.2+10-69.
- Builds used isolated copies to preserve the existing, ignored `target/`
  recordings and reference artifacts in the original checkout. The Maven
  cache was used offline; a temporary settings file exposed the cached
  repository ID without changing project configuration.

| Run | Result |
| --- | --- |
| Unmodified Java 21 `mvn clean verify` | Failed in the Elektro-L example assertion. Core and decoder-base tests passed. |
| Unmodified Java 25 `mvn -Dmaven.compiler.release=25 clean verify` | Failed in a ZMQ client test reading status snapshots before both channels had published their latest counters. |
| Java 25 clean verify with the core ZMQ test waiting for both snapshots | Reached the same Elektro-L assertion and failed. |
| Java 21 `mvn clean verify` with the test-owned Elektro-L configuration and core ZMQ test adjustment | Passed in the isolated baseline copy. |
| Migrated Java 25 `mvn clean verify` with the same test fixes | Passed in an isolated copy, with no compiler-release override. |

The Elektro-L test now creates its own properties and WAV input instead of
assuming values from an example configuration. The ZMQ test retains the exact
counter assertions while waiting for asynchronous status snapshots. Its full
reference-client suite passed three additional times on each JDK. Both clean
builds ran 125 JUnit tests plus the file-source, simulated FMComms2, Inspector,
runtime, ZMQ, decoder and other self-contained checks. Both builds also tested
the assembled distribution's module graph, discovery, mission DSP and Windows
and POSIX launchers. Java 21 rejected the Java 25 distribution's class files,
confirming the new minimum runtime.

As separate compatibility checks, the existing Elektro-L WAV and CS16 example
files were run unchanged on both JDKs using short generated recordings. The only
configuration override supplied was the local `source.file` path; `--headless`
was also passed to the launcher.

The actual Inspector window opened and closed on both JDKs using separate,
temporary preferences directories. It opened at 1280 by 850 pixels in both
runs, and saved settings matched except for the isolated storage path. No radio
connection or recording was started during this desktop smoke test. The
JSatDecode synthetic monitoring window also opened and closed on both JDKs, at
1936 by 1048 pixels in each run.

## Mission recording comparison

These are one-run measurements on the same host, with no simultaneous workload
deliberately started by the test. Wall time varies by machine and load. The
recordings are private local files and are not committed to the repository.

| Measurement | Java 21 | Java 25 |
| --- | ---: | ---: |
| Hinode WAV samples / accepted frames | 45,023,232 / 309 | 45,023,232 / 309 |
| Hinode bad / total candidates | 81 / 390 | 81 / 390 |
| Hinode replay | 29.013 s, 3.104x | 27.792 s, 3.240x |
| Elektro-L CS16 samples / accepted frames | 103,965,627 / 273 | 103,965,627 / 273 |
| Elektro-L bad / total candidates | 11 / 284 | 11 / 284 |
| Elektro-L runtime replay | 18.081 s, 5.750x | 18.176 s, 5.720x |

Hinode input SHA-256:
`025baef29a0ad3e643344171589bfa36f276459e284b0a3241b0e37e10b3c5d0`.
The two 275,628-byte concatenated frame files compare byte-for-byte equal, with
SHA-256 `caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472`.
The EOF monitoring snapshots also matched, including carrier, symbol and frame
locks (`LOCKED`), four duplicates, 81 RS rejects and the carrier/SNR values.
This is a comparison against the Java 21 implementation, not an independent
receiver's expected frames.

Elektro-L input SHA-256:
`effdd3399ee0f549b9110aae34ff572b03e864bfc4614f575ad5fc7522232529`.
Both ordered output streams had SHA-256
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217`;
the existing recording harness validated frame checksum annotations and source
EOF/accounting. The input was replayed through the CS16 source and runtime at
1 MS/s, 4096 samples per block, with DC blocking and real-time pacing disabled.

Run artifacts, including the raw Hinode frame files, JSON reports and test logs,
are in the ignored directory `target/step1-regression-37dbac496dd34a52af5f4c4d5862a2c5/`.
The verified Java 25 ZIP and tar.gz distributions are copied there as well.
These are local artifacts and may be removed by a future clean build; keep the
recordings and preserve the evidence elsewhere before cleaning if needed.

## Reproducing the recording captures

Build each source revision with the desired JDK and package the artifacts. The
initial Java 25 comparison above supplied `-Dmaven.compiler.release=25`; this
override is no longer needed on the migrated project. With the same input
recordings and an unused output filename, run from the repository root on Windows:

```powershell
$hinodeCp = 'decoder_hinode/target/test-classes;decoder_hinode/target/classes;decoder_hinode/target/lib/*'
java -cp $hinodeCp regression/HinodeBaselineReplay.java path/to/Hinode_90secs.wav path/to/new-hinode-frames.bin
$ggakCp = 'decoder_elektrol_ggak/target/test-classes;decoder_elektrol_ggak/target/classes;decoder_elektrol_ggak/target/lib/*'
java -cp $ggakCp eu.dariolucia.jsatdecode.elektrol.ElektroLGgakRecordingQualification path/to/GGAK.cs16 path/to/new-ggak-report.json
```

Use `:` as the classpath separator on Linux/macOS. Compare Hinode files directly
and compare the Elektro-L output hashes, counters and input hashes. The Hinode
capture is bounded to 10,000 fixed-size frames, requires source EOF and matching
decoder good-frame accounting, and writes output only after a successful replay.
For the optional desktop smoke test, compile and launch
`regression/InspectorDesktopSmoke.java` with the core classes and test
dependencies on the classpath. Set `APPDATA` on Windows, or `user.home` on other
platforms, to a fresh directory inside the system temporary directory first. The
helper checks this isolation before opening or saving any settings.
`regression/DecoderDesktopSmoke.java` exercises the synthetic decoder monitoring
window in the same way, using the core classes and test dependencies.

## Remaining coverage

Live FMComms2 acquisition and external remote protocol peers were not exercised
for this record. The documented default IIOD address `192.168.2.1:30431` was
not reachable; no other hardware endpoint was supplied. Automated simulated
device and local protocol checks do not establish those behaviors. The one-run
throughput figures do not establish performance on other machines.
