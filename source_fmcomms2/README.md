# JSatDecode FMComms2 source

`eu.dariolucia.jsatdecode:jsatdecode-source-fmcomms2` provides the `fmcomms2`
sample-source factory for FMComms2 and PlutoPlus IIOD devices. Its only
JSatDecode dependency is the API; it uses Java's `java.xml` and no third-party
or native libraries. It registers the provider on both the Java module path and
classpath. Source package names and configuration keys are unchanged.

Build with Java 25 and Maven from the repository root:

```text
mvn -pl source_fmcomms2 -am verify
```

The full reactor runs simulated IIOD and recording integration tests from
`integration_tests`. See [FMComms2 acquisition](../core/docs/fmcomms2-source.md)
and [Java modules](../core/docs/java-modules.md).
