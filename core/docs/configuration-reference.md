# Source and distribution configuration reference

**Recording frequency offset: `source.output_frequency_offset`, in Hz.**

```properties
# Move a signal at +25 kHz relative to the recording center to DC:
source.output_frequency_offset=-25000
```

Positive values shift frequencies upward; negative values shift them downward.
Works with every source. `source.center_frequency` only sets file-source RF
metadata; `source.start_offset` selects a position in the recording, in samples.

[File sources](#file-sources-wav-cf32-cs16) |
[FMComms2](#fmcomms2-live-source) |
[IQ recording](#fmcomms2-iq-recording) |
[Synthetic outputs](#synthetic-outputs) |
[ZMQ frames](#zmq-frame-output) |
[ZMQ monitoring](#zmq-monitoring-output) |
[TCP frames](#tcp-frame-output) |
[TCP monitoring](#tcp-monitoring-output) | 
[Frame files](#frame-file-output) |
[Aliases](#legacy-aliases)

## Configuration basics

All table keys are complete properties-file / `--set` names. Load UTF-8 Java
properties with `--config receiver.properties`; override a value with
`--set source.output_frequency_offset=-25000`. Precedence, lowest first:
defaults, properties file, repeated `--set`, dedicated CLI switches such as
`--store`. The last occurrence within a level wins. Relative paths use the
process working directory; use forward slashes or escaped backslashes on Windows.

Booleans accept `true`/`false` without regard to case. Enum values and factory
IDs are case sensitive. Numeric values must be finite; sample indices/counts
are integers. A range includes both limits unless stated otherwise.

| Global key | Default | Purpose / values |
| --- | --- | --- |
| `decoder.type` | Required | Registered decoder ID; also supplies the default output decoder identity. See [mission decoders](../../README.md#mission-decoders). |
| `application.configuration_name` | Configuration filename without its final extension; `session` without a file | Default FMComms2 IQ-recording name. |
| `storage.folder` | Absent: no additional frame recording | Creates a frame-file output alongside the selected frame output. CLI equivalent: `--store <folder>`; see [frame files](#frame-file-output). |

## File sources: WAV, CF32, CS16

The same table applies to all three file sources; only format and sample-rate
handling differ. CF32 is headerless little-endian float32 I/Q; CS16 is
headerless little-endian signed int16 I/Q; WAV must contain float32 stereo I/Q.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `source.type` | Required | `wav`, `cf32` or `cs16`. |
| `source.file` | Required | Readable regular recording file. |
| `source.sample_rate` | WAV: header; CF32/CS16: required | Positive complex samples/s. A WAV override must exactly match its header. |
| `source.output_frequency_offset` | `0` | Signed frequency translation in Hz before decoder DSP; fractional values allowed. Zero leaves samples unchanged. |
| `source.center_frequency` | `0` (unspecified) | Nonnegative RF center-frequency metadata in Hz; does not translate IQ. |
| `source.block_samples` | `4096` | Maximum integer complex samples per delivered block, `1..1048576`; the last block can be shorter. |
| `source.start_offset` | `0` | Nonnegative inclusive recording index, in complex samples. |
| `source.end_offset` | End of recording | Nonnegative exclusive recording index, in complex samples; mutually exclusive with `source.length`. |
| `source.length` | All samples from start to end of recording | Nonnegative selected complex-sample count; mutually exclusive with `source.end_offset`. |
| `source.repeat` | `false` | Repeat the selected segment until stopped; segment must be nonempty. |
| `source.realtime` | `false` | Pace replay at the sample rate; otherwise process as fast as possible. |

Selection bounds must lie inside the recording, with end at or after start.
File-source factories reject unknown settings. The runtime consumes
`source.output_frequency_offset` before factory validation; direct source-factory
calls do not perform this translation. Details: [file sources](file-sources.md),
[frequency translation](runtime.md#source-output-frequency-translation).

## FMComms2 live source

PlutoPlus/FMComms2 acquisition over IIOD TCP. Device firmware can impose tighter
limits than the configuration ranges below. Unknown source keys are rejected.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `source.type` | Required | `fmcomms2`. |
| `source.uri` | `ip:192.168.2.1` | `ip:host[:port]`; IPv6 addresses in brackets. Default port: `30431`. |
| `source.frequency` | `2400000000` | Integer hardware LO frequency, `70000000..6000000000` Hz. |
| `source.output_frequency_offset` | `0` | Signed Hz translation before decoder DSP, using the delivered sample rate; fractional values allowed. Does not retune hardware or change recorded IQ. |
| `source.sample_rate` | `3000000` | Integer physical AD9361 rate, `520833..61440000` samples/s. With FIR `Off`, at least `2084000`. |
| `source.fpga_decimation` | `1` | `1`, `2`, `4`, `8`, `16`; must divide `source.sample_rate` exactly and be supported by firmware. Delivered rate = physical rate / factor. |
| `source.bandwidth` | `2000000` | Integer analog RF bandwidth, `200000..52000000` Hz. |
| `source.buffer_size` | `65536` | Integer complex samples per IIOD buffer, `1..1048576`. |
| `source.block_samples` | `4096` | Maximum integer complex samples per delivered block, `1..source.buffer_size`. |
| `source.rx1_en` | `true` | Enable RX1; exactly one of RX1/RX2 must be enabled. |
| `source.rx2_en` | `false` | Enable RX2; exactly one of RX1/RX2 must be enabled. |
| `source.gain1` | `slow_attack` | RX1 mode: `manual`, `slow_attack`, `fast_attack`, `hybrid`. |
| `source.gain2` | `slow_attack` | RX2 mode: `manual`, `slow_attack`, `fast_attack`, `hybrid`. |
| `source.manual_gain1` | `64` | RX1 manual gain, `-10..73` dB; applied when RX1 is selected in manual mode. |
| `source.manual_gain2` | `64` | RX2 manual gain, `-10..73` dB; applied when RX2 is selected in manual mode. |
| `source.rf_port_select` | `A_BALANCED` | `A_BALANCED`, `B_BALANCED`, `C_BALANCED`, `A_N`, `A_P`, `B_N`, `B_P`, `C_N`, `C_P`, `TX_MONITOR1`, `TX_MONITOR2`, `TX_MONITOR1_2`. |
| `source.quadrature` | `true` | RX quadrature tracking correction. |
| `source.rfdc` | `true` | RF DC offset tracking. |
| `source.bbdc` | `true` | Baseband DC offset tracking. |
| `source.filter_source` | `Off` | `Off`: disable FIR; `File`: upload FIR file; `Keep`: retain existing hardware FIR. |
| `source.filter` | Absent | Required only with `source.filter_source=File`: readable FIR file, `1..65536` bytes. Rejected with `Off`/`Keep`. |
| `source.kernel_buffers` | `4` | Integer daemon/kernel buffer count, `1..64`. |
| `source.connect_timeout_ms` | `5000` | Integer TCP connect timeout, `1..120000` ms. |
| `source.server_timeout_ms` | `3000` | Integer IIOD timeout, `1..60000` ms; must exceed one buffer's duration at the delivered rate. |
| `source.read_timeout_ms` | `5000` | Integer socket read timeout, `1..120000` ms; must exceed `source.server_timeout_ms`. |
| `source.control_device` | `ad9361-phy` | XML-advertised RF control device name or ID. |
| `source.stream_device` | `cf-ad9361-lpc` | XML-advertised ADC stream device name or ID. |

These settings apply at startup; reconfiguration requires a new session.
Details: [FMComms2 acquisition](fmcomms2-source.md).

### FMComms2 IQ recording

These additional keys apply only to `source.type=fmcomms2`. Files contain CF32
IQ after FPGA decimation and **before** `source.output_frequency_offset`.
Retain the same frequency offset when replaying the recording.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `source.recording.enabled` | `false` | Enable source IQ recording. |
| `source.recording.folder` | Java `user.home` | Output directory; created at startup. Explicitly blank paths are rejected. |
| `source.recording.name` | `application.configuration_name` | Recording filename identity, `1..120` characters; unsafe filename characters are sanitized. Direct factory default: `session`. |
| `source.recording.queue_mib` | `64` | Integer IQ queue budget, `1..1024` MiB; must hold at least one full `source.block_samples` block. |
| `source.recording.write_buffer_mib` | `4` | Integer serialization buffer size, `1..64` MiB. |
| `source.recording.failure_policy` | `STOP_RECORDING` | `STOP_RECORDING`: keep decoding after recording failure; `FAIL_SESSION`: fail acquisition/session too. |

Supplied recording settings are validated even when recording is disabled.
Details and naming: [IQ recording](iq-recording.md).

## Synthetic outputs

The CLI defaults both output types to `synthetic`: these consume/count data
without storing or publishing it. These are all effective settings; other
section keys are ignored. The runtime API requires explicit output type keys.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.frames.type` | `synthetic` (CLI) | Select `synthetic` for frame consumption. |
| `distribution.frames.queue_capacity` | `256` | Positive integer queued-item capacity. |
| `distribution.frames.overflow_policy` | `FAIL_RUNTIME` | `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`. |
| `distribution.monitoring.type` | `synthetic` (CLI) | Select `synthetic` for monitoring/event consumption. |
| `distribution.monitoring.queue_capacity` | `256` | Positive integer queued-item capacity; snapshots and events share the queue. |
| `distribution.monitoring.overflow_policy` | `FAIL_RUNTIME` | `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`. |

## ZMQ frame output

Select `zmq` explicitly. The publisher binds a TCP PUB socket; subscribers
connect. Unknown keys are rejected. Capacities count items, not bytes.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.frames.type` | `synthetic` (CLI) | Set to `zmq`. |
| `distribution.frames.endpoint` | `tcp://*:5555` | TCP bind address; host/port may be `*`, IPv6 in brackets; numeric port `1..65535`. |
| `distribution.frames.receiver_id` | `receiver-1` | Nonblank wire receiver identity. |
| `distribution.frames.decoder` | `decoder.type` | Nonblank wire decoder identity; required when calling the factory directly. |
| `distribution.frames.queue_capacity` | `4096` | Positive integer queued-item capacity. |
| `distribution.frames.send_hwm` | `4096` | Positive integer socket send high-water mark, per peer. |
| `distribution.frames.linger_ms` | `250` | Integer socket-close linger, `0..60000` ms. |
| `distribution.frames.overflow_policy` | `FAIL_RUNTIME` | `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`. |
| `distribution.frames.max_message_bytes` | `16777216` | Positive integer total multipart message size limit, in bytes (default 16 MiB). |
| `distribution.frames.interval_s` | `0.5` | Accepted/validated (`0.001..86400` s), but unused by frame publishing. |
| `distribution.frames.metric_capacity` | `1024` | Accepted/validated (positive integer), but unused by frame publishing. |

## ZMQ monitoring output

This independently binds a TCP PUB socket for status snapshots and events.
Unknown keys are rejected.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.monitoring.type` | `synthetic` (CLI) | Set to `zmq`. |
| `distribution.monitoring.endpoint` | `tcp://*:5556` | TCP bind address; same syntax as the frame endpoint. |
| `distribution.monitoring.receiver_id` | `receiver-1` | Nonblank wire receiver identity. |
| `distribution.monitoring.decoder` | `decoder.type` | Nonblank wire decoder identity; required when calling the factory directly. |
| `distribution.monitoring.queue_capacity` | `256` | Positive integer queued-item capacity; snapshots and events share the queue. |
| `distribution.monitoring.send_hwm` | `256` | Positive integer socket send high-water mark, per peer. |
| `distribution.monitoring.linger_ms` | `250` | Integer socket-close linger, `0..60000` ms. |
| `distribution.monitoring.overflow_policy` | `FAIL_RUNTIME` | `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`. |
| `distribution.monitoring.interval_s` | `0.5` | Full-status publication interval, `0.001..86400` seconds. |
| `distribution.monitoring.metric_capacity` | `1024` | Positive integer maximum number of retained metric keys. |
| `distribution.monitoring.max_message_bytes` | `16777216` | Positive integer CBOR-envelope size limit, in bytes (default 16 MiB); excludes the topic. |

Protocol and behavior: [ZMQ publishers](zmq-compatibility.md),
[client interface](zmq-distribution-interface.md).

## Frame-file output

For recording **alongside** the selected output, set `storage.folder` or pass
`--store <folder>`. The additional recorder inherits only
`distribution.frames.decoder`, `distribution.frames.receiver_id`,
`distribution.frames.queue_capacity` and `distribution.frames.max_message_bytes`;
absent queue/size values use the frame-file defaults below. Its filename prefix
is always `decoder.type`.

To select disk recording as the primary frame output instead, use this table:

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.frames.type` | `synthetic` (CLI) | Set to `frame-file`. |
| `distribution.frames.folder` | Required | Destination directory; missing directories are created at startup. |
| `distribution.frames.filename_decoder` | `distribution.frames.decoder` | Filename prefix; must match `[A-Za-z0-9][A-Za-z0-9_.-]*`. |
| `distribution.frames.decoder` | `decoder.type` | Nonblank decoder identity in stored metadata. |
| `distribution.frames.receiver_id` | `receiver-1` | Nonblank receiver identity in stored metadata. |
| `distribution.frames.queue_capacity` | `4096` | Positive integer queued-frame capacity. Overflow always fails the runtime. |
| `distribution.frames.max_message_bytes` | `16777216` | Positive integer total multipart message size limit, in bytes. |
| `distribution.frames.overflow_policy` | `FAIL_RUNTIME` | Accepted/validated enum, but disk recording always uses `FAIL_RUNTIME`. |
| `distribution.frames.endpoint` | `tcp://*:5555` | Accepted/validated as for ZMQ; no disk-output effect. |
| `distribution.frames.send_hwm` | `4096` | Accepted/validated as for ZMQ; no disk-output effect. |
| `distribution.frames.linger_ms` | `250` | Accepted/validated as for ZMQ; no disk-output effect. |
| `distribution.frames.interval_s` | `0.5` | Accepted/validated as for ZMQ; no disk-output effect. |
| `distribution.frames.metric_capacity` | `1024` | Accepted/validated as for ZMQ; no disk-output effect. |

Unknown keys are rejected. Files use
`<filename prefix>_yyyy_MM_dd_HH_mm_ss.frame.bin` with a UTC timestamp;
existing files are never overwritten. Details: [frame recording](frame-recording.md).

## TCP frame output

JSatDecode TCP Binary Distribution Protocol (JTDP) v1 frame output. Select `tcp`
explicitly. One server socket listens on a single port and distributes frames
to all connected clients. The server also coordinates with the TCP monitoring
distributor so that a single connection can receive frames, monitoring snapshots,
and events. Unknown keys are rejected. Shared `distribution.tcp.*` aliases are supported for the transport/batching settings below; explicit per-stream keys win. The runtime validates shared listener settings before construction.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.frames.type` | `synthetic` (CLI) | Set to `tcp`. |
| `distribution.frames.port` | `6001` | TCP listen port, `1..65535`. |
| `distribution.frames.keepalive` | `false` | Enable `SO_KEEPALIVE`. |
| `distribution.frames.max_clients` | `2` | Maximum concurrent clients, `1..65535`. |
| `distribution.frames.send_buffer_bytes` | OS default | Optional positive requested `SO_SNDBUF` size in bytes. |
| `distribution.frames.receive_buffer_bytes` | OS default | Optional positive requested `SO_RCVBUF` size in bytes. |
| `distribution.frames.tcp_nodelay` | `true` | Set `TCP_NODELAY` (disable Nagle's algorithm). |
| `distribution.frames.max_frame_latency_s` | `1` | Maximum latency for frame batching, `0.001..86400` seconds. |
| `distribution.frames.max_frame_buffer` | `5` | Maximum number of frames to batch, `1..65535`. |
| `distribution.frames.max_message_bytes` | `1048576` | Maximum message size in bytes, `40..2147483647`. |
| `distribution.frames.client_queue_bytes` | `1048576` | Maximum pending outbound bytes per client, `1..2147483647`. |
| `distribution.frames.source_id` | `1` | Source/antenna identifier, `0..65535`. |
| `distribution.frames.queue_capacity` | `256` | Positive integer frame-producer queue capacity, excluding the bounded active batch. |
| `distribution.frames.overflow_policy` | `FAIL_RUNTIME` | Producer item-queue policy: `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`; independent of client byte queues. |

Protocol and behavior: [TCP distribution interface](tcp-distribution-interface.md).

## TCP monitoring output

JSatDecode TCP Binary Distribution Protocol (JTDP) v1 monitoring and event output.
This shares the same server socket as the TCP frame distributor when both are
configured with type `tcp` and the same port. Select `tcp` explicitly. Unknown
keys are rejected.

| Key | Default | Purpose / allowed values |
| --- | --- | --- |
| `distribution.monitoring.type` | `synthetic` (CLI) | Set to `tcp`. |
| `distribution.monitoring.port` | `6001` | TCP listen port, `1..65535`; MUST match `distribution.frames.port` when using both TCP distributors. |
| `distribution.monitoring.keepalive` | `false` | Enable `SO_KEEPALIVE`; MUST match `distribution.frames.keepalive`. |
| `distribution.monitoring.max_clients` | `2` | Maximum concurrent clients, `1..65535`; MUST match `distribution.frames.max_clients`. |
| `distribution.monitoring.send_buffer_bytes` | OS default | Optional positive requested `SO_SNDBUF` size in bytes; MUST match `distribution.frames.send_buffer_bytes`. |
| `distribution.monitoring.receive_buffer_bytes` | OS default | Optional positive requested `SO_RCVBUF` size in bytes; MUST match `distribution.frames.receive_buffer_bytes`. |
| `distribution.monitoring.tcp_nodelay` | `true` | Set `TCP_NODELAY`; MUST match `distribution.frames.tcp_nodelay`. |
| `distribution.monitoring.monitoring_interval_s` | `3` | Monitoring snapshot publication interval, `0.001..86400` seconds. |
| `distribution.monitoring.max_message_bytes` | `1048576` | Maximum message size in bytes, `40..2147483647`; MUST match `distribution.frames.max_message_bytes`. |
| `distribution.monitoring.client_queue_bytes` | `1048576` | Maximum pending outbound bytes per client, `1..2147483647`; MUST match `distribution.frames.client_queue_bytes`. |
| `distribution.monitoring.source_id` | `1` | Source/antenna identifier, `0..65535`; MUST match `distribution.frames.source_id`. |
| `distribution.monitoring.queue_capacity` | `256` | Positive integer monitoring-producer queue capacity; snapshots and events share the bounded FIFO. |
| `distribution.monitoring.overflow_policy` | `FAIL_RUNTIME` | Producer item-queue policy: `FAIL_RUNTIME`, `DROP_NEWEST`, `DROP_OLDEST`; independent of client byte queues. |

Protocol and behavior: [TCP distribution interface](tcp-distribution-interface.md).

Both TCP adapters accept `slow_client_policy=DISCONNECT` in their respective sections. Other values are rejected: any client exceeding its byte quota is disconnected, regardless of the producer overflow policy. No frames, monitoring snapshots or events are generated by a disabled TCP stream. Producer overrun/discard notifications are coalesced and emitted only when TCP monitoring is enabled.

TCP interval settings have millisecond resolution; fractional seconds are truncated to milliseconds (minimum 1 ms). Monitoring submissions update the latest observation, while the configured timer controls publication; enabled monitoring sends a snapshot immediately to a new client. Frame batches use the first accepted frame's monotonic submission time, not recorded reception time, for their latency deadline. Shutdown flushes producer queues and allows up to one second for client queues to drain.

Shared aliases use `distribution.tcp.` followed by the corresponding transport key, `source_id`, `max_frame_buffer`, `max_frame_latency_s`, or `monitoring_interval_s`. They apply only to streams selected as `tcp`. Producer `queue_capacity` and `overflow_policy` remain per-stream settings.

For the Java reference client, `TcpSettings.clientFrom` accepts `distribution.tcp.host` (default `localhost`) or `distribution.tcp.endpoint` (`host:port` or `tcp://host:port`, including bracketed IPv6), `port`, `keepalive`, positive `receive_buffer_bytes`, `tcp_nodelay`, `max_message_bytes`, `reconnect_interval_ms` (default `1000`) and `max_reconnect_interval_ms` (default `30000`). Reconnect limits satisfy `1 <= min <= max <= 86400000` ms. `new TcpClient(settings)` uses these parsed values. These client-only keys are not server configuration keys.

## Legacy aliases

Prefer the canonical keys above. Aliases are normalized after configuration
merging: an explicitly supplied canonical key wins over its alias from any
configuration layer. Network aliases do **not** select `zmq` automatically.

| Alias | Canonical key |
| --- | --- |
| `decoder.name` | `decoder.type` |
| `application.receiver_id` | `distribution.frames.receiver_id` and `distribution.monitoring.receiver_id` |
| `application.monitor_interval_s` | `distribution.monitoring.interval_s` |
| `network.frames.endpoint` | `distribution.frames.endpoint` |
| `network.frames.queue_capacity` | `distribution.frames.queue_capacity` |
| `network.frames.send_hwm` | `distribution.frames.send_hwm` |
| `network.frames.linger_ms` | `distribution.frames.linger_ms` |
| `network.monitor.endpoint` | `distribution.monitoring.endpoint` |
| `network.monitor.queue_capacity` | `distribution.monitoring.queue_capacity` |
| `network.monitor.send_hwm` | `distribution.monitoring.send_hwm` |
| `network.monitor.linger_ms` | `distribution.monitoring.linger_ms` |
