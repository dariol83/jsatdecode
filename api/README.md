# JSatDecode API

`eu.dariolucia.jsatdecode:jsatdecode-api:0.1.0-SNAPSHOT` supplies the public
source, decoder, distribution, frame, configuration, monitoring and tap
contracts. It also contains reusable FFT, spectrum, UTC, bounded tap and metric
utilities. It depends only on the Java 25 platform and has no third-party or
JSatDecode module dependencies.

Its JPMS name is `eu.dariolucia.jsatdecode.api`. Existing Java package names are
preserved, including the historical `core.buffers` and `core.monitoring`
packages for shared tap and metric helpers. Core requires the API transitively;
decoder modules use the API and shared decoder library without a production
dependency on core. See [Java modules](../core/docs/java-modules.md).

Sources may implement `ManagedSampleSource` for nonblocking cancellation and
failure reporting. A factory may also implement `DiagnosticSampleSourceFactory`
for a bounded, count-only read diagnostic. These capabilities let Inspector use
source providers through the source service while retaining diagnostic behavior.

`SampleSourceFactory.configurationProperties()` optionally returns ordered
`SourcePropertyDescriptor` values: the existing unprefixed key, label, type, unit,
default, required flag, inclusive bounds, choices and runtime-update capability.
The default method returns an empty list for older providers. Descriptors are
metadata for editors; factory parsers still validate complete configurations,
conditional settings and hardware limits. No defaults are injected into CLI
configurations. The identity-only `SourceDescriptor` contract is unchanged.

Sources advertising live properties implement `RuntimeConfigurableSampleSource`.
Its `updateConfiguration(Configuration)` accepts only changed, advertised keys
on a running source, retaining omitted values. Owners serialize updates with
lifecycle calls on a management thread. Validation precedes I/O; implementations
document recovery from partially applied hardware requests. Inspector uses the
same contracts for source selection, configuration and live updates.

Distribution providers can register a `DistributionConfigurationNormalizer` to
validate shared aliases before components are created. A monitoring output can
implement `InitialMonitoringSnapshot` to request the runtime's initial snapshot.
The reusable `AsyncOutput` mailbox provides bounded, worker-owned delivery.

`ApplicationLauncher` supplies optional application entry points without GUI
types or implementation dependencies. Inspector registers the `inspector` ID
so core can preserve its `--inspector` selector without depending on that
application module. Registration uses both JPMS and `META-INF/services`.

Build and run the standalone API checks with JDK 25 and Maven:

```text
mvn -f api/pom.xml clean verify
```

The API module runs the existing bounded-observation and numerical FFT test
harnesses without implementation modules or third-party libraries.
