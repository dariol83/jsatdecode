# Java modules

JSatDecode targets Java 25. Each Java artifact includes `module-info.class` in
its main JAR and can run on the Java Platform Module System (JPMS) module path.
The Maven reactor's `deployment` project packages these artifacts, while
`integration_tests` runs cross-module tests; neither is a Java module. Maven
directory and artifact names differ from JPMS names:

| Maven directory | Maven artifact | JPMS module |
| --- | --- | --- |
| `api` | `jsatdecode-api` | `eu.dariolucia.jsatdecode.api` |
| `source_file` | `jsatdecode-source-file` | `eu.dariolucia.jsatdecode.source.file` |
| `source_fmcomms2` | `jsatdecode-source-fmcomms2` | `eu.dariolucia.jsatdecode.source.fmcomms2` |
| `distribution_zmq` | `jsatdecode-distribution-zmq` | `eu.dariolucia.jsatdecode.distribution.zmq` |
| `distribution_jtdp` | `jsatdecode-distribution-jtdp` | `eu.dariolucia.jsatdecode.distribution.jtdp` |
| `core` | `jsatdecode-core` | `eu.dariolucia.jsatdecode.core` |
| `inspector` | `jsatdecode-inspector` | `eu.dariolucia.jsatdecode.inspector` |
| `decoder_base` | `decoder-base` | `eu.dariolucia.jsatdecode.decoder.base` |
| `decoder_elektrol_ggak` | `decoder-elektrol-ggak` | `eu.dariolucia.jsatdecode.decoder.elektrol.ggak` |
| `decoder_hinode` | `decoder-hinode` | `eu.dariolucia.jsatdecode.decoder.hinode` |

The group ID for all ten artifacts is `eu.dariolucia.jsatdecode`.

## Dependencies and exported packages

| Module | Declared dependencies, in addition to implicit `java.base` |
| --- | --- |
| API | None |
| File sources | `requires transitive eu.dariolucia.jsatdecode.api` |
| FMComms2 source | `requires transitive eu.dariolucia.jsatdecode.api`, `requires java.xml` |
| ZMQ distribution | `requires transitive eu.dariolucia.jsatdecode.api`, `requires org.zeromq.jeromq` |
| JTDP distribution | `requires transitive eu.dariolucia.jsatdecode.api` |
| Core | `requires transitive eu.dariolucia.jsatdecode.api`, `requires transitive java.desktop` |
| Inspector | `requires eu.dariolucia.jsatdecode.api`, `requires eu.dariolucia.jsatdecode.core`, `requires java.desktop` |
| Shared decoder library | `requires transitive eu.dariolucia.jsatdecode.api`, `requires jradio` |
| Each mission decoder | `requires transitive eu.dariolucia.jsatdecode.api`, `requires transitive eu.dariolucia.jsatdecode.decoder.base`, `requires jradio` |

Each source and distribution module depends on API alone among JSatDecode modules.
Core has no production dependency on those providers, the shared decoder library
or either mission module. Inspector depends on API and core's shared registry and
UI components, and discovers source providers without concrete dependencies.
`requires transitive` makes API contracts readable to consumers of core, the
shared library and mission decoders. A consumer that uses jradio types itself must
declare its own `requires jradio`; jradio is not part of the exported decoder
API. Core's exported Swing panel API extends Java desktop types, so
`java.desktop` is transitive. The packaged application also includes FMComms2,
which requires `java.xml`, including in headless modular launches.

Core tests use the source and distribution modules with test scope. Mission decoder tests and
qualification tools use core and the providers with test scope. Their
builds copy test dependencies to `target/lib` so existing local launcher and
recording commands continue to work; none are published runtime dependencies
of a mission decoder.

All package names below have the prefix `eu.dariolucia.jsatdecode`:

| Module | Exported packages |
| --- | --- |
| API | `api.application`, `api.config`, `api.decoder`, `api.distribution`, `api.frame`, `api.lifecycle`, `api.monitoring`, `api.source`, `api.taps`; `core.buffers`, `core.monitoring`; `dsp.fft`; `util` |
| File sources | `source.file`, `source.cf32`, `source.cs16`, `source.wav` |
| FMComms2 source | `source.fmcomms2` |
| ZMQ distribution | `distribution.zmq`, `distribution.zmq.client` |
| JTDP distribution | `distribution.tcp`, `distribution.tcp.client`, `distribution.tcp.codec`, `distribution.tcp.server` |
| Core | `core.config`, `core.registry`, `core.runtime`; `ui`, `ui.swing` |
| Inspector | `inspector` |
| Shared decoder library | `decoder.base`, `decoder.base.config`, `decoder.base.dsp`, `decoder.base.monitoring` |
| Elektro-L GGAK | `elektrol` |
| Hinode | `hinode` |

Core's launcher, CLI, dummy-decoder and synthetic-distribution
packages are encapsulated on the module path. The JVM can invoke an application
main class without exporting its package. There are no `opens` declarations;
modules use public APIs and service providers without deep reflective access.
The API retains the historical `core.buffers` and `core.monitoring` package
names for shared helpers; those packages are owned solely by API, avoiding split
packages and preserving existing imports.

Inspector exports its existing application package for direct entry-point use.
Its icon and launch scripts belong to that module. Core retains the shared icon
helper, source registry and reusable spectrum widgets. The `ApplicationLauncher`
service preserves `MainLauncher --inspector` without a circular dependency.

Code that imports a concrete source or distribution class on the module path must
require its owning provider module. Existing Java package names are unchanged;
classpath users include the corresponding provider JAR alongside API and the application.

The main core JAR supplies the `MainLauncher` entry point. There is no separate
`modular` classifier. Applications that previously selected that classifier
should depend on the ordinary `jsatdecode-core` artifact.

## Build and verification

From the repository root, using JDK 25 or later and Maven:

```text
mvn clean verify
mvn -f api/pom.xml clean verify
mvn -pl decoder_hinode -am verify
jar --describe-module --file api/target/jsatdecode-api-0.1.0-SNAPSHOT.jar
jar --describe-module --file core/target/jsatdecoder.jar
```

The full build compiles explicit descriptors and runs the Java test harnesses.
The deployment checks inspect the selected packaged descriptors and resolve
each application's module graph with service binding enabled. They reject
unrelated project modules and launch the packaged applications to check factory
discovery and argument handling.
Independent consumers compile and run with API alone, with each source provider,
and with the shared library plus its required dependencies, without core. Source
and distribution providers are also exercised independently on the module path
and classpath. An external named
consumer also uses core's registry to run both mission decoders through service
discovery, checking DSP and lifecycle without access overrides.
The harnesses use the classpath for existing functional tests; the packaged
application checks exercise the module path. Desktop interaction and external
recording checks remain opt-in as described in the component documentation.

Publication remains controlled by the `release` profile for `api`, both source
and distribution modules, `core`, `inspector` and `decoder_base`. Their ordinary published JARs include the
module descriptors.

## Run on the module path

The [application deployments](../../deployment/README.md) collect selected
modules and their runtime dependencies in `lib/`. JSatDecode includes nine
project modules; JSatInspector includes API, core, Inspector and the two source
modules, with no mission decoder or distribution providers. After extracting
the corresponding archive, its launcher runs one of these commands:

```text
java --module-path lib --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.cli.Main --list-decoders
java --module-path lib --module eu.dariolucia.jsatdecode.inspector --help
```

Core declares the factory services it consumes. Standard launcher service
binding resolves matching provider modules from the module path, including the
sources, distributions and mission decoders; no `--add-modules ALL-MODULE-PATH` is needed. To install another
decoder, put its modular JAR and runtime dependencies in `lib/` and register its
factory as described below. Do not place multiple versions of the same module
on that path.

For core development, `mvn -pl core -am verify` copies test/runtime dependencies into
`core/target/test-dependencies`. From the repository root on Windows:

```powershell
java --module-path 'core/target/classes;core/target/test-dependencies' --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.MainLauncher --list-sources
```

Use `:` instead of `;` as the path separator on Linux/macOS. A module-path
directory supplies the JARs inside it; do not add a classpath-style `/*` wildcard.

Some third-party runtime dependencies remain automatic modules, including
jradio 1.121 (`jradio`). Keep their runtime dependencies available and preserve
the dependency filenames supplied by Maven: automatic names can be derived
from filenames. The current runtime graph cannot be packaged directly with
`jlink` while it contains automatic modules.

## Service providers

Core declares `uses` for all four factory contracts and the optional distribution
configuration normalizer and application-launcher services. Inspector declares
`uses SampleSourceFactory` and registers its optional entry point:

| Service in `eu.dariolucia.jsatdecode.api` | Provider module | IDs |
| --- | --- | --- |
| `source.SampleSourceFactory` | File sources | `wav`, `cf32`, `cs16` |
| `source.SampleSourceFactory` | FMComms2 source | `fmcomms2` |
| `application.ApplicationLauncher` | Inspector | `inspector` |
| `decoder.DecoderFactory` | Core | `dummy` |
| `distribution.FrameDistributorFactory` | Core | `synthetic` |
| `distribution.MonitoringDistributorFactory` | Core | `synthetic` |
| `distribution.FrameDistributorFactory` | ZMQ distribution | `zmq`, `frame-file` |
| `distribution.MonitoringDistributorFactory` | ZMQ distribution | `zmq` |
| `distribution.FrameDistributorFactory` | JTDP distribution | `tcp` |
| `distribution.MonitoringDistributorFactory` | JTDP distribution | `tcp` |
| `distribution.DistributionConfigurationNormalizer` | JTDP distribution | Shared TCP aliases and validation |

Each module declares `provides` for its own providers. The Elektro-L GGAK
and Hinode modules each declare `provides DecoderFactory` with their public
factory class, supplying IDs `elektrol_ggak` and `hinode_s_tlm`. The shared decoder
library registers no service provider.

A new decoder that extends the shared infrastructure can use this descriptor:

```java
module com.example.receiver {
    requires eu.dariolucia.jsatdecode.decoder.base;

    provides eu.dariolucia.jsatdecode.api.decoder.DecoderFactory
            with com.example.receiver.ExampleDecoderFactory;
}
```

Declare additional dependencies, such as `requires jradio`, when used directly.
The provider factory must be public and have a public no-argument constructor
for the constructor-based registration used here. A service provider's package
does not need to be exported just for `ServiceLoader`; export packages intended
for direct use by application code. If a consuming named module calls
`ServiceLoader.load` itself, its descriptor must declare `uses` for that service.
Calling core's `FactoryRegistry.discover` supports the four framework factory
interfaces listed above, using core's service declarations. For a custom service
contract, load providers in its consuming module and pass them to the
`FactoryRegistry` constructor.

## Classpath compatibility

The same JARs continue to work on the classpath, where module descriptors do not
enforce package exports. Core, source, distribution and mission modules retain `META-INF/services`
resources for factory discovery. Custom providers should supply both a JPMS
`provides` declaration and the matching service resource when supporting both
launch styles. For example, a decoder resource named
`META-INF/services/eu.dariolucia.jsatdecode.api.decoder.DecoderFactory` contains
the fully qualified factory class name, one provider per line.

To run the extracted distribution on the classpath:

```text
java -cp "lib/*" eu.dariolucia.jsatdecode.MainLauncher --list-decoders
```

Keep application and decoder JARs together on the module path for a modular
launch, or together on the classpath for a classpath launch.
