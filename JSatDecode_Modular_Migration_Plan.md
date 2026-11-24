# JSatDecode — Java 25 and Modularization Migration Plan

**Status:** Implementation plan  
**Scope:** Refactoring only; `source_soapy` is **out of scope** and shall be implemented in a separate change after this migration passes all acceptance gates.

## Non-negotiable constraints

- **No functional regressions.** Preserve existing processing, timings/semantics, source/distribution IDs, monitoring, wire protocols, data formats, launch behavior, and observable outputs except changes expressly required below.
- **Existing configuration files must work unchanged.** No mandatory new keys, renamed keys, changed defaults, or migration of user configuration files.
- **Source/distribution module dependency isolation.** Every `source_*` and `distribution_*` module shall depend on **`api` only among JSatDecode project modules**. It shall not depend on `core`, `inspector`, `decoder_base`, `decoder_*`, `deployment`, another source module, or another distribution module, either directly or transitively through another JSatDecode module. External third-party libraries needed by an implementation are permitted. Any shared JSatDecode contracts, DTOs, or reusable helpers needed by these modules shall be placed in `api`, without introducing third-party dependencies into `api`. This rule applies throughout extraction and at final acceptance; no temporary exceptions.
- **No decoder implementation changes.** Do not modify decoder algorithms, processing logic, behavior, data structures, or protocol handling. For `decoder_base` and `decoder_*`, only Maven/JPMS dependency changes and strictly necessary import/package-reference updates are permitted. Preserve decoder public contracts and binary output behavior.
- Prefer **relocating existing code**, not rewriting it. Preserve Java package names where possible; prevent JPMS split packages and cyclic module dependencies.
- Each step is independently buildable, testable, and reviewable. **Do not proceed** if its acceptance gate fails. Record the baseline and compare against it at subsequent steps.
- Do not introduce SoapySDR, its bindings, or native dependencies in this migration.

## Target Maven modules

| Module | Responsibility |
|---|---|
| `api` | Public interfaces/factories, related data objects, reusable functions (including FFT and other truly generic utilities); **no third-party dependencies** and no dependencies on any JSatDecode implementation module |
| `core` | Framework runtime/orchestration, configuration loading, service discovery, CLI and applicable core UI; no concrete source/transport implementations |
| `source_fmcomms2` | Existing FMComms2/PlutoPlus source implementation |
| `source_file` | Existing WAV, CF32, CS16 file source implementations |
| `distribution_zmq` | Existing ZMQ/SatDecode distribution implementation |
| `distribution_jtdp` | Existing TCP/JTDP distribution implementation, including shared service registry if applicable |
| `inspector` | Standalone JSatInspector application and UI |
| `decoder_base`, `decoder_*` | Existing decoder modules; implementation unchanged |
| `deployment` | Former `distribution` packaging module; independent JSatDecode and JSatInspector deployment configurations |

**Dependency rule:** `api` depends on no project modules or external libraries. Every `source_*` and `distribution_*` implementation may depend on **`api` as its sole JSatDecode module dependency** (and on necessary external libraries); none may depend on `core` or on any other JSatDecode module. Decoder implementations consume `api` (and, for decoders, `decoder_base` as required). `core` and `inspector` consume public contracts and discover implementations via the established plugin mechanism, without compile-time dependence on concrete source providers. `deployment` assembles the selected modules and runtime dependencies. No circular dependencies.

## Step-by-step migration

### 1. Migrate to Java 25 LTS; establish a no-regression baseline

- Capture a **Java 21 pre-migration baseline**: successful clean build/test run, module/launcher behavior, representative source and distribution tests, decoder reference IQ outputs (frame bytes/counts/order and diagnostic statistics), and performance measurements where reproducible.
- Set Java 25 as the required JDK/runtime; update Maven compiler configuration, build/CI scripts, launchers, packaging instructions, and documentation as necessary. Change dependencies/plugins only when required for compatibility.
- Rebuild and run the same regression suite under Java 25, including JSatDecode, JSatInspector, file sources, FMComms2 hardware acquisition where available, and both distribution protocols.

**Gate:** Java 25 clean build and tests pass; no configuration/decoder changes; reference output, protocol compatibility, and practical performance remain equivalent to the captured baseline.

### 2. Extract `api`

- Move existing interfaces, service/factory contracts, associated DTOs/events/configuration types, and implementation-independent helpers (including reusable FFT) from `core` into `api`.
- Keep the module **free from all third-party dependencies**, GUI classes, device drivers, network transports, runtime orchestration, and concrete plugin implementations. Keep public package names stable where feasible.
- Update `core`, `decoder_base`, and `decoder_*` Maven and JPMS dependencies; permit only strictly necessary decoder import changes. Resolve split packages without changing public behavior.

**Gate:** `api` builds independently with JDK 25; no external dependency is required by `api`; all decoders build and produce unchanged reference results.

### 3. Extract source modules

- Move FMComms2 acquisition/control implementation to `source_fmcomms2`.
- Move WAV/CF32/CS16 readers and their shared file-source logic to `source_file`.
- Transfer factory registrations and JPMS `provides` declarations to the owning modules; retain the same source IDs, configuration syntax/defaults, stream/timestamp semantics, monitoring and lifecycle behavior.
- Ensure `source_fmcomms2` and `source_file` each depend on `api` as their **only JSatDecode module dependency**. Relocate any required shared contracts/helpers into `api`; do not introduce transitional dependencies on `core` or other project modules. Remove concrete source implementation dependencies from `core`.

**Gate:** All existing sources are discovered and usable through the same configurations; reference IQ acquisition/decoding remains unchanged. Maven dependency trees and JPMS descriptors confirm that each `source_*` module depends on no JSatDecode module except `api`.

### 4. Extract distribution modules

- Move ZMQ/SatDecode implementation to `distribution_zmq` and TCP/JTDP implementation to `distribution_jtdp`.
- Move corresponding factory registrations and JPMS `provides` declarations; keep frame/monitoring coordination, shared TCP registry, thread/queue ownership and lifecycle inside the appropriate implementation module.
- Preserve configuration keys, transport endpoints/defaults, serialized bytes, framing, message ordering and error behavior. Each `distribution_*` module must depend on `api` as its **only JSatDecode module dependency**; move shared contracts/helpers into `api` if required, never into `core` or another distribution module.

**Gate:** Both distribution implementations load through the existing discovery mechanism; protocol interoperability and outputs remain byte-compatible with baseline. Maven dependency trees and JPMS descriptors confirm that each `distribution_*` module depends on no JSatDecode module except `api`.

### 5. Expose source configuration properties *only if required*

- First assess whether existing source contracts already allow Inspector to discover/select/configure implementations generically.
- **Only if insufficient**, introduce the smallest backward-compatible API operation or companion optional capability interface to expose source-specific configuration-property descriptors (e.g., name/type/default/range/allowed values), without adding implementation classes to `api`.
- Update the descriptor to include which configuration properties can be asked to be updated **at runtime**, i.e. with the source already running. The update shall be backward-compatible.
- Update existing source providers and generic configuration consumers to honor these descriptors. Keep all existing configuration files, keys, defaults and CLI behavior valid; no changes to decoders.

Note: JSatDecode application does not require the use of this mechanism. JSatInspector/Inspector will in step 7.

**Gate:** Source selection and configuration can be implemented generically; current source behavior/configuration remains unchanged. If unnecessary, record “no API change required.”

### 6. Extract `inspector` application

- Move JSatInspector entry point, GUI, inspector-specific acquisition/recording/presentation logic and launcher resources from `core` to `inspector`.
- Preserve current Inspector functionality, recording formats, spectrum/FFT behavior and UI defaults. Keep runtime/framework responsibilities in `core`.

**Gate:** JSatInspector builds and launches independently; all existing Inspector features and outputs still work.

### 7. Make Inspector use generic source discovery/configuration

- Remove hardcoded FMComms2 construction, imports and assumptions in Inspector; discover available sources via the common source factory/registry mechanism.
- Add source selection and dynamically present applicable configuration controls using the existing API or the optional descriptors from Step 5. Ensure source switching, start/stop, cleanup, errors and acquisition/recording paths remain correct.
- Preserve FMComms2 as the effective/default selection where necessary for backward compatibility. Do not require edits to existing configuration files or change decoder behavior.

**Gate:** Inspector can select/configure at least FMComms2 and applicable file sources through the generic mechanism; FMComms2 use behaves as before; Inspector no longer imports concrete FMComms2 classes.

### 8. Rename packaging module to `deployment`

- Rename the existing Maven `distribution` module/artifact responsibility to `deployment`; avoid confusion with `distribution_zmq` and `distribution_jtdp`.
- Add **two explicit deployment configurations**: `jsatdecode` (runtime/CLI and selected decoder/source/distribution providers) and `jsatinspector` (Inspector and selected source providers, without unnecessary decoder/distribution modules).
- Update assembly descriptors, dependencies, module-path/service binding, launch scripts, Java 25 runtime requirements and release documentation. Preserve existing supported command-line behavior and configuration compatibility.

**Gate:** Both deployment artifacts are independently buildable and launchable, with correct optional provider discovery and no unnecessary native dependencies.

### 9. Full final regression and release gate

- Execute a clean multi-module Java 25 build and complete automated tests from the repository root; validate `api` dependency independence, JPMS graph, factory discovery, and both deployments. Audit POM dependency graphs and `module-info.java` for every `source_*` / `distribution_*` module: `api` must be its **only** JSatDecode module dependency, direct or transitive.
- Re-run **unchanged reference IQ recordings** through existing decoders; compare accepted frames **byte-for-byte**, counts, ordering, lock states, monitoring values and processing performance to the Step 1 baseline, accounting only for predeclared nondeterministic metrics.
- Test file and live FMComms2 acquisition where available, Inspector spectrum/recording, source selection, both ZMQ and JTDP transport interoperability, lifecycle/error handling, and backward compatibility with **unchanged legacy configuration files**.
- Audit `decoder_base`/`decoder_*` diffs: **only POM, JPMS module declarations and unavoidable imports/package references** are permitted; reject changes to decoder implementation/logic.
- Publish a short migration report with tests run, comparison results, any approved deviations, and updated module/deployment documentation.

**Final acceptance:** No unresolved regressions; all source/distribution modules depend exclusively on `api` among JSatDecode modules; no decoder implementation changes; existing configurations work unchanged; both Java 25 deployments build/launch; all providers and protocols behave as before. **Only after this acceptance** may a separate `source_soapy` implementation begin.

## Deliverables

1. Java 25-compatible multi-module repository with the module names listed above.
2. Independent `api` artifact without external dependencies; correctly isolated providers and Inspector application.
3. Separate `jsatdecode` and `jsatinspector` deployment configurations.
4. Regression evidence demonstrating equivalence to the pre-migration baseline and explicit confirmation that decoder implementations and existing configurations remain untouched.
