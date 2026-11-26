# JSatInspector

`eu.dariolucia.jsatdecode:jsatdecode-inspector` is the standalone source
inspection, spectrum/waterfall and CF32/CS16 recording application. Its JPMS
name is `eu.dariolucia.jsatdecode.inspector`. It retains the existing
`eu.dariolucia.jsatdecode.inspector` Java package and user preferences.

Inspector depends on API and core's shared source registry and spectrum/UI
components. It has no production dependency on a concrete source, decoder or
distribution module. Sources are discovered through the API factory service.
Include `source_file` and/or `source_fmcomms2` at runtime for acquisition.
When the source offers leased samples, Inspector borrows them for synchronous
analysis and retains independent claims for asynchronous recording. Queued
claims are released on drain, rejection and writer failure; source shutdown
does not invalidate samples still being written. Sources without the capability
continue to use retainable legacy blocks.

With JDK 25 and Maven, build and test from the repository root:

```text
mvn -pl inspector -am verify
```

On Windows, launch directly using the development outputs:

```powershell
java --module-path 'inspector/target/classes;inspector/target/test-dependencies' --module eu.dariolucia.jsatdecode.inspector/eu.dariolucia.jsatdecode.inspector.JSatInspector
java -cp 'inspector/target/classes;inspector/target/test-dependencies/*' eu.dariolucia.jsatdecode.inspector.JSatInspector --help
```

Use `:` as the path separator on Linux/macOS. `target/test-dependencies`
includes test-only JUnit dependencies; production needs only API, core and the
selected source providers. The main JAR, `target/jsatinspector.jar`, declares
JSatInspector as its entry point.

The [Inspector deployment](../deployment/README.md) includes the application
and installs this module's `src/main/bin/jsatinspector.sh` and `.bat` launchers.
The core launcher's `--inspector` selector remains compatible through the
`ApplicationLauncher` service, registered for both JPMS and classpath launches.
No runtime, decoder or distributor is constructed by Inspector.

See the [application guide](docs/jsatinspector.md) for configuration, recording,
desktop checks and source diagnostics. Existing acquisition, analysis,
preferences and recording tests run from this module.
