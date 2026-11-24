# JSatDecode ZMQ distribution

`eu.dariolucia.jsatdecode:jsatdecode-distribution-zmq` provides the `zmq`
frame and monitoring outputs, the `frame-file` recorder and the SatDecode
reference client. It registers factories on the Java module path and classpath.
Its only JSatDecode dependency is API; it uses JeroMQ for network transport.
Existing Java packages, configuration keys, endpoints and wire format are retained.

Build with Java 25 and Maven from the repository root:

```text
mvn -pl distribution_zmq -am verify
```

The full reactor runs cross-module and protocol checks. See the
[ZMQ interface](../core/docs/zmq-distribution-interface.md),
[frame recording guide](../core/docs/frame-recording.md) and
[Java modules](../core/docs/java-modules.md).
