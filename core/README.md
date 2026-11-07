# JSatDecode core

The Java 21 core module provides sample sources, decoder and distributor APIs,
runtime management, monitoring, spectrum utilities, the optional Swing decoder
window and [JSatInspector](docs/jsatinspector.md). Java packages use
`eu.dariolucia.jsatdecode`.

## Sources, decoders and outputs

File acquisition supports WAV, CF32 and CS16 recordings with sample ranges,
repeat and optional real-time pacing. The `fmcomms2` source acquires normalized
IQ from a PlutoPlus/FMComms2 IIOD TCP server using Java sockets and XML parsing.
It supports RF configuration, receiver selection and native FPGA decimation
where exposed by the device. See [file sources](docs/file-sources.md) and
[FMComms2 source](docs/fmcomms2-source.md).

The built-in `dummy` decoder emits raw-IQ integration records. Mission decoders
are separate Maven modules: [Elektro-L GGAK](../decoder_elektrol_ggak/README.md)
and [Hinode](../decoder_hinode/README.md). The
[shared decoder library](../decoder_base/README.md) provides common lifecycle,
configuration, DC blocking and monitoring support. Factories are discovered
through `ServiceLoader`; include mission JARs and their dependencies on the
module path or classpath to make them available.

Frame and monitoring outputs include synthetic consumers and SatDecode v1 ZMQ
publishers. `--store <folder>` adds decoded-frame recording alongside configured
outputs; see [frame recording](docs/frame-recording.md). FMComms2 can also record
source IQ with `source.recording.enabled=true`; the default destination is
Java's `user.home`. See [IQ recording](docs/iq-recording.md).

`source.output_frequency_offset` shifts source IQ by a signed frequency in Hz
before decoder processing. `-v` logs independent carrier/symbol/frame lock changes
and complete monitoring snapshots. See [runtime and CLI](docs/runtime.md).

All public timestamps use [UTC epoch nanoseconds](docs/time.md). Decoder authors
must follow the [decoder implementation specification](docs/decoder-implementation-specification.md),
including the ten mandatory `DecoderStatusKeys` metrics and unavailable-value rules.

## Build and run

Use JDK 21 or later and Maven. From the repository root:

```text
mvn verify
mvn -pl core verify
```

The full reactor produces a [stand-alone distribution](../distribution/README.md)
in `distribution/target` containing both applications, both mission decoders and
runtime dependencies. After extraction, use `bin/jsatdecoder.bat` or
`bin/jsatdecoder.sh`, and `bin/jsatinspector.bat` or `bin/jsatinspector.sh`.
These launchers use Java's module system and require Java 21 or later from
`JAVA_HOME` or `PATH`.

For core development, Maven copies dependencies to `core/target/test-dependencies`.
From the repository root on Windows:

```powershell
java -cp 'core/target/classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.MainLauncher --help
java -cp 'core/target/classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.MainLauncher --ui-demo
java -cp 'core/target/classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.MainLauncher --config core/examples/dummy.properties --set source.file=C:/recordings/input.cf32 --headless -v
java -cp 'core/target/classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.MainLauncher --inspector
```

Use `:` instead of `;` as the classpath separator on Linux/macOS. The core JAR
declares a main entry point and also has a `modular` classifier; its dependencies
are separate JARs.

Maven's Exec plugin runs self-contained tests for APIs, observability, FFTs, file
sources, IIOD acquisition/recording, runtime, verbose logging, the ZMQ client,
Swing monitoring and Inspector. Failed checks fail the build without requiring
`-ea`. Surefire's separate zero-test report is expected because these harnesses
do not use JUnit. Desktop and external-recording checks are opt-in; see the
[implementation guide](docs/implementation-guide.md) and the relevant module docs.

## Documentation

- [Framework specification](specs/JSatDecode_Specification.md)
- [Architecture and API contracts](docs/architecture.md)
- [Implementation guide](docs/implementation-guide.md)
- [Decoder implementation specification](docs/decoder-implementation-specification.md)
- [Runtime, CLI and output configuration](docs/runtime.md)
- [File-source formats and lifecycle](docs/file-sources.md)
- [FMComms2/PlutoPlus acquisition](docs/fmcomms2-source.md)
- [Source IQ recording](docs/iq-recording.md)
- [Decoded-frame recording](docs/frame-recording.md)
- [ZMQ publisher configuration and mapping](docs/zmq-compatibility.md)
- [ZMQ client interface and Java client](docs/zmq-distribution-interface.md)
- [FFT and spectrum utilities](docs/spectrum.md)
- [Swing monitoring](docs/swing-ui.md)
- [RF/baseband spectra](docs/spectrum-ui.md)
- [Constellation display](docs/constellation-ui.md)
- [JSatInspector](docs/jsatinspector.md)
- [UTC time contract](docs/time.md)
