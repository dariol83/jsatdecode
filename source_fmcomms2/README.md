# JSatDecode FMComms2 source

`eu.dariolucia.jsatdecode:jsatdecode-source-fmcomms2` provides the `fmcomms2`
sample-source factory for FMComms2 and PlutoPlus IIOD devices. Its only
JSatDecode dependency is the API; it uses Java's `java.xml` and no third-party
or native libraries. It registers the provider on both the Java module path and
classpath. Source package names and configuration keys are unchanged.

The factory exposes property descriptors for generic editors. Frequency, RF
bandwidth and gain settings support explicit runtime updates through the optional
`RuntimeConfigurableSampleSource` capability. Management uses a separate IIOD
control connection; acquisition retains sole ownership of its streaming socket.
Other settings require reconnecting. See the source guide for failure semantics.

Build with Java 25 and Maven from the repository root:

```text
mvn -pl source_fmcomms2 -am verify
```

The full reactor runs simulated IIOD and recording integration tests from
`integration_tests`. See [FMComms2 acquisition](../core/docs/fmcomms2-source.md)
and [Java modules](../core/docs/java-modules.md).
