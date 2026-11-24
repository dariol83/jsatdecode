# API extraction: Step 2 regression record

**Status: Step 2 gate passed.** The reference for this comparison is the
Step 1 revision `3fc52a5bed5f162c423eda28d0820fcc773954d8` and its
[regression record](step-1-baseline.md). Tests ran on Windows 11 amd64 with
OpenJDK 25.0.2 and Maven 3.9.11. Builds used isolated source copies and the
offline Maven cache for the reactor and baseline comparisons. The standalone
API build used its new `api/target` directory; existing build artifacts in the
checkout were preserved.

## Build and module checks

- `mvn -f api/pom.xml clean verify` passed independently. The API POM has no
  dependencies; `jdeps --print-module-deps` reported only `java.base`.
- A clean reactor `mvn clean verify` passed. A subsequent `mvn verify` passed
  with the added module-boundary assertions. These builds include the 125 JUnit
  tests and the existing API, runtime, simulated source, protocol, Inspector and
  decoder test harnesses.
- Distribution checks verified the five explicit module descriptors, project
  dependencies, absence of split packages, service binding and both Windows and
  POSIX launchers. Named consumers compiled and ran with the API alone and with
  the shared decoder library, API and jradio, without core. A separate consumer
  exercised both mission decoders through core's service registry.
- Maven runtime dependency trees confirmed that the shared decoder library uses
  API, and each mission decoder uses API and the shared library, with no core
  dependency in those runtime trees.
- All 64 relocated Java production and test files match their Step 1 contents.
  The only changes under `decoder_base` and the mission decoder modules are in
  their POMs and JPMS descriptors. Existing example configurations are unchanged.

## Recording comparison

Both recordings are the same private inputs identified by SHA-256 in the Step 1
record. Timings are single runs on the same machine and vary with system load.

| Measurement | Step 1, Java 25 | Step 2, Java 25 |
| --- | ---: | ---: |
| Hinode samples / accepted frames | 45,023,232 / 309 | 45,023,232 / 309 |
| Hinode bad / total candidates | 81 / 390 | 81 / 390 |
| Hinode replay | 27.792 s, 3.240x | 27.863 s, 3.232x |
| Elektro-L samples / accepted frames | 103,965,627 / 273 | 103,965,627 / 273 |
| Elektro-L bad / total candidates | 11 / 284 | 11 / 284 |
| Elektro-L runtime replay | 18.176 s, 5.720x | 17.587 s, 5.911x |

The Hinode output contains 275,628 bytes with SHA-256
`caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472`.
Because the original local Step 1 frame file was no longer available, the Step 1
revision was rebuilt and replayed again; its output compared byte-for-byte equal
to Step 2. Carrier, symbol and frame locks were `LOCKED`, with four duplicates,
and the deterministic decoder monitoring values matched.

The Elektro-L ordered-frame SHA-256 was
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b368b26b3217`,
matching Step 1. The existing qualification runner checked frame checksum
annotations and complete CS16 source/runtime accounting.

## Compatibility checks and coverage limits

The unchanged Elektro-L WAV and CS16 example configurations both ran headless
with short generated inputs and only the `source.file` override. The real
Inspector and synthetic decoder monitoring windows opened and closed; their
sizes matched Step 1 (1280 by 850 and 1936 by 1048 pixels, respectively).
Inspector preferences were isolated in a temporary directory.

Live FMComms2 hardware and external remote protocol peers were not available for
this comparison, as in Step 1. Simulated device and local protocol tests passed.
The private recordings and one-run throughput figures do not establish behavior
on other inputs or machines.

Local frame captures, reports and logs are in the ignored directory
`target/step2-regression-13cce03ef2cc47e384c4b659cca3cef0/`. They can be
removed by a future clean build.
