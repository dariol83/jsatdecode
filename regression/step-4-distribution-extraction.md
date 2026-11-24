# Distribution extraction: Step 4 regression record

**Status: Step 4 gate passed.** The pre-extraction comparison is revision
`1e9885e`; see the [Step 3 record](step-3-source-extraction.md). Checks ran on
Windows 11 amd64, OpenJDK 25.0.2 and Maven 3.9.11. Builds used isolated source
copies and the offline Maven cache, preserving checkout build artifacts.

## Modules and verification

- `distribution_zmq` owns the `zmq` frame and monitoring factories, the
  `frame-file` recorder and the SatDecode client. `distribution_jtdp` owns the
  `tcp` frame and monitoring factories, codecs, client and shared listener
  registry. Existing package names, IDs and service registrations are retained.
  Of the 59 relocated implementation and test files, only the two adapters
  that use the new API capabilities and one integration test changed content.
- Maven dependency trees show that each provider depends on API alone among
  JSatDecode modules. JTDP has no third-party dependencies; ZMQ uses JeroMQ
  0.6.0 and its jnacl dependency. API still requires only `java.base`. JPMS
  descriptors and `jdeps` confirm the same project-module boundaries.
- The generic bounded output mailbox moved to API without changing its worker
  or queue behavior. JTDP supplies a configuration-normalizer service for shared
  TCP aliases and implements an optional initial-monitoring capability. The
  runtime invokes these through API contracts, and the shared listener remains
  in JTDP. Tests cover validation before resource creation, selective initial
  publication and the order of startup events.
- A clean `mvn clean verify` passed in the isolated copy. It ran 127 JUnit
  tests, 599 ZMQ reference-client checks, 25,722 file-source checks and the
  existing runtime, simulated FMComms2, Inspector, decoder and other suites.
  The runtime capability test also passed in a subsequent `mvn -pl core -am
  verify`. The complete distribution was tested on Windows and POSIX launchers.
  API and both provider Javadoc JARs also built successfully.
- Packaged-distribution checks validate all nine application modules and their
  service binding. Separate named-module and classpath consumers discover,
  construct, start and stop each distribution provider with API and only its
  own dependencies; they also write and verify a frame-file recording. The
  existing ZMQ tests use local real TCP publisher/subscriber sockets, and JTDP
  tests exercise its server, clients, shared listener and wire fixtures.
- The unchanged ZMQ dummy and Elektro-L TCP example configurations ran through
  the packaged launcher with generated input and only `source.file` overridden.
  Test-owned configuration and data are used for the new automated tests.
  Only Maven dependencies changed under decoder directories; no decoder
  implementation or example configuration was changed.

## Wire and recording comparison

`DistributionWireReplay` runs the same fixed-clock inputs on the pre-extraction
and extracted implementations. It compares the bytes of two SatDecode frames,
one monitoring status and one event, with multipart lengths added by the test
for comparison; it also compares the actual two-frame recording and JTDP frame,
monitoring and event messages. All files were byte-for-byte identical.

| Capture | Bytes | SHA-256 |
| --- | ---: | --- |
| SatDecode multipart contents | 1,001 | `896f994510837121bbc40baa0cbff4fe2658623758715373d11348aa05c84f81` |
| Frame-file recording | 516 | `02a9eb1b7bc6fa3fc6b023d0025fe73681ba328bd74e7e4db4b0bf658f63635b` |
| JTDP messages | 106 | `5aa661598390cc38067c8dfb5249554715ffa436914f168f7cfd085c5c6369f8` |

The full build runs the same fixture against the pre-extraction hashes. These
checks cover the selected inputs and are complemented by the existing protocol,
ordering, lifecycle, socket and error tests.

## Mission recording comparison

The private reference recordings documented in Step 1 were replayed on both
revisions. Output frames match byte-for-byte and match the Step 1 hashes. These
are single-run timings and vary with machine load.

| Measurement | Step 3 baseline | Step 4 |
| --- | ---: | ---: |
| Hinode samples / accepted frames | 45,023,232 / 309 | 45,023,232 / 309 |
| Hinode bad / total candidates | 81 / 390 | 81 / 390 |
| Hinode replay | 28.289 s, 3.183x | 27.895 s, 3.228x |
| Elektro-L samples / accepted frames | 103,965,627 / 273 | 103,965,627 / 273 |
| Elektro-L bad / total candidates | 11 / 284 | 11 / 284 |
| Elektro-L replay | 18.586 s, 5.594x | 18.331 s, 5.672x |

The 275,628-byte Hinode outputs have SHA-256
`caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472`.
The 60,060-byte Elektro-L outputs have SHA-256
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b26b3217`.
Decoder counters and lock states match the baseline; elapsed-time measurements
are naturally variable. The [Step 3 record](step-3-source-extraction.md)
contains the commands for reproducing these captures.

## Coverage limits

No live FMComms2 device or external SatDecode peer was available. Local
simulated hardware and independent local protocol clients were exercised;
they do not establish behavior with every remote implementation. The one-run
throughput figures do not establish performance on other machines.

Local frame captures and logs are in the ignored directory
`target/step4-regression-ed56ad8ffd8f4309bac4cc0e9c75332d/`. They may be removed
by a future clean build.
