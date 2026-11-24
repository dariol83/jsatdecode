# JSatDecode JTDP distribution

`eu.dariolucia.jsatdecode:jsatdecode-distribution-jtdp` provides the `tcp`
frame and monitoring outputs, their shared listener registry, JTDP codecs and
reference client. It registers factories on the Java module path and classpath.
Its only JSatDecode dependency is API, and it has no third-party dependencies.
Existing Java packages, configuration keys, endpoints and wire format are retained.

Build with Java 25 and Maven from the repository root:

```text
mvn -pl distribution_jtdp -am verify
```

The full reactor runs cross-module and protocol checks. See the
[TCP interface](../core/docs/tcp-distribution-interface.md) and
[Java modules](../core/docs/java-modules.md).
