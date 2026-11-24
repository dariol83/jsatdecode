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
the FMComms2 provider through the source service while retaining its current
radio configuration and diagnostic behavior.

Build and run the standalone API checks with JDK 25 and Maven:

```text
mvn -f api/pom.xml clean verify
```

The API module runs the existing bounded-observation and numerical FFT test
harnesses without implementation modules or third-party libraries.
