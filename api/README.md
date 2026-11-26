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

### Opt-in IQ buffer ownership

`IqBufferPool` and `IqBufferLease` provide explicitly leased storage. The file
and FMComms2 sources, runtime and Inspector use this path when both sides opt in.
Legacy `ComplexSampleBlock` construction and `iq()` continue to make defensive
copies, and a legacy sink receives its own independent block. The pool budget
covers the float payload of both checked-out and retained idle arrays.
Power-of-two capacity classes are charged in full; idle classes may be evicted
to make room. `tryAcquire` returns empty rather than waiting for capacity, and
allocation errors propagate. Array/object overhead and evicted arrays awaiting
garbage collection are outside the payload budget.

An acquired lease is exclusively writable until `publish(offset, count)`
changes that same lease to read-only. Both values are in complex samples;
indexed reads start at the valid window, and only initialized samples may be
published. Reused storage has unspecified contents. A synchronous consumer may
borrow a published lease during a call. Any asynchronous consumer must retain a
separate lease **before** enqueueing it and close that lease on completion,
rejection, cancellation, or failure. Every lease must be closed exactly once;
duplicate closes fail and increment `invalidReleases`. Closing a pool prevents
new acquisitions and discards idle arrays, while outstanding leases stay valid
until their own last close. Scoped access is valid only on the callback thread
and only until that callback returns; the backing array is never exposed.

```java
try (IqBufferPool pool = new IqBufferPool(32_768);
     IqBufferLease lease = pool.tryAcquire(4096).orElseThrow()) {
    lease.withWriteAccess(samples -> {
        for (int n = 0; n < 4096; n++) samples.put(n, 0.0f, 0.0f);
    });
    lease.publish(0, 4096);
    try (LeasedSampleBlock block = LeasedSampleBlock.from(lease, 0, timestampNanos, sampleRate)) {
        leasedSink.accept(block); // borrows until accept returns
    }
}
```

`LeasedSampleSource` installs a synchronous borrowed-block sink. A
`LeasedDecoder` consumes the same block without copying; the runtime adapts
other decoders through the legacy copy boundary. A consumer uses
`block.withView(...)` for synchronous indexed reads. Before an asynchronous
handoff, it calls `block.retain()`, publishes that independently closeable
claim, and closes it exactly once after the final read or rejection. Retaining
a block and rejecting it while the producer still owns the borrowed block use
atomics without taking the pool monitor. A retained block may outlive its source.
A retain racing a close can succeed or fail; a successful claim remains valid
independently of the closed block.

Pool statistics distinguish underlying pool leases, distinct active array bytes,
idle retained bytes, peak values, allocations, reuse, exhaustion and invalid
releases. Several retained `LeasedSampleBlock` claims share one underlying pool
lease, which remains outstanding until the final claim closes. Source
`leasedStatistics()` reports successful leased deliveries and pool accounting;
read it on a management thread because obtaining the snapshot takes the pool
lock. File-source budgets allow 128 MiB for size-class rounding in Inspector's
64 MiB queue plus three maximum blocks. FMComms2 adds twice its configured
source-recording queue budget, 128 MiB for Inspector and four maximum blocks.
Inspector decimation has a 152 MiB output pool; runtime rotation has a 256 MiB
pool. These are payload caps, allocated lazily. Exhaustion fails acquisition
explicitly; no block is silently dropped.

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
