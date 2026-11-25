# Source configuration: Step 5 regression record

**Status: Step 5 gate passed.** The comparison baseline is
revision `a62c074159cf395a8063966b7b42ec247f31b103`, following the
[Step 4 record](step-4-distribution-extraction.md). Checks use Windows 11 amd64,
OpenJDK 25.0.2 and Maven 3.9.11, with the offline Maven cache. Clean builds run
in `target/step5-workspace` to preserve the checkout's existing regression artifacts.

## Contract assessment and changes

The existing contracts exposed source identity and construction, but no property
metadata or runtime-setting operation. They could discover factories but could
not construct a generic configuration editor. An API change was therefore required.

- `SampleSourceFactory.configurationProperties()` is a default method returning
  an immutable empty list when metadata is unavailable. Existing providers need
  no implementation change. The four-field `SourceDescriptor` remains unchanged.
- `SourcePropertyDescriptor` describes the existing unprefixed key, display
  label, description, type, unit, default, required flag, inclusive bounds,
  allowed values and runtime-update flag. It contains no provider or GUI types.
- `RuntimeConfigurableSampleSource` is an optional capability accepting partial
  updates on the management thread while RUNNING. Omitted keys retain their
  values. Factory parsers remain responsible for full configuration validation;
  descriptors do not inject defaults into CLI configurations.
- FMComms2 exposes 31 properties. Frequency, bandwidth, both gain modes and both
  manual-gain settings support runtime requests. Validation precedes opening a
  separate control connection, leaving READBUF and sample timing on the streaming
  connection. Only the selected RX channel's gain attributes are written. I/O
  failures fail and cancel acquisition because hardware changes can be partial;
  intentional cancellation also closes a pending control connection.
- WAV, CF32 and CS16 each expose nine properties with their existing defaults.
  Their settings remain setup-only. WAV rate is inferred from its header unless
  an explicit matching rate is supplied; raw formats still require a rate.

## Inspector behavior

Inspector discovers available providers, offers a source combo box and builds
the selected provider's controls from its descriptors. Runtime fields remain
editable while connected; Apply source changes submits only changed live keys
through the management worker. Source selection, software decimation and static
fields require disconnecting. Drafts survive switching sources in the window.

FMComms2 remains the default when available. Missing saved providers fall back
to an available provider, and an empty registry disables Connect. Existing
Inspector preferences retain their FMComms2 configuration, defaults, RX-buffer
conversion and low-rate software-decimation behavior. New preferences store the
selected source and its existing property names alongside the legacy fields.

File sources use their actual rate and center metadata for FFT and recording.
Normal EOF releases the session without an error. Configured repeat boundaries
preserve recording-relative source indices; Inspector accepts these rewinds and
continues checking continuity within each pass. Recording supports unspecified
center frequency and fractional sample rates. Existing radio recording bytes
and integer-valued filename fields are preserved. Runtime edits do not rename or
split an active recording, whose filename describes its starting settings.

## Verification and limits

- The pre-change `mvn -o verify` baseline passed. Final `mvn -o clean verify`
  passed across the complete reactor: 137 JUnit tests, 25,722 file-source checks,
  197 simulated FMComms2 checks, 1,101 source-recording checks, 599 ZMQ reference
  checks and the existing Inspector, runtime, decoder and numerical suites.
  Packaged Windows/POSIX launchers, service binding, external module consumers
  and fixed wire/recording compatibility fixtures passed. The final log is
  `target/step5-final-verify.log`; all 25 changed Java files were hash-compared
  with the isolated build copy.
- Seven new core JUnit tests cover metadata, legacy-provider compatibility,
  descriptor validation, runtime patches/default restoration, preferences,
  all file providers, source readback, repeated-segment recording and live/static
  editor availability. Three IIOD integration tests cover independent live
  control, validation before I/O, unchanged sample-clock continuity, hardware
  failure and cancellation of a blocked control request.
- The desktop window test checks source switching, preserved drafts, live versus
  static fields, layout bounds, recording/FFT controls and close with an unfinished
  edit. The real-application desktop test checks restored preferences, disconnected
  startup, persistence and worker cleanup in an isolated child JVM.
- An independent named-module consumer discovers all four providers and their
  property metadata using only API and the two source modules. `jdeps` reports
  API requires only `java.base`; file sources require API and `java.base`;
  FMComms2 requires API, `java.base` and `java.xml`. No POM or JPMS dependency
  changes were needed. API Javadoc generation passes with existing documentation
  warnings.
- No decoder implementation, example configuration, source parser, source ID,
  distribution implementation or wire format was changed. Inspector remains in
  core; later extraction/deployment steps and SoapySDR are outside this change.

Live hardware and private mission-recording replays were not exercised for this
step. FMComms2 behavior is covered by local IIOD peers and the existing simulated
acquisition/recording suite. The automated checks do not establish live-device
throughput or compatibility with every firmware version.

Build logs, the module probe and the desktop screenshot are in the ignored
`target/step5-*` paths and may be removed by a future clean build.
