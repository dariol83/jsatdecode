# Java modules

JSatDecode targets Java 25. Each Java artifact includes `module-info.class` in
its main JAR and can run on the Java Platform Module System (JPMS) module path.
The Maven reactor's `distribution` project packages these artifacts; it has no
Java module of its own. Maven directory and artifact names differ from JPMS
names:

| Maven directory | Maven artifact | JPMS module |
| --- | --- | --- |
| `api` | `jsatdecode-api` | `eu.dariolucia.jsatdecode.api` |
| `core` | `jsatdecode-core` | `eu.dariolucia.jsatdecode.core` |
| `decoder_base` | `decoder-base` | `eu.dariolucia.jsatdecode.decoder.base` |
| `decoder_elektrol_ggak` | `decoder-elektrol-ggak` | `eu.dariolucia.jsatdecode.decoder.elektrol.ggak` |
| `decoder_hinode` | `decoder-hinode` | `eu.dariolucia.jsatdecode.decoder.hinode` |

The group ID for all five artifacts is `eu.dariolucia.jsatdecode`.

## Dependencies and exported packages

| Module | Declared dependencies, in addition to implicit `java.base` |
| --- | --- |
| API | None |
| Core | `requires transitive eu.dariolucia.jsatdecode.api`, `requires transitive java.desktop`, `requires java.xml`, `requires org.zeromq.jeromq` |
| Shared decoder library | `requires transitive eu.dariolucia.jsatdecode.api`, `requires jradio` |
| Each mission decoder | `requires transitive eu.dariolucia.jsatdecode.api`, `requires transitive eu.dariolucia.jsatdecode.decoder.base`, `requires jradio` |

Core has no dependency on the shared decoder library or either mission module.
`requires transitive` makes API contracts readable to consumers of core, the
shared library and mission decoders. A consumer that uses jradio types itself must
declare its own `requires jradio`; jradio is not part of the exported decoder
API. Core's exported Swing panel API extends Java desktop types, so
`java.desktop` is transitive. Modular launches require `java.desktop` and
`java.xml` to be present in the runtime, including headless decoding runs.

Mission decoder tests and qualification tools use core with test scope. Their
builds copy test dependencies to `target/lib` so the existing local launcher
and recording commands continue to work; core is not a published runtime
dependency of a mission decoder.

All package names below have the prefix `eu.dariolucia.jsatdecode`:

| Module | Exported packages |
| --- | --- |
| API | `api.config`, `api.decoder`, `api.distribution`, `api.frame`, `api.lifecycle`, `api.monitoring`, `api.source`, `api.taps`; `core.buffers`, `core.monitoring`; `dsp.fft`; `util` |
| Core | `core.config`, `core.registry`, `core.runtime`; `distribution.tcp`, `distribution.tcp.client`, `distribution.tcp.codec`, `distribution.tcp.server`, `distribution.zmq`, `distribution.zmq.client`; `source.file`, `source.cf32`, `source.cs16`, `source.wav`, `source.fmcomms2`; `ui.swing` |
| Shared decoder library | `decoder.base`, `decoder.base.config`, `decoder.base.dsp`, `decoder.base.monitoring` |
| Elektro-L GGAK | `elektrol` |
| Hinode | `hinode` |

Core's launcher, CLI, Inspector, dummy-decoder and synthetic-distribution
packages are encapsulated on the module path. The JVM can invoke an application
main class without exporting its package. There are no `opens` declarations;
modules use public APIs and service providers without deep reflective access.
The API retains the historical `core.buffers` and `core.monitoring` package
names for shared helpers; those packages are owned solely by API, avoiding split
packages and preserving existing imports.

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
The distribution checks also inspect all five packaged descriptors, resolve the
module graph with core as the root and service binding enabled, and launch the
packaged applications to check factory discovery and argument handling.
Independent named consumers compile and run with API alone and with the shared
library plus its required dependencies, without core. An external named
consumer also uses core's registry to run both mission decoders through service
discovery, checking DSP and lifecycle without access overrides.
The harnesses use the classpath for existing functional tests; the packaged
application checks exercise the module path. Desktop interaction and external
recording checks remain opt-in as described in the component documentation.

Publication remains controlled by the `release` profile for `api`, `core` and
`decoder_base`. Their ordinary published JARs include the module descriptors.

## Run on the module path

The [standalone distribution](../../distribution/README.md) collects the five
application modules and their runtime dependencies in `lib/`. After extraction,
its launchers run these commands, with the appropriate Java executable:

```text
java --module-path lib --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.MainLauncher --list-decoders
java --module-path lib --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.inspector.JSatInspector --help
```

Core declares the factory services it consumes. Standard launcher service
binding resolves matching provider modules from the module path, including the
mission decoders; no `--add-modules ALL-MODULE-PATH` is needed. To install another
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

Core declares `uses` for all four factory contracts:

| Service in `eu.dariolucia.jsatdecode.api` | Core provider IDs |
| --- | --- |
| `source.SampleSourceFactory` | `wav`, `cf32`, `cs16`, `fmcomms2` |
| `decoder.DecoderFactory` | `dummy` |
| `distribution.FrameDistributorFactory` | `synthetic`, `zmq`, `frame-file` |
| `distribution.MonitoringDistributorFactory` | `synthetic`, `zmq` |

Core also declares `provides` for those built-in providers. The Elektro-L GGAK
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
enforce package exports. Core and mission modules retain `META-INF/services`
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
