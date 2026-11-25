# Inspector extraction: Step 6 regression record

**Status: Step 6 gate passed.** The comparison baseline is revision
`4bf40ea761b32a1ae96b9f9a9123779ac452f88d`, following the
[Step 5 record](step-5-source-configuration.md). Checks use Windows 11 amd64,
OpenJDK 25.0.2 and Maven 3.9.11 with the offline Maven cache. Baseline and
extracted builds run in `target/step6-baseline-workspace` and
`target/step6-workspace`, preserving existing regression artifacts.

## Module boundary

The new `inspector` Maven module owns JSatInspector's entry point, acquisition,
analysis, recording, preferences, source editor, presentation and tests. It also
owns the Inspector icon, Windows/POSIX launch scripts and application guide.
Existing Java package names and preference locations are preserved.

Inspector's production dependencies are API and core. Core retains framework
runtime, service discovery, shared spectrum widgets and the shared icon helper.
Concrete sources are discovered through the existing factory service; file and
FMComms2 providers are test dependencies only. No decoder or distributor is
required to build or run Inspector.

A dependency-free `ApplicationLauncher` API service preserves core's
`MainLauncher --inspector` dispatch without a circular dependency. Inspector
registers its launcher through JPMS and `META-INF/services`. A missing optional
Inspector produces an actionable diagnostic. The existing distribution includes
the new application JAR and collects its relocated scripts and icon. Separate
deployment archives and the packaging-module rename remain Step 8 work.

## Baseline comparison

- Sixteen of the eighteen relocated production source files are byte-identical.
  `InspectorWindow` changes only the icon resource owner to `JSatInspector.class`;
  `JSatInspector` changes only its help example for the separate module.
- Forty-four of forty-six baseline Inspector class files are byte-identical,
  including acquisition, decimation, FFT analysis, recording and preferences.
  Only `InspectorWindow.class` and `JSatInspector.class` differ. The launcher
  service implementation is new.
- The icon is byte-identical. Eight of nine moved test sources are unchanged;
  the desktop window test adds an assertion that the relocated icon loads.
- No decoder, source-provider or distribution-provider implementation, POM or
  JPMS descriptor changed. Existing configuration files and defaults are untouched.

## Verification

- Pre-change `mvn -o verify`, independent `mvn -o -pl inspector -am verify` and
  final full-reactor `mvn -o clean verify` all passed. The final run includes
  138 JUnit tests, 25,722 file-source checks, 197 simulated FMComms2 checks,
  1,101 source-recording checks, 599 ZMQ reference checks and the existing
  runtime, decoder, numerical and wire-compatibility suites.
- Relocated Inspector suites pass: settings persistence/boundaries, 8,279 receiver
  checks, 74 recording checks, 549 dropped-sample checks, 708 analysis checks and
  seven source-configuration JUnit tests. They cover existing CF32/CS16 bytes,
  lifecycle, filtering/decimation, FFT cadence and rendering.
- Packaging checks inspect all ten project module descriptors, confirm that
  Inspector classes and its icon belong to the new JAR, and launch Inspector
  using only API, core, Inspector and the two source-provider JARs. Direct JPMS
  launch and compatibility dispatch on both JPMS and classpath pass. Windows and
  POSIX scripts retain argument forwarding, caller working directory and error
  exit behavior.
- The desktop window test passes source selection/drafts, live/static controls,
  FFT/recording settings, sidebar, painting and close with unfinished edits.
  The real-application desktop test passes preference restoration/persistence,
  disconnected startup, worker cleanup and normal exit with isolated settings.
- A real named-module application opens and closes using only the five JARs
  above. The Inspector-owned icon loads, the default window remains 1280 by 850,
  and preferences are saved in a fresh temporary directory.
- `jdeps` confirms API requires only `java.base`; Inspector requires API, core,
  `java.base` and `java.desktop`. Packaging checks retain the rule that each
  source/distribution provider depends only on API among project modules.
  All 39 added/changed Java files were hash-compared with the final build copy.

Live hardware and private mission-recording replays were not exercised for this
step. Acquisition/recording checks use the existing simulated and local-IIOD
suites; byte-identical relocated processing classes provide additional evidence
that extraction did not change those algorithms.

Logs, the bytecode audit and desktop screenshot are in ignored `target/step6-*`
paths. The final full build log is `target/step6-final-verify.log`; desktop logs
are `step6-desktop-window.log`, `step6-desktop-application.log` and
`step6-modular-desktop.log` under `target`. These artifacts may be removed by a
future clean build.
