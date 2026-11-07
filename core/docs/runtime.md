# Runtime and CLI

`source.type=fmcomms2` selects the native Java IIOD network source. See
[fmcomms2-source.md](fmcomms2-source.md) and examples/fmcomms2-dummy.properties.
Startup configures the selected RF receiver and opens its stream; disconnects,
protocol errors and timeouts are failures rather than normal EOF/reconnect.

For FMComms2, `source.recording.enabled=true` also records received IQ as CF32;
see [IQ recording](iq-recording.md). ConfigurationLoader defaults
`application.configuration_name` to the properties-file stem (or `session`);
runtime supplies it to this source unless `source.recording.name` overrides it.
Other source factories receive their source sections without this identity injection.
Source completion/stop includes IQ-writer drain and file close.

Decoder status follows [decoder-implementation-specification.md](decoder-implementation-specification.md)
and the public DecoderStatusKeys constants. All ten decoder metrics remain
available through the lifecycle. Runtime/distributor delivery and loss counters
are separate from accepted/rejected decoder frames. The dummy decoder publishes
UNKNOWN locks, NaN SNR/offset/symbol rate and counts of generated integration
records; it does not claim RF/FEC lock or validation.

`--store <folder>` (or global `storage.folder`) adds an independently queued
disk recording alongside the selected frame distributor. Missing directories are
created; files are named `<decoder>_yyyy_MM_dd_HH_mm_ss.frame.bin` at processing
startup. Topic, CBOR metadata and payload match the ZMQ multipart format, with
big-endian length prefixes for disk boundaries. See
[frame-recording.md](frame-recording.md) for layout, reading, lifecycle and failures.

Build with `mvn verify` from the repository root. From an extracted
[distribution](../../distribution/README.md) on Windows:

~~~text
bin/jsatdecoder.bat --list-sources
bin/jsatdecoder.bat --list-decoders
bin/jsatdecoder.bat --config examples/core/dummy.properties --set source.file=C:/recordings/input.cf32 --headless
~~~

Configuration is UTF-8 Java properties. Precedence is defaults, file, repeated
`--set key=value` overrides, then dedicated `--headless`/`--ui`, `--store` and `-v` switches.
The last occurrence within a level wins. Values may contain additional equals
signs. Relative paths use the process working directory. Use forward slashes or
Java-properties escaping for Windows paths.

Source and decoder types are required for decoding runs. Defaults select synthetic frame and
monitoring outputs and disable UI. --ui enables the optional Swing observer;
--ui-demo runs synthetic monitoring without source/decoder configuration.
Help, version and listings require no configuration. Unknown options,
missing values, unknown IDs and duplicate IDs fail before execution.
All four factory contracts use ServiceLoader; IDs are unique within a contract.
Embedders can also supply explicit FactoryRegistry providers and configured
component lists to JSatDecodeRuntime.

## Source output frequency translation

Set `source.output_frequency_offset` to translate source IQ before it enters
the decoder's DSP. This works with every source, including WAV/CF32 replay and
live receivers using offset tuning:

~~~properties
# Move a signal at +25 kHz relative to the source center to DC.
source.output_frequency_offset=-25000
~~~

The optional value is a finite signed number in Hz; fractional values are
supported. Absent, `0` and `-0.0` install no rotator: the source connects directly
to the decoder and preserves the original sample bits. Malformed/nonfinite
values fail configuration before resources are created.

For a nonzero offset `f`, the runtime inserts a synchronous complex rotator:
`y[n] = x[n] * exp(+j*phase[n])`, with phase increment `2*pi*f/Fs` per sample.
Positive values shift frequencies upward; negative values shift them downward.
The first delivered sample has phase zero. Phase continues across blocks and
file repeats, including when recording indices rewind. Each block supplies its
actual source **output** sample rate `Fs`; WAV uses its header and FMComms2 uses
the rate after native FPGA decimation. A rate change updates the increment
without resetting phase. Sampled-frequency shifts alias modulo `Fs`.

Sample count, block boundaries, recording indices, UTC timestamps and sample
rate are retained. The serial source caller owns rotation and reusable scratch
bounded by its largest block; downstream blocks remain immutable and retainable.
Decoder RF/baseband/symbol taps observe the translated stream at their usual
positions; decoder carrier-offset measurements are relative to that input.
For an absolute RF axis on the translated tap, set
`ui.spectrum.rf_center_frequency_hz` to the acquired center minus the configured
offset. Source tuning/center metadata and source-owned IQ recordings retain
the acquired signal before this runtime translation. When replaying such a
recording, retain the offset needed for the original live decoder input.

`JSatDecodeRuntime.create` consumes this setting before passing the remaining
unprefixed source section to its factory. Direct source factories produce raw
source samples; the constructor accepting already configured runtime components
retains its direct source/decoder connection.

## Verbose monitoring

Add `-v` to a decoding command to enable monitoring logs, with or without the UI:

~~~text
bin/jsatdecoder.bat --config examples/core/dummy.properties --set source.file=C:/recordings/input.cf32 --headless -v
bin/jsatdecoder.bat --config examples/core/dummy.properties --set source.file=C:/recordings/input.cf32 --ui -v 2> monitoring.log
~~~

Logging is disabled by default. The equivalent global property is
`logging.verbose=true`; `-v` takes precedence over properties and `--set`.

Each UTC-stamped `INFO` record occupies one physical line on standard error:

~~~text
2026-10-28T12:34:56Z INFO Locks: carrier.lock=SEARCHING -> LOCKED, symbol.lock=UNKNOWN -> SEARCHING
2026-10-28T12:34:58Z INFO Monitoring: carrier.lock=LOCKED, carrier.offset_hz=1200.5 [Hz], ...
~~~

The abbreviated monitoring example above stands for the complete snapshot:
all source, decoder, runtime and frame-output metrics shown in the UI table,
sorted by key and accompanied by descriptor units when available. Values use
the table's conventions, including `Unavailable` for nonfinite measurements.
Metric text is escaped to preserve one line; the set of metrics is not truncated.
The first snapshot is logged immediately, then at two-second wall-time intervals
as management polling permits, independently of replay speed or wire/UI cadence.

Every observed carrier, symbol or frame lock change is logged at the normal
management polling cadence (up to four times per second), including lock loss,
searching and transitions to unknown. Changes observed together share a line;
unchanged states are not repeated as events. Initial states are compared with
`UNKNOWN`, and the final drained snapshot also checks for lock changes.
The pull monitoring API exposes current states, so transitions occurring entirely
between polls cannot be reconstructed. Timestamps identify the observation.

Formatting and log output run on the management caller, reusing the snapshots
already forwarded to monitoring distributors. No extra worker, Swing dependency,
sample callback or DSP work is introduced.

## Ownership and lifecycle

Creation resolves factories before constructing components and closes successfully
constructed components on later failure. Start order is frame outputs, monitoring
outputs, decoder, source. Startup failure cleans every owned component, including
partially started and not-yet-started components.

Call `awaitCompletion()` on a management thread after `start()`. It polls source
and decoder monitoring at most four times/second, detects asynchronous failure
and EOF, and shuts down. Start alone creates no supervisor/scheduler. Monitoring
snapshots never run on sample workers. Runtime events include runtime.started,
source.eof, runtime.stopped and runtime.failed. Failed monitoring outputs cannot
deliver their own failure event; diagnostics remain available locally.

Stop joins acquisition, stops/flushes decoder work, drains frame workers and then
drains monitoring/event workers. Close releases components in reverse construction
order exactly once. Stop/close are idempotent, including before start. Runtime
and synthetic outputs are one-shot. State is CLOSED after cleanup; `failure()`
retains the diagnostic. `snapshot()` returns immutable weakly consistent status.
Interrupting awaitCompletion stops and joins components, restores interruption
and propagates InterruptedException. The CLI installs a shutdown hook before
start and removes it after completion. Hook and normal cleanup use the same
idempotent runtime. Forced process termination cannot guarantee hook execution.

Frames can fan out to multiple independent outputs. A failure after partial
fanout fails the runtime; it does not undo deliveries already accepted.

## Synthetic outputs

Both factory IDs are synthetic. Factories deliberately consume/count without
retaining history or writing files/network traffic. Constructors accept worker
callbacks for tests and embedding. The dummy decoder is integration-only: one
little-endian float IQ payload per source block, source UTC epoch-nanosecond timestamp and
sample_index annotation. Payloads/count/order are deterministic; timestamps vary
between runs. It performs no mission DSP and reports unavailable SNR as NaN.

Each output section accepts type, queue_capacity (positive, default 256) and
overflow_policy (FAIL_RUNTIME default; DROP_NEWEST or DROP_OLDEST optional).
One worker owns callbacks. A nonwaiting semaphore and concurrent queue bound
queued/reserved messages to capacity, plus one in-flight callback. Submission
performs no callback, I/O, capacity waiting or metric-map construction.
Allocations are per message, never per sample. Monitoring snapshots/events share
one FIFO; this implementation does not coalesce snapshots.

FAIL_RUNTIME counts the rejected item and fails output/runtime. Drop policies
count each discarded item. DROP_OLDEST replaces the oldest queued item; it cannot
replace an executing callback. With concurrent reservations another producer may
claim the replacement slot, also dropping the incoming item; both are counted.
Callback exceptions fail the worker and count the failed and remaining queued
items as dropped. On queue overflow, a healthy worker drains accepted frames.

Owners serialize lifecycle and quiesce producers before stopping outputs.
Callbacks must eventually return; stop joins without an arbitrary drain timeout.
Callbacks must not stop/close their own output or runtime. Connected means the
synthetic worker is running, not a network connection. Counters are independently
observed and reconcile after drain.

SatDecode-compatible ZMQ transports provide network distribution;
see [ZMQ compatibility](zmq-compatibility.md). Synthetic outputs retain their
integration-only meaning. The runtime normalizes common identity/network
aliases and forwards merged source/decoder/distribution snapshots, including
a final observation after acquisition/decoder/frame drain. ZMQ workers own wire
status cadence; awaitCompletion remains the generic management polling loop.
The runtime exposes observer-only monitoringSnapshot(), monitoringDescriptors()
and taps() without a desktop dependency in its functional path. The CLI owns UI sessions
separately, closes them on completion/failure and isolates headless/UI failures.
Window close disables observation while decoding continues. See swing-ui.md.
