# JSatDecode — IQ Memory Optimisation: Three-Step Implementation Plan

**Status:** Implementation specification / quality-gate checklist  
**Repository:** https://github.com/dariol83/jsatdecode  
**Target:** Java 25, Maven, JPMS; `api`, `core`, `source_file`, `source_fmcomms2`, `inspector`, `decoder_base`, mission decoders, deployment/tests.  
**Primary invariant:** Optimise memory use **without changing functional behaviour**. Each step must pass its gate before the next begins.

## 0. Mandatory constraints (all steps)

1. **No functional changes:** Preserve decoder algorithms, IQ values and order, source settings, block indices, metadata/timestamps, frame bytes and order, counters/locks, output wire formats, recording bytes, error/overflow policies, cancellation semantics and UI behaviour. Preserve byte-for-byte deterministic output; compare non-deterministic timing separately.
2. **Preserve architecture:** `api` remains Java-SE-only with no external dependencies; `source_*`/`distribution_*` depend only on `api` among project modules; no module cycles, new native dependency or required Java version above 25. Keep JPMS services, command-line/configuration contracts and packaged applications operational.
3. **Ownership is explicit:** Published IQ is read-only; only an exclusive unpublished writable lease may mutate storage. No exposure of a mutable backing array through public read-only APIs. A consumer retains a lease *before* asynchronous enqueue, and releases exactly once after its final use. No use-after-release or premature recycling.
4. **Avoid copies, not safety:** Do not remove the existing `ComplexSampleBlock` constructor/`iq()` defensive-copy behaviour for legacy callers. Add a separate documented leased/borrowed path and migrate consumers deliberately. Copies remain where needed for compatibility, genuinely independent ownership or DSP transformations.
5. **No silent loss or unbounded growth:** Pool budgets, exhaustion actions, queue limits and metrics are explicit. Preserve existing acquisition/backpressure/failure policies; never introduce blocking into nonblocking observer/output paths or drop IQ silently to satisfy a memory target.
6. **No unrelated refactoring:** Keep patches small, tests adjacent to each change, full Javadoc for public APIs, and a separate commit/PR per step. Do not mix changes to the source-property/runtime-reconfiguration interfaces into this effort.
7. **No fabricated evidence:** Record the exact Git commit, OS/JDK, JVM options, test data hashes and commands. An unrun or unavailable test is **NOT PASS**.

## Step 1 — Baseline, ownership model and opt-in buffer pool

**Goal:** Introduce and prove safe memory ownership *without changing production processing behaviour*.

### Implement

- Audit the active source → optional rotator → decoder and Inspector/recorder paths. Inventory allocation/copy sites, block sizes and asynchronous retention points. Capture a pre-change JFR allocation/GC profile, peak heap and replay throughput under a recorded, reproducible configuration.
- Add to `api` a small bounded `IqBufferPool` + closeable `IqBufferLease` (names illustrative; follow existing naming conventions). Use reusable interleaved `float[]` (`I,Q`) size classes, valid-length/offset metadata, explicit state and deterministic last-owner release. Pool budget must cover **retained + checked-out** buffers, not merely idle arrays.
- Support **exclusive writable acquisition** and **read-only publication**. Consumers may borrow synchronously; asynchronous consumers must obtain separate retained read-only leases. Recycle only at zero outstanding leases. Use a read-only/indexed or controlled scoped-access interface, not an unrestricted `float[]` getter. No writer may mutate data while read-only leases exist.
- Preserve legacy `ComplexSampleBlock` behaviour; provide an explicit adapter/boundary for old sinks. Do **not** switch existing sources/decoders yet. Add pool instrumentation: allocations, reuses, outstanding leases/bytes, peak active/retained bytes, exhaustion, invalid releases.
- Define deterministic exhaustion behaviour and capacity accounting before integration. Pool internals must avoid per-sample allocation, remain thread-safe, and not rely on GC/finalizers for correctness.

### Gate G1 — Must all pass

- [ ] `mvn clean verify` and `mvn -pl api -am verify` pass with **zero existing test regressions**; JPMS/dependency constraints remain intact.
- [ ] New tests pass: ownership transfer; concurrent retain/release; last-reference recycling; duplicate close; access-after-close; attempts to write after publication; errors during acquisition; cancellation/shutdown; capacity-class reuse; exact valid-length/offset; pool exhaustion; no buffer aliasing between active leases.
- [ ] Pool shows **zero outstanding leases** after every completed test and failure/cancellation path; no race, data corruption, deadlock or unexpected buffer reuse under stress (repeat concurrent tests with fixed seeds).
- [ ] Unchanged packaged Hinode/Elektro-L replay, recording/distribution and Inspector regression tests yield exactly the **pre-change** deterministic bytes, frames, sample/block counts and metadata. No changes to production-path performance or semantics beyond measurement noise.
- [ ] A baseline evidence bundle exists: commit/hash manifests, JFR/GC profiles, regression logs and explicit inventory of remaining copy sites.

**STOP if G1 fails.** No migration of production sinks or sources is allowed.

## Step 2 — Zero-copy acquisition, runtime and Inspector lifetime integration

**Goal:** Remove unnecessary whole-block copies at the source/runtime boundary and safely retain buffers for asynchronous recording.

### Implement

- Migrate `source_file` (CF32/WAV/CS16) and `source_fmcomms2` to fill pooled writable buffers, publish read-only leased blocks, and invoke the synchronous sink without copying. Keep sample conversion (`CS16 → float32`) numerically identical. Retain existing exact stream indexing, block boundaries, timestamp semantics, repeat/EOF and acquisition failure handling.
- Adapt `core` runtime and optional frequency rotator to accept read-only leased input. Zero offset passes the same read-only storage through; nonzero offset writes into a **separate pooled destination** (preserve phase/state/timestamp). Do not overwrite input that another observer may still read. Legacy sink adapters retain defensive copying where required.
- In `inspector`, borrow samples for synchronous analysis and **retain a lease before enqueueing** asynchronous CF32/CS16 recording; release in writer `finally`, on completed writes, rejected offers, queue purge, stop and failures. Preserve recorder queue capacity and overflow behaviour, CS16 conversion, output byte order and file contents.
- Ensure source stop/close cannot reclaim buffers held by a recorder or another asynchronous consumer; drain/release outstanding work according to existing lifecycle policy. Preserve source-only recording before runtime frequency translation.
- Add targeted counters to demonstrate avoided copies and reused buffers, without allocations in hot loops. Do not modify mission DSP code in this step.

### Gate G2 — Must all pass

- [ ] Full reactor `mvn clean verify`, `source_file`, `source_fmcomms2`, `core` and `inspector` tests pass; both independent deployment profiles build/launch with unchanged source discovery and configuration.
- [ ] Byte-exact **input-output IQ** comparison across CF32, WAV/RF64 and CS16, including partial/zero-length blocks, range/repeat, channel ordering, endianness, scale, timestamps/indices (excluding documented run-dependent time anchors), and source-recording files.
- [ ] With the rotator disabled, no full-IQ copy occurs along the **migrated leased** source-to-decoder handoff. With the rotator enabled, its new output matches the baseline numeric/bit-exact contract established before migration; sample count, phase continuity, offset sign and metadata match.
- [ ] Inspector recording: CF32 and CS16 files are byte-for-byte identical to baseline for equivalent input; stop-during-write, full queue, enqueue rejection, writer I/O exception, source failure, rapid start/stop and cancellation tests pass. No buffer is recycled until the last recorder read finishes.
- [ ] Simulated IIOD acquisition, disconnect/recovery and runtime configuration updates pass. For a production release involving physical FMComms2, complete a real Pluto/PlutoPlus smoke test (live acquire + recording + stop/restart); if hardware is unavailable, record **RELEASE GATE OPEN**, not PASS.
- [ ] Production replay of **both** mission decoders remains byte-identical, with unchanged deterministic monitoring, frame accounting and output transport fixtures (ZMQ/JTDP). Pool outstanding leases = 0 after final drain/close in all normal/error tests.
- [ ] Allocation profile confirms removal of at least one *identified* whole-block allocation/copy from the migrated path; no throughput regression beyond documented benchmark noise or memory-budget breach.

**STOP if G2 fails.** Roll back the affected migration path rather than weakening ownership or compatibility guarantees.

## Step 3 — Decoder scratch reuse, final optimisation and release qualification

**Goal:** Eliminate remaining *proven* hot-loop allocations in decoder DSP while retaining bit-exact outputs and stable memory usage.

### Implement

- Profile `decoder_base`, `decoder_hinode`, `decoder_elektrol_ggak`, taps and jradio interfaces. Optimise **only measured hot spots**: reuse per-decoder FIR/PLL/Costas/timing/FEC scratch arrays; replace subrange copies with bounded views; avoid per-block temporary objects and boxing; use pooled destination buffers only where a transformed block is actually required.
- Keep DSP state/arrays confined to their decoder instance/worker; never share stateful scratch across concurrent decoders. Avoid in-place changes to published source/tap arrays, preserve filter histories and exactly the same numerical operation order where outputs are compared bitwise.
- Keep existing bounded tap/FFT primitives and monitoring architecture unless profiling demonstrates a regression-free improvement. Do not prioritise UI allocations over DSP correctness.
- Document retained pool capacity, exceptional large-block treatment, outstanding-lease diagnostics, shutdown checks and production monitoring. Perform before/after allocation, GC, peak-memory and throughput comparisons on the *same* reference workloads.

### Gate G3 — Final release gate; must all pass

- [ ] `mvn clean verify`, module-isolated checks, package/JPMS/service-discovery checks and both deployment launch tests pass from a clean checkout.
- [ ] Deterministic regression matches Step-0 baseline **exactly**: ordered accepted frame payloads/bytes, rejected/total counts, decoded metadata, status/lock values, sample/block counts and distribution fixtures. Do not accept “approximately the same frame count.”
- [ ] Run the repository's existing private-capture replay harnesses when the reference inputs are present. Historical reference evidence (only valid if the input SHA-256 matches): Hinode **45,023,232 samples / 309 accepted / 81 bad / 390 candidates**; Elektro-L **103,965,627 samples / 273 accepted / 11 bad / 284 candidates**. Verify ordered-output hashes against the **captured local pre-change baseline**, not just those counts.
- [ ] Inspector spectrum/constellation/waterfall behaviour, UI configuration and source switching, live updates, recording, cancellation and file-close behaviour pass the existing automated/desktop acceptance tests.
- [ ] Concurrency/lifecycle stress: repeated start/stop, EOF, source/distributor/recorder failure, pool exhaustion and queue saturation yield **no leaked leases, double-release, use-after-release, deadlock, changed error policy or unreported sample loss**.
- [ ] Under matched steady-state workloads and measured after warm-up, **IQ-processing allocation rate and GC allocation pressure are lower than baseline**; peak in-use bytes stay within the configured cap; throughput is not materially worse. Report measured values and raw evidence; set an explicit performance threshold from Step-1 baseline before implementation, not retrospectively.
- [ ] Production-release live-hardware validation passes for affected physical source paths; otherwise clearly label that part **not validated** and do not certify it as released.
- [ ] Public APIs, Javadoc, module descriptors, developer docs, pool lifecycle diagrams, tests and rollback notes are updated. Every accepted optimisation has a documented before/after allocation or copy reduction.
- [ ] Documentation update: document how decoder implementations, source implementations and distribution implementations must deal with the introduced memory model and lifecycle for IQ blocks. Update the architecture.md and implementation-guide documents. Update the decoder-implementation-specification accordingly, to include the authoritative specification there for decoders.

**Release only when G1, G2 and G3 are PASS.** Missing evidence, unexplained changed output, flaky tests, sample loss, ownership uncertainty or unreleased leases are unconditional **NO-GO** conditions.

## Validation evidence and rollback protocol

At each step, attach one short report containing: `baseline_commit`, `candidate_commit`, Java/Maven/OS, replay input SHA-256, exact commands, automated/desktop/hardware results (`PASS` / `FAIL` / `NOT RUN`), ordered frame hashes, recording hashes, monitoring diff, outstanding leases, JFR allocations (bytes/s), GC count/pause, peak heap and replay throughput, plus known limitations.

**Comparison policy:** Use identical immutable input data, equivalent CPU/JVM settings, a warm-up phase and repeated benchmark runs. Keep functional comparisons strict; treat wall-clock-derived telemetry and throughput as separately documented nondeterministic measurements.

**Rollback:** Each step stays separately revertible. If a gate fails, retain evidence, revert that step, rerun the last passing full suite and resume only after the defect is explained and a new candidate passes. Never fix a failed gate by changing reference recordings, reducing test coverage, weakening copy/immutability contracts, or adjusting acceptance criteria retroactively.

## Reference repository documentation

- [Repository and build](https://github.com/dariol83/jsatdecode)
- [Architecture: data ownership, lifecycles, buffering](https://github.com/dariol83/jsatdecode/blob/master/core/docs/architecture.md)
- [API contracts](https://github.com/dariol83/jsatdecode/blob/master/api/README.md)
- [Inspector module](https://github.com/dariol83/jsatdecode/blob/master/inspector/README.md)
- [Existing Java 25 migration regression and replay evidence](https://github.com/dariol83/jsatdecode/blob/master/regression/step-9-final-migration.md)

> **Evidence scope:** This plan is based on the public repository's documented architecture and regression evidence. Before editing code, Step 1 must verify actual call sites and baseline behaviour against the checked-out commit; documentation alone is not a source-level allocation audit.
