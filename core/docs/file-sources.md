# WAV, CF32 and CS16 file sources

File sources provide deterministic local acquisition through the SampleSource contract.
All code is Java SE, with no external codec, DSP, UI or native dependency.
Factories are Cf32SourceFactory (ID cf32), Cs16SourceFactory (ID cs16) and WavSourceFactory (ID wav).
All are discoverable using ServiceLoader<SampleSourceFactory> and selectable through the runtime and CLI.

## Configuration

Factories and concrete source constructors take an unprefixed source section.
For a full properties-style Configuration, pass configuration.getSection("source").
Unknown keys, malformed values, unreadable/nonregular paths and invalid ranges fail with ConfigurationException before processing.
The runtime additionally accepts `source.output_frequency_offset` in Hz and
consumes it before source-factory validation. A nonzero value inserts a rotator
after file delivery and before decoder DSP; zero/absent uses the direct path.
See [frequency translation](runtime.md#source-output-frequency-translation) for
sign, phase and sample-rate semantics. Direct file-source construction takes
only the source-specific keys below.

File-source factory IDs: `cf32`, `cs16`, `wav`. For live acquisition, see
[the FMComms2 source](fmcomms2-source.md).

| Key | Meaning/default |
| --- | --- |
| type | Optional matching factory ID: cf32, cs16 or wav |
| file | Required readable regular recording path |
| sample_rate | CF32/CS16: required positive finite complex samples/s; WAV: optional, must equal header |
| block_samples | Maximum complex samples per block; default 4096, allowed 1..1048576 |
| start_offset | Inclusive recording index in complex samples; default 0 |
| end_offset | Optional exclusive recording index in complex samples |
| length | Optional selected complex-sample count; mutually exclusive with end_offset |
| repeat | Boolean, default false; repeat the selected segment until stopped |
| realtime | Boolean, default false; pace to the recording sample clock |
| center_frequency | Finite nonnegative metadata in Hz; default 0 (unspecified) |

Offsets and lengths are integer sample counts, not bytes or seconds.
The default end is the payload's final sample. Bounds outside the recording are rejected rather than clipped.
Explicit limits must be nonnegative. Zero-length nonrepeating selections are valid and emit no empty blocks.
Repeating an empty segment is rejected. Length arithmetic is checked for overflow.
Relative paths resolve against the process working directory and are normalized at construction.

Example source section in a properties configuration:

~~~properties
source.type=cf32
source.file=/recordings/pass.cf32
source.sample_rate=500000
source.block_samples=4096
source.start_offset=10000
source.length=5000000
source.repeat=false
source.realtime=false
source.center_frequency=145800000
~~~

For CF32 and CS16, sample_rate is required. For WAV, omit sample_rate to use the header. There is no resampling, tuning or amplitude scaling.

CS16 example:

~~~properties
source.type=cs16
source.file=/recordings/pass.cs16
source.sample_rate=500000
source.block_samples=4096
source.start_offset=10000
source.length=5000000
source.center_frequency=145800000
~~~

CS16 files must have a length that is a multiple of 4 bytes (2 bytes per component).

## Encoding and validation

CF32 is I0,Q0,I1,Q1,... in little-endian IEEE float32. File length must be a multiple of eight.
CS16 is I0,Q0,I1,Q1,... in little-endian signed 16-bit integers, stored as 4 bytes per complex sample (2 bytes I + 2 bytes Q). File length must be a multiple of four. Samples are converted to float32 in the range [-1.0, 1.0] by dividing by 32768.0. This format is compatible with SatDump and SDR++ CS16 format.
WAV maps its first stereo channel to I and its second to Q, with IEEE float32 samples.
Bits, channel count, block alignment, sample rate and byte rate are checked.
Raw float bits, including signed zero, NaN payloads and infinities, are preserved by sample delivery.
No RF/DSP interpretation or filtering is performed.

Supported WAV forms:

- Little-endian RIFF/WAVE with IEEE float format code 3.
- WAVE_FORMAT_EXTENSIBLE with a float subformat GUID and 32 valid bits.
- Common RF64/WAVE with a leading ds64 chunk and no replacement-table entries.
- Unsigned 32-bit RIFF chunk lengths and checked 64-bit RF64 sizes/offsets.
- Padded unknown chunks, metadata before/after data, and fmt before or after data.
- A single contiguous data region, including an empty one.

Rejected formats include PCM integer samples, mono/multichannel audio, non-32-bit samples,
big-endian RIFX, BW64, nonfloat extensible subformats, duplicate fmt/data regions,
RF64 replacement tables, unknown RF64 sentinel chunks, inconsistent sizes/counts and truncated headers/data.
RF64 unsigned values beyond Java's nonnegative signed-long range are unsupported and rejected.
A nonzero RF64 sample count must match payload length; zero means unspecified.
Bytes after the declared RIFF container are ignored. Chunk payload/padding must fit inside the declared container.

WavHeader.read(path) exposes validated metadata and reports format/I/O failures as IOException.
Source construction translates WAV validation failures to ConfigurationException with the parser diagnostic.
Recordings must remain unchanged after construction. An insufficient payload length at start or an
encountered truncation during reading is detected; arbitrary header/in-place changes are not checksummed
and append-only growth is outside the validated selection.

## Lifecycle and ownership

Construction returns CONFIGURED with immutable settings and no retained input channel.
Install the non-null sink while CONFIGURED, then start once.
Start validates the file is still long enough, opens a channel and launches one worker.
The worker invokes the sink serially with blocks of at most block_samples; the final block may be shorter.
No asynchronous queue or per-sample object is created.
Reusable byte/float staging is bounded by block_samples. The block constructor makes its
own immutable sample copy, so consumers can retain blocks after return without producer reuse corrupting them.

The source does not retain delivered blocks. A sink that retains every block owns that memory policy.
Monitoring polling never runs on the delivery worker and cannot block it through a callback.
A file read and optional pacing wait are source-functional operations.

EOF of the selected nonrepeating segment closes the channel and enters STOPPED.
Stop and close are idempotent before start, after EOF and after failure.
Sources do not support restart; create a new instance for another deterministic run.
Management stop interrupts reads/pacing and joins the worker, preserving caller interruption.
Close additionally enters terminal CLOSED. No sample callbacks occur after management stop/close returns.
A sink must cooperate with interruption: arbitrary stuck sink code cannot be forcibly terminated safely.

Use requestStop from a callback for nonblocking cooperative cancellation.
Calling stop/close on that worker is rejected with LifecycleException to prevent self-join.
requestStop does not interrupt an in-flight read; pacing checks it at intervals of at most 100 ms.
Use management stop to interrupt and join.

Startup failure throws SourceException and sets FAILED, closing partially acquired resources.
Asynchronous read/sink failure sets FAILED and is available through failure().
awaitCompletion(timeout) waits for success, cancellation or failure with input resources released;
it does not rethrow a worker failure, so inspect state/failure. Stop/close also join the worker.
A failed sink block is not included in successful delivery counts.
Cancellation does not masquerade as EOF; failure remains visible until close, with diagnostics retained afterward.

## Indices, time and repeat

sampleIndex is the absolute recording sample index, including start_offset.
Repeat rewinds that index to start_offset and repeats only the selected interval.
source.samples counts total successful deliveries across passes, without rewinding.

Each block timestamp is start's UTC epoch-nanosecond anchor plus the duration of previously delivered samples.
The first selected block is at that anchor; original-recording offset is represented by sampleIndex.
The sample clock continues across repeats. This is a processing-start UTC sample
timeline, not the recording's original capture time. Accelerated decoding may
therefore produce sample timestamps later than current host time. Provide explicit
recording UTC annotations when the original capture epoch is known. Epoch and
sample-offset arithmetic must fit signed-long nanoseconds; overflow fails decoding.
Elapsed time, throughput and real-time pacing still use a separate monotonic clock.
Functional sample values/order/block boundaries/indices are deterministic across runs;
the wall-clock anchor and performance metrics necessarily vary.

Unpaced mode performs no sleeps. Real-time mode waits before delivery until the block's cumulative
end time, accounting for prior sink/processing time and avoiding a burst from a short file.
Late processing catches up without adding an extra full delay per block.
Stop interrupts even a very distant pacing deadline.

## Monitoring

All descriptors and snapshots are immutable and use a fixed schema.
Updates use cached primitive handles; elapsed time/realtime factor are calculated on the observer caller.

| Metric | Meaning |
| --- | --- |
| source.samples | Complex samples accepted by successful sink returns, including repeats |
| source.blocks | Successfully delivered blocks |
| source.cycles | Fully delivered selected-segment passes; zero for an empty selection |
| source.position | Next original-recording sample index; rewinds between passes |
| source.eof | Nonrepeating selected segment reached its end normally |
| source.total_samples | Complete payload length, excluding WAV headers/metadata |
| source.selected_samples | Selected segment count |
| source.sample_rate | Complex samples/s |
| source.center_frequency | Configured Hz metadata, zero when unspecified |
| source.elapsed_s | Live processing wall time, frozen at completion, including pacing/sink work |
| source.realtime_factor | (source.samples/source.sample_rate)/source.elapsed_s, or zero before timing starts |

Snapshots are weakly consistent across metrics. No source.drop/overrun counters are fabricated for this synchronous file path.

## Programmatic example

Imports omitted; this example uses the source directly:

~~~java
var configuration = new Configuration(Map.of(
        "file", "/recordings/pass.cf32",
        "sample_rate", 500000,
        "block_samples", 4096));
var received = new AtomicLong();
try (var source = new Cf32SourceFactory().create(configuration)) {
    source.setSampleSink(block -> received.addAndGet(block.complexSamples()));
    source.start();
    if (!source.awaitCompletion(Duration.ofMinutes(5))) {
        source.stop();
        throw new IllegalStateException("File processing timed out");
    }
    source.stop();
    if (source.failure().isPresent()) {
        throw new SourceException("File processing failed", source.failure().orElseThrow());
    }
}
~~~

For CLI execution with a configured decoder and outputs, see [runtime and CLI](runtime.md).
