# Migrating existing decoders to leased IQ

This guide explains how to adapt an existing JSatDecode decoder to the opt-in
IQ memory model while preserving its behavior. The
[decoder implementation specification](decoder-implementation-specification.md)
remains authoritative; see the [API ownership guide](../../api/README.md#opt-in-iq-buffer-ownership)
and [architecture](architecture.md#data-ownership-and-time) for the complete
contracts and pool budgets.

Migration is optional. An unchanged decoder still receives an independently
owned `ComplexSampleBlock`, including when the source uses pooled IQ. Migrating
allows compatible sources and the runtime to deliver a borrowed block without
the defensive whole-block copy at the decoder boundary. It does not change the
modulation, DSP, frame protocol, configuration or lifecycle of the decoder.

## 1. Establish the baseline

Before changing a decoder, record its source revision, Java version,
configuration and input hashes. Run its existing automated tests and replay
representative recordings. Save ordered frame payloads, frame metadata,
accepted/rejected counts, sample/block counts, deterministic monitoring and
lock values. Record throughput, heap, allocation and GC observations using a
repeatable warmed workload. These results are the comparison baseline; do not
replace exact frame comparisons with frame-count comparisons.

Inventory the input path before editing it:

- Find calls to `ComplexSampleBlock.iq()`, which returns a new array, and any
  later copies, array mutations, queues and callbacks that retain samples.
- Identify the worker that owns each DSP/filter history, FEC buffer and tap
  buffer. Note whether the decoder hands input to jradio or another library
  that retains references or requires a mutable array.
- Record behavior for zero-length and partial blocks, block boundaries, sample
  indices, UTC timestamps, invalid samples, sink errors, cancellation and EOF.
- Check the decoder's existing queue bounds, overflow policy and stop/drain
  order. Do not silently change them during migration.

## 2. Keep both input paths

`LeasedDecoder` extends `Decoder`; implementing it adds `acceptLeased` and does
not replace `accept(ComplexSampleBlock)`. The runtime selects the path from
the capabilities of both components:

| Source | Decoder | Runtime delivery |
| --- | --- | --- |
| Legacy | Legacy or leased | Calls `accept` with a legacy immutable block. |
| Leased | Legacy | Adapts the leased block to an independent `ComplexSampleBlock`; this copies the valid IQ window. |
| Leased | Leased | Calls `acceptLeased` with a synchronously borrowed block. |

With zero output-frequency offset, the leased runtime can forward the source
block directly. A nonzero offset produces a distinct pooled output for the
decoder. Source-owned recording still sees the original samples before runtime
translation. No decoder should assume that all sources or all deployments use
the leased path.

An existing decoder that extends `AbstractDecoder` can add
`implements LeasedDecoder`. A decoder that implements `Decoder` directly can
implement `LeasedDecoder` instead. Keep the existing factory and service
registration. The new interfaces are in the existing API module; ensure the
deployed API version provides them, but do not add a new module export or
configuration switch solely for this migration.

## 3. Extract a common synchronous processing method

Both legacy blocks and leased callback views implement `IqSampleView`. Move the
existing validation, processing, monitoring updates, exception handling and
tap flush into one method using that interface. The following is an excerpt
inside an existing decoder; `processSample` represents its current DSP entry
point and is not a new framework method.

```java
import eu.dariolucia.jsatdecode.api.decoder.LeasedDecoder;
import eu.dariolucia.jsatdecode.api.source.ComplexSampleBlock;
import eu.dariolucia.jsatdecode.api.source.IqSampleView;
import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;

@Override
public void accept(ComplexSampleBlock samples) {
    acceptSamples(samples);
}

@Override
public void acceptLeased(LeasedSampleBlock samples) {
    samples.withView(this::acceptSamples);
}

private void acceptSamples(IqSampleView samples) {
    // Keep the existing lifecycle/rate checks and failure handling here.
    int count = samples.complexSamples();
    for (int n = 0; n < count; n++) {
        float i = samples.i(n);
        float q = samples.q(n);
        processSample(i, q); // Existing decoder-specific processing.
    }
    // Keep existing tap flush and monitoring publication here.
}
```

Preserve the original order of numerical operations, validation, timestamp
derivation and frame delivery. Use `sampleIndex()`, `timestampNanos()` and
`sampleRate()` from the view where the old code used the block metadata. The
sample index is the first complex sample's stream position; timestamps are UTC
epoch nanoseconds. Access only `complexSamples()` valid pairs, including when
the final block is shorter than the source's configured block size.

The view is read-only and valid only on the callback thread until `withView`
returns. Do not save it in a field, return it, send it to an executor, pass it
to a library that retains it, or use it on another thread. Do not close the
borrowed `LeasedSampleBlock`: its caller owns that claim. An independently owned
legacy `ComplexSampleBlock` retains its existing lifetime and defensive-copy
contract.

The built-in [Hinode decoder](../../decoder_hinode/src/main/java/eu/dariolucia/jsatdecode/hinode/HinodeDecoder.java)
and [Elektro-L decoder](../../decoder_elektrol_ggak/src/main/java/eu/dariolucia/jsatdecode/elektrol/ElektroLGgakDecoder.java)
show this common-method pattern with their own validation and DSP state.

## 4. Adapt array-based processing safely

`IqSampleView` has primitive indexed `i(n)` and `q(n)` accessors; it does not
expose the backing array or an `iq()` method. Prefer these accessors when the
existing DSP can consume pairs directly. Do not create a temporary object or
array for every sample or symbol.

If a library requires a mutable interleaved array, copy only the valid prefix
into decoder-owned scratch and pass its valid length to that library. Reuse
bounded scratch only when its lifetime, size and threading are compatible with
the library. If an API has no offset/length parameter and requires an exact
array length, do not pass a larger buffer containing stale tail samples; use an
appropriate exact-length adapter or retain the required copy. Never mutate a
published source block or an observed tap buffer. A transformed output that
must be published or retained needs its own storage.

Keep FIR histories, PLL/Costas/timing state, FEC scratch and other mutable
buffers confined to one decoder instance and its serialized processing worker.
Keep state across arbitrary block partitions. Profile before optimizing and
only change measured hot spots; do not change algorithm order or replace a
library FEC implementation without separate bit-exact qualification.

## 5. Handle asynchronous consumers explicitly

Synchronous processing is the simplest migration. If the existing decoder
already queues input or otherwise needs it after `acceptLeased` returns, retain
an independent claim **before** handoff. Open a fresh view on the worker thread,
then close that claim exactly once after its last read.

The following excerpt assumes `queue` is bounded and its admission is already
coordinated with decoder stop. `onQueueFull()` stands for the decoder's existing
documented overflow policy, including its error or loss accounting.

```java
private void enqueueBorrowed(LeasedSampleBlock borrowed) {
    LeasedSampleBlock claim = borrowed.retain();
    boolean handedOff = false;
    try {
        if (!queue.offer(claim)) {
            onQueueFull();
            return;
        }
        handedOff = true; // The queue/worker now owns this claim.
    } finally {
        if (!handedOff) claim.close(); // Also covers an offer/policy exception.
    }
}

private void processQueued(LeasedSampleBlock claim) {
    try (claim) {
        claim.withView(this::acceptSamples);
    }
}
```

This excerpt is not a complete queue implementation. On rejection,
cancellation, worker failure or shutdown, close every claim that was retained
but will not reach `processQueued`. Coordinate admission with stop so a claim
cannot be enqueued after the final drain. A cancelled queued task may never run
its `finally` block. Preserve sample order and ensure all legacy and leased work
that shares DSP state is serialized on the same owner; do not accidentally
process the two paths concurrently. A retained claim remains usable after the
source closes its pool, but pool closure does not release that claim for you.

## 6. Verify equivalence and ownership

Use separate, identically configured decoder instances for the legacy and
leased paths so DSP histories begin in the same state. Install equivalent frame
sinks and start both before delivery. For a synchronous decoder, the core of a
small paired-input test can look like this:

```java
float[] iq = {0.25f, -0.5f, 0.75f, 0.125f};
int count = iq.length / 2;
long index = 0;
long timestampNanos = 1_700_000_000_000_000_000L;
double rate = 500_000;

legacy.accept(new ComplexSampleBlock(iq, count, index, timestampNanos, rate));
IqBufferPool pool = new IqBufferPool(4096);
try (pool; IqBufferLease writer = pool.tryAcquire(count).orElseThrow()) {
    writer.putInterleaved(iq, 0, 0, count);
    writer.publish(0, count);
    try (LeasedSampleBlock block = LeasedSampleBlock.from(writer, index, timestampNanos, rate)) {
        migrated.acceptLeased(block);
    }
}
// After any asynchronous consumers have drained, assert the pool has no leases.
assert pool.statistics().outstandingLeases() == 0;
// Compare the two decoders' captured frames, metadata and monitoring values.
```

This excerpt also needs the `IqBufferPool` and `IqBufferLease` imports. In a real
test, use the test framework's assertions rather than relying on Java assertions
being enabled. Close both decoders and any captured resources. For asynchronous
processing, wait for the worker and all queues to drain before comparing outputs
or asserting zero outstanding leases.

Cover at least:

1. Both paths with identical IQ bits and metadata, including zero/partial
   blocks, different partitions of the same stream, timestamps, sample-rate
   checks and the existing invalid-input behavior.
2. Exact ordered frame bytes, annotations, accepted/rejected/total counts,
   deterministic monitoring and lock states, tap contents and lifecycle
   transitions. Compare the pre-change baseline on the same recordings;
   normalize only values that are inherently run-dependent.
3. Scoped-view misuse, multiple retained claims, concurrent retain/close,
   callback failure, queue-full rejection, cancellation, worker failure,
   EOF, stop and pool shutdown. Verify claims survive source shutdown when
   retained and all underlying leases reach zero after consumers drain.
4. Legacy and leased sources, plus zero and nonzero runtime frequency offsets
   where supported. Verify source recording remains pre-translation and
   decoded output preserves the established behavior.
5. Independent simultaneous decoder instances, bounded tap/observer behavior,
   factory discovery, module-path packaging and existing distribution fixtures.

For source-pool diagnostics, obtain `LeasedSampleSource.leasedStatistics()` on
the management thread after acquisition has started. Its pool snapshot includes
allocated, active and retained payload accounting, peaks, exhaustion, invalid
releases and outstanding **underlying leases**. Multiple retained block claims
can share one underlying lease. A nonzero count immediately after source stop
can be legitimate while a recorder or decoder worker is draining; it must reach
zero after all owners finish. Source statistics do not include separate runtime
rotator or Inspector pools and are not automatically added to normal monitoring.

Run the relevant module and full reactor checks from the repository root. For
the existing mission decoders, for example:

```text
mvn -pl decoder_hinode -am verify
mvn -pl decoder_elektrol_ggak -am verify
mvn clean verify
mvn -pl deployment -am -Pjsatdecode verify
mvn -pl deployment -am -Pjsatinspector verify
```

Use the decoder's own reference recordings and acceptance criteria; the
[Step 3 evidence](../../regression/iq-memory-step3.md) demonstrates exact
Hinode and Elektro-L replay comparisons, allocation measurements and their
limits. Recorded replays and simulators do not validate physical hardware; run
the applicable live-source and recording checks before a hardware release.

## 7. Measure and roll back safely

Measure baseline and migrated code with the same immutable inputs, JVM, heap
settings and machine, using warm-up and repeated runs. Set the acceptable
throughput threshold before changing the decoder. Report allocation and GC
results, sampled peak heap, pool caps/peaks, outstanding claims and throughput;
distinguish exact counters from JFR's sampled allocation estimates. A lower
allocation rate does not by itself prove fewer GC pauses. Preserve raw evidence
and investigate any changed output, leaked claim or unreported sample loss.

If equivalence or lifecycle checks fail, revert the decoder migration as an
independent change and rerun the last passing tests and replay baseline. The
legacy `accept` path remains available. Do not weaken immutability, enlarge
queues without bounds, discard failures or change reference inputs to make a
migration pass.
