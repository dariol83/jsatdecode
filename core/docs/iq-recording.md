# PlutoPlus / FMComms2 CF32 session recording

Add these settings to a normal `source.type=fmcomms2` configuration:

```properties
source.recording.enabled=true
source.recording.folder=C:/recordings/iq
```

Recording is disabled by default. An absent folder uses Java's `user.home`.
An explicitly blank folder, an unavailable home directory, malformed settings,
or an output-creation failure is an error. Relative paths use the process working
directory; use forward slashes or escaped backslashes in Java properties.
Missing directories are created at startup. `--set` can override every setting.

This records the source's normalized complex samples before decoder processing.
It is independent of `--store` / `storage.folder`, which records decoded frames.
Both features may be enabled together.
When `source.output_frequency_offset` is nonzero, recording still captures
source IQ before the runtime rotator. Apply the same offset during replay to
restore the live decoder input; see [frequency translation](runtime.md#source-output-frequency-translation).

## Configuration and naming

| Setting | Default | Meaning |
| --- | --- | --- |
| source.recording.enabled | false | Strict true/false enablement |
| source.recording.folder | user.home | Output directory, created at startup |
| application.configuration_name | Properties-file stem, otherwise session | Global configuration identity |
| source.recording.name | Global configuration identity, otherwise session | Source-local filename identity override |
| source.recording.queue_mib | 64 | Queued IQ payload budget, 1..1024 MiB; must hold one maximum source block |
| source.recording.write_buffer_mib | 4 | Reusable direct serialization buffer, 1..64 MiB |
| source.recording.failure_policy | STOP_RECORDING | STOP_RECORDING or FAIL_SESSION |

Global configuration precedence remains defaults, file, `--set`, dedicated
switches. When identity is absent, `ConfigurationLoader` removes the final file
extension: `receiver-pluto.properties` becomes `receiver-pluto`. Runtime passes
identity only to FMComms2. A source-local name overrides it; direct source-factory
users have no global/file context and default to `session` unless they supply
unprefixed `recording.name`.

Names must contain 1..120 characters. Control characters and the portable unsafe
characters `<>:"/\|?*` become underscores. Surrounding whitespace and trailing
periods/spaces are removed; a resulting empty name is rejected. The result is one
path component, never a subdirectory. The final filename is:

```text
<configuration name>_<center frequency Hz>_<delivered sample rate>sps_g<configured gain>_<yyyyMMdd_HHmmss>.cf32
```

Example: `receiver-pluto_137100000_500000sps_g45_20260101_120000.cf32`.

Frequency is the hardware LO readback, not an assumed value. Rate is the verified
stream output: `source.sample_rate / source.fpga_decimation`. A 4 MS/s PHY with
decimation eight therefore produces `500000sps`, not `4000000sps`.

The gain token is the selected receiver's configured `manual_gain1` or
`manual_gain2`, including its default if omitted. Integral/fractional values
appear as `45` / `42.5`, independent of locale. Under AGC this token remains the
configured numeric value; it is **not** the actual varying hardware gain. Gain
mode and selected receiver are exposed separately through monitoring.

The timestamp is **UTC**, captured once after successful radio configuration,
before file creation, stream OPEN and the first READBUF. File/buffer setup
finishes before OPEN so slow storage setup cannot accumulate hardware buffers.
It is the
recording-start wall-clock instant, not a hardware sample timestamp or the
source's estimated first-sample UTC. The Java pattern is `uuuuMMdd_HHmmss`.
`CREATE_NEW` protects existing files: a same-second/name collision fails startup
without overwriting or adding a suffix. An empty started session creates an
empty file. Retuning still requires a new source/session.

## Binary format and playback

Files contain headerless little-endian IEEE float32 pairs:
`I0,Q0,I1,Q1,...`. There are exactly eight bytes per complex sample. No further
gain, scaling, conjugation, filtering or decoder preprocessing is applied. A
successful normal stop containing N complex samples produces exactly 8*N bytes.

For JSatDecode replay, replace the complete hardware source section with:

```properties
source.type=cf32
source.file=C:/recordings/iq/receiver-pluto_137100000_500000sps_g45_20260101_120000.cf32
source.sample_rate=500000
source.center_frequency=137100000
source.realtime=false
# If the live configuration used output frequency translation, retain it:
# source.output_frequency_offset=-25000
```

Keep the desired decoder/output sections. Remove FMComms2 hardware and recording
keys: file sources deliberately reject unknown settings. The CF32 reader does
not infer metadata from filenames. Raw CF32 preserves neither original block
boundaries nor embedded UTC timestamps; current replay timestamps start a new
processing timeline. See [file-sources.md](file-sources.md).

For GNU Radio on a little-endian host, use **File Source**, output **Complex**,
vector length **1**, repeat **No**. Set flowgraph sample-rate variables to the
delivered rate independently; configure any required frequency axes separately.
A big-endian host needs float byte-order conversion. No header-skip block is used.

## Bounded execution and failure

The acquisition worker creates the same immutable block used by the decoder and
offers its reference to a fixed SPSC queue before decoder acceptance. It never
serializes, copies the IQ array, allocates queue nodes, waits for capacity, logs
routine messages or performs filesystem operations. Disabled recording creates
no output path, buffer, queue or recording worker. Supplied settings are still
validated to catch errors.

Only `jsatdecode-cf32-writer` serializes and writes. Producer wakeups are batched
up to 32 queued blocks (fewer for tiny queues); timed worker polls also cover
sparse input, and finalization always wakes the worker. Its reusable buffer batches
large sequential writes and handles partial/zero-length channel writes. A
continuous one-second period of zero progress fails recording, with parked
retries on the writer only. This does not impose a timeout on an OS write that
itself blocks. Partial batches are written at shutdown; no per-block fsync is
performed. Successful close does not guarantee power-loss durability.

Queue slots are `min(65536, floor(queue_bytes / (8*block_samples)))`. At the
default block size 4096 there are 2048 slots and at most 64 MiB of queued sample
payload. Memory also includes references, retained immutable block/array headers,
one in-flight block and the 4 MiB serialization buffer. Consumed slots are cleared.
Short blocks or the slot cap reduce actual retained payload/stall coverage;
`queue_capacity_bytes` is the maximum at the configured block size. Other source,
decoder and distribution memory is additional.

CF32 requires 8*Fs bytes/s: 500 ksps is 4 MB/s (14.4 GB/hour); 4 Msps is
32 MB/s (115.2 GB/hour). A full 64 MiB queue covers roughly 16.8 or 2.1 seconds
respectively. Storage must sustain the average rate. CPU, memory bandwidth and
GC effects remain measurable even though storage cannot backpressure DSP.

On overflow, no block is skipped followed by a recording resumption. New offers
become inert and the healthy writer drains the already accepted contiguous
prefix. `STOP_RECORDING` preserves acquisition/decoding and retains recording
failure status. `FAIL_SESSION` also records a source failure and cancels the socket.
The writer emits one error diagnostic including the destination and original
cause. I/O failure preserves the written prefix and attempts to truncate a
partial complex sample; repair can also fail on broken storage and is reported.
Close errors are retained, with secondary errors suppressed under the first.

Source/network/decoder failure finalizes an incomplete recording. Recording is
offered before decoder acceptance, so a block whose decoder callback throws may
already be recorded. Unprocessed tails of the source's raw staging buffer are
not added after cancellation. The recorder does not detect or repair hidden
device/kernel sample loss.

Callbacks use `requestStop()`; they never join. Management stop first quiesces
acquisition, drains accepted blocks, closes the file and joins both workers.
`awaitCompletion` includes recording finalization. Normal source completion is
not published while the file is still draining. Graceful management shutdown may
wait for pending storage operations; forced process termination cannot ensure
finalization.

## Monitoring and verification

The fixed `source.recording.*` schema exposes enabled/active/state, file,
`start_time_ns`, samples_accepted/samples_written/bytes_written, queue_bytes,
queue_capacity_bytes/queue_slots/queue_high_water_bytes, errors/failure/complete,
rx_channel/configured_gain/gain_mode/failure_policy. States are DISABLED,
CONFIGURED, STARTING, RECORDING, DRAINING, COMPLETE, INCOMPLETE and FAILED.
These snapshots are weakly consistent gauges, assembled only by polling. A
recording failure under STOP_RECORDING leaves the source error counter unchanged.
Always check final `complete` and `failure`; the filename alone cannot identify
an incomplete prefix. No sidecar file is generated.

`Fmcomms2SourceTest` invokes `Cf32RecordingTest` during `mvn verify`.
Tests use mock IIOD, controlled clocks, bounded fake
channels, partial/failed writes and sparse long-file positions; no radio is needed.

The opt-in `Cf32RecordingBenchmark` alternates recording OFF/ON with identical
mock samples, dummy decoder and CRC consumer. From the repository root after
test compilation:

```powershell
java -cp 'core/target/classes;core/target/test-classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.source.fmcomms2.Cf32RecordingBenchmark core/target/iq-benchmark 512 5 0
```

Arguments are destination, buffers/run (65536 samples each), pairs, optional
producer pacing samples/s (zero is unpaced). Benchmark recordings use unique
names and are deleted after checking their length. The JDK's java.management and
jdk.management modules supply producer allocation/CPU and GC measurements.
Reports include CRC equality, producer throughput, allocation, GC time, queue
high-water mark and completion/write throughput. Correctness tests do not assert
timing thresholds. Measure producer overhead on the intended decoder/storage
setup with runs long enough to exceed filesystem-cache effects. A short cached
dummy benchmark cannot establish durable disk throughput.
