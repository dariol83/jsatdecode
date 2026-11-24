# JSatDecode file sources

`eu.dariolucia.jsatdecode:jsatdecode-source-file` provides the `wav`, `cf32`
and `cs16` sample-source factories. Its only JSatDecode dependency is the API;
it needs no third-party libraries. It registers providers on both the Java
module path and classpath. Source package names and configuration keys are
unchanged.

Build with Java 25 and Maven from the repository root:

```text
mvn -pl source_file -am verify
```

The full reactor runs file-source and cross-module integration checks. See
[file-source formats and configuration](../core/docs/file-sources.md) and
[Java modules](../core/docs/java-modules.md).
