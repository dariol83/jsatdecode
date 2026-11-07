# JSatDecode stand-alone distribution

This package contains JSatDecoder, JSatInspector, `core`, `decoder_base`, the
Elektro-L GGAK and Hinode decoders and every runtime dependency. No Maven installation is needed
to run it. Install Java 21 or later, including desktop support for JSatInspector
or decoder monitoring windows. The scripts use `JAVA_HOME` when set, otherwise
the `java` command on `PATH`. A Java runtime is not bundled.

## Build and extract

From the repository root, with JDK 21 or later and Maven:

```text
mvn verify
```

The `package` phase creates the archives, so `mvn package` also builds them.
To select the distribution and all modules it needs, use:

```text
mvn -pl distribution -am verify
```

The archives are `distribution/target/jsatdecode-0.1.0-SNAPSHOT.zip` and
`distribution/target/jsatdecode-0.1.0-SNAPSHOT.tar.gz`. Their enclosing directory
is `jsatdecode-0.1.0-SNAPSHOT/`. Later project versions use the corresponding
version in these names. Extract either archive to a directory of your choice.
The tar.gz archive preserves executable permissions for the Unix scripts; if
your ZIP extraction tool does not, invoke them with `sh bin/jsatdecoder.sh` or
`sh bin/jsatinspector.sh`.

## Run JSatDecoder

From the extracted directory on Windows (PowerShell or Command Prompt):

```text
.\bin\jsatdecoder.bat --help
.\bin\jsatdecoder.bat --list-decoders
.\bin\jsatdecoder.bat --config examples/decoder_hinode/hinode-wav.properties --set "source.file=C:/recordings/Hinode_90secs.wav" --headless -v
```

On Linux/macOS:

```sh
./bin/jsatdecoder.sh --help
./bin/jsatdecoder.sh --list-decoders
./bin/jsatdecoder.sh --config examples/decoder_hinode/hinode-wav.properties --set "source.file=/path/to/recordings/Hinode_90secs.wav" --headless -v
```

Adapt the example configuration to your recording or radio before decoding.
`--list-decoders` lists the included mission factories and the integration dummy
decoder. Use `--ui` to open decoder monitoring or `--store <folder>` to record
decoded frames alongside configured outputs.

All script arguments are passed to the application. Quote paths containing
spaces as usual for your shell. The scripts locate `lib/` relative to themselves,
so they can also be invoked using an absolute path from another directory.
They preserve your current working directory: relative configuration, recording
and output paths are resolved from the directory where you run the command.

## Run JSatInspector

On Windows:

```text
.\bin\jsatinspector.bat
.\bin\jsatinspector.bat --help
```

On Linux/macOS:

```sh
./bin/jsatinspector.sh
./bin/jsatinspector.sh --help
```

JSatInspector opens the PlutoPlus spectrum analyser, waterfall and IQ recorder.
Its settings are configured in the window and saved separately from decoder
properties. Its script forwards every argument; currently the application
accepts `--help` / `-h` and otherwise starts without arguments.

## Package contents

| Location | Contents |
| --- | --- |
| `bin/` | Windows `.bat` and Unix `.sh` launchers for each application |
| `lib/` | Core, shared decoder library, every mission decoder and all runtime JARs |
| `examples/<module>/` | Example configurations from core and each mission |
| `docs/` | Framework and mission documentation |

Start with `docs/README.md` for the documentation index,
`docs/core/docs/runtime.md` for decoder CLI and configuration details, or
`docs/core/docs/jsatinspector.md` for Inspector controls and recording formats.

The mission modules are Elektro-L GGAK and Hinode. The launchers use
`java --module-path ... --module ...`; application and dependency JARs are
loaded through the Java module system without an application classpath.
The application, shared library and mission JARs are automatic modules. Core's
manifest supplies the stable `Automatic-Module-Name`
`eu.dariolucia.jsatdecode.core`; dependencies retain their own module metadata.
The launchers resolve all modules on the module path so every included decoder
is available for factory discovery.
The core module also builds a JAR with a main entry point for individual core
development. Include runtime dependencies on the classpath for decoding; the
distribution launchers supply them automatically.
