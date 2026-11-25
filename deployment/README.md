# Application deployments

The `deployment` module creates separate Java 25 applications. Each ZIP or
tar.gz contains its launchers, icon, documentation, license and runtime JARs.
Maven and a native SDR library are not needed to run either application.

| Archive | Runtime contents | Launcher |
| --- | --- | --- |
| `jsatdecode-<version>` | API, core, file/FMComms2 sources, ZMQ/JTDP providers, decoder-base, Elektro-L GGAK, Hinode and their third-party dependencies | `jsatdecoder.bat` / `jsatdecoder.sh` |
| `jsatinspector-<version>` | API, core, Inspector and file/FMComms2 sources: five JARs, no mission decoder or transport JARs | `jsatinspector.bat` / `jsatinspector.sh` |

## Build and extract

With JDK 25 or later and Maven, run from the repository root:

```text
mvn clean verify
```

Both deployment profiles are active by default. Select one explicitly to build
and verify its archive and reactor dependencies:

```text
mvn -pl deployment -am -Pjsatdecode verify
mvn -pl deployment -am -Pjsatinspector verify
```

Use `-Pjsatdecode,jsatinspector` to select both explicitly. The deployment
module is `eu.dariolucia.jsatdecode:jsatdecode-deployment`, replacing the former
`distribution` packaging project. It is not published as a Maven library.
Provider modules `distribution_zmq` and `distribution_jtdp` retain their names.

The package phase creates these files in `deployment/target`:

```text
jsatdecode-0.1.0-SNAPSHOT.zip
jsatdecode-0.1.0-SNAPSHOT.tar.gz
jsatinspector-0.1.0-SNAPSHOT.zip
jsatinspector-0.1.0-SNAPSHOT.tar.gz
```

Later project versions use their corresponding version in these names. Each
archive has its own enclosing directory. Extract the application you want;
the two installations can be used independently. Unix executable permissions
are preserved in tar.gz. If a ZIP extractor drops them, use `sh bin/<app>.sh`.

## Run JSatDecode

Install Java 25 or later with `java.desktop` and `java.xml`, including for
headless decoding. The scripts use `JAVA_HOME` when set and otherwise `java`
from `PATH`. A Java runtime is not bundled.

From the extracted **jsatdecode** directory on Windows:

```text
.\bin\jsatdecoder.bat --help
.\bin\jsatdecoder.bat --list-decoders
.\bin\jsatdecoder.bat --list-sources
.\bin\jsatdecoder.bat --config examples/decoder_hinode/hinode-wav.properties --set "source.file=C:/recordings/Hinode_90secs.wav" --headless -v
```

On Linux/macOS use `./bin/jsatdecoder.sh` with the same arguments. Existing
properties and source/distribution IDs remain valid. Adapt the recording path
or hardware address before acquisition. `--ui` opens decoder monitoring;
`--store <folder>` records decoded frames alongside configured outputs.

## Run JSatInspector

From the extracted **jsatinspector** directory on Windows:

```text
.\bin\jsatinspector.bat
.\bin\jsatinspector.bat --help
```

On Linux/macOS use `./bin/jsatinspector.sh`. Inspector provides source selection,
source-specific settings, spectrum/waterfall and CF32/CS16 recording. It retains
existing preferences and starts disconnected. A graphical desktop is required
except for `--help` / `-h`.

Core's `MainLauncher --inspector` selector remains available when the Inspector
JAR is installed, including in the Inspector deployment. To install both apps
in one module path, combine their `lib` contents while keeping a single version
of each JAR. The default deployments keep the applications separate.

## Paths, modules and verification

All arguments are forwarded unchanged. Quote paths containing spaces as usual
for the shell. Scripts locate `lib/` relative to themselves and preserve the
caller's working directory, so relative configuration/input/output paths keep
their existing meaning. Application failures propagate a nonzero exit code.

Launchers use the Java module path and service binding. No `ALL-MODULE-PATH`
option is needed. Provider discovery works with both JPMS registrations and
classpath service resources. Third-party automatic-module filenames must be
preserved. There are no added native dependencies.

`verify` extracts each selected archive into a path with spaces, checks its
module/dependency graph and isolated provider consumers, and runs its launchers
from a different directory. Windows and available POSIX shells are exercised.
The final migration report is in `docs/regression/step-9-final-migration.md`.

See `docs/README.md`, `docs/core/docs/runtime.md`,
`docs/inspector/docs/jsatinspector.md` and
`docs/core/docs/java-modules.md` in an extracted package for application details.
The `icons` directory contains the corresponding PNG for desktop shortcuts.
