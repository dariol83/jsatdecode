# Source extraction: Step 3 regression record

**Status: Step 3 gate passed.** The comparison revision is
`4e72145694692a5f1555f83ba8ad9fc0266b1c11`; see the
[Step 2 record](step-2-api-extraction.md). Checks ran on Windows 11 amd64,
OpenJDK 25.0.2 and Maven 3.9.11. Reactor and baseline builds used isolated
source copies and the offline Maven cache, preserving checkout build artifacts.

## Modules and verification

- `source_file` supplies `wav`, `cf32` and `cs16`; `source_fmcomms2` supplies
  `fmcomms2`. Both preserve their Java packages, IDs and configuration keys.
  Their Maven runtime trees contain API as their only JSatDecode dependency;
  neither source module has third-party dependencies. JPMS descriptors and
  `jdeps` show file sources use API and `java.base`, while FMComms2 additionally
  uses `java.xml`. API itself still requires only `java.base`.
- Core has no production dependency or import of a concrete source module.
  Inspector still selects FMComms2 and uses the same settings, but obtains it
  through the source service. Optional API capabilities preserve nonblocking
  cancellation, failure reporting and count-only read diagnostics.
- A clean `mvn clean verify` passed, including 125 JUnit tests, 25,722
  file-source checks, 197 simulated FMComms2 checks and 1,101 CF32 recording
  checks. The moved radio tests run from the separate `integration_tests`
  project, keeping core and file sources out of the FMComms2 module's dependency
  graph. A targeted runtime test also verified propagation of the original
  failure from a managed source.
- Distribution checks validated seven explicit module descriptors, service
  binding, module-path and classpath discovery, and independent consumers with
  just API and one source provider. The file consumer replayed test-owned WAV,
  CF32 and CS16 samples; the FMComms2 consumer created both normal and
  diagnostic sources without connecting hardware. Inspector's normal and
  diagnostic service paths also passed against the local simulated IIOD server
  on both the module path and classpath.
- Only Maven dependency declarations changed under the decoder modules; no
  decoder implementation or example configuration changed. The source code was
  relocated with five small capability or factory declarations; the remaining
  21 relocated production and test files are unchanged.

## Recording comparison

The same private recordings and input SHA-256 values documented in Step 1 were
used. The Step 2 revision was rebuilt to recreate its frame files, then each
ordered output was compared byte-for-byte with Step 3. Timings are single runs
on the same host and vary with load.

| Measurement | Step 2 | Step 3 |
| --- | ---: | ---: |
| Hinode samples / accepted frames | 45,023,232 / 309 | 45,023,232 / 309 |
| Hinode bad / total candidates | 81 / 390 | 81 / 390 |
| Hinode fresh replay | 27.692 s, 3.252x | 27.638 s, 3.258x |
| Elektro-L samples / accepted frames | 103,965,627 / 273 | 103,965,627 / 273 |
| Elektro-L bad / total candidates | 11 / 284 | 11 / 284 |
| Elektro-L qualification replay | 17.587 s, 5.911x | 17.585 s, 5.912x |

The 275,628-byte Hinode outputs are identical, with SHA-256
`caf9c035e68d33584b0338b37bf658e6e0c30a25cdb3d6f379b75ecdd8fc7472`.
The EOF decoder snapshots match, including four duplicates and carrier,
symbol and frame locks all `LOCKED`.

The 60,060-byte Elektro-L outputs are identical, with SHA-256
`f2c3dde4f0a7e917fad726d30132329c288a5daa1dc52e0a7d38b26b3217`.
Post-stop decoder counters and lock states match. The existing qualification
runner separately checked frame checksum annotations and complete source/runtime
accounting.

For future byte comparisons, after building each revision, run from its root
with an unused output path on Windows:

```powershell
$hinodeCp = 'decoder_hinode/target/test-classes;decoder_hinode/target/classes;decoder_hinode/target/lib/*'
java -cp $hinodeCp regression/HinodeBaselineReplay.java path/to/Hinode_90secs.wav path/to/new-hinode-frames.bin
$ggakCp = 'decoder_elektrol_ggak/target/test-classes;decoder_elektrol_ggak/target/classes;decoder_elektrol_ggak/target/lib/*'
java -cp $ggakCp regression/ElektroBaselineReplay.java path/to/GGAK.cs16 path/to/new-ggak-frames.bin
```

The Elektro-L helper was added in Step 3; use that helper from this revision
when replaying an earlier build. Use `:` as the classpath separator on Linux or
macOS.

## Compatibility checks and coverage limits

The unchanged Elektro-L WAV and CS16 examples and the Hinode WAV example ran
headless with generated inputs and only `source.file` overridden. Both decoder
development launchers discovered all four source IDs. The actual Inspector and
synthetic monitoring windows opened and closed at the Step 2 sizes (1280 by 850
and 1936 by 1048 pixels), using isolated Inspector preferences.

The default FMComms2 endpoint `192.168.2.1:30431` could not be reached; no
live-radio acquisition was performed. The IIOD and recording checks used local
simulated peers. External remote protocol peers were not exercised. The private
recordings and one-run throughput figures do not establish behavior on other
inputs or machines.

Local frame captures, reports, build logs and packaged distributions are in the
ignored directory `target/step3-regression-470f837abc294358967d06b9868b3894/`.
They can be removed by a future clean build.
