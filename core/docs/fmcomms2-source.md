# Native Java FMComms2 / PlutoPlus network source

Factory ID: **`fmcomms2`**. Implementation package:
`eu.dariolucia.jsatdecode.source.fmcomms2`. This is an independent Java socket
implementation of IIOD's network protocol. It does **not** load libiio, JNI,
JNA, USB drivers, native libraries, Python or an external acquisition process.
The radio must already expose an IIOD TCP server (normally port 30431).
The source configures an AD9361-family receive chain and delivers normalized
complex float32 blocks through the unchanged SampleSource API.

## References and protocol decision

Protocol and hardware references:

- [libiio overview and supported remote backends](https://analogdevicesinc.github.io/libiio/main/).
- [GNU Radio FMComms2 GRC settings](https://github.com/gnuradio/gnuradio/blob/main/gr-iio/grc/iio_fmcomms2_source.block.yml).
- [GNU Radio FMComms2 implementation](https://github.com/gnuradio/gnuradio/blob/main/gr-iio/lib/fmcomms2_source_impl.cc).
- [libiio v0.26 protocol client](https://github.com/analogdevicesinc/libiio/blob/v0.26/iiod-client.c)
  and [daemon grammar](https://github.com/analogdevicesinc/libiio/blob/v0.26/iiod/parser.y).
- [Current daemon grammar retaining legacy commands](https://github.com/analogdevicesinc/libiio/blob/main/iiod/parser.y),
  [daemon sample transfer](https://github.com/analogdevicesinc/libiio/blob/v0.26/iiod/ops.c)
  and [channel ordering](https://github.com/analogdevicesinc/libiio/blob/v0.26/context.c).
- [ADI's receive configuration example](https://github.com/analogdevicesinc/libiio/blob/v0.26/examples/ad9361-iiostream.c)
  and [AD9361 filter/rate management](https://github.com/analogdevicesinc/libad9361-iio/blob/main/ad9361_baseband_auto_rate.c).

The implementation uses the **legacy text-command / binary-body IIOD protocol**,
including on newer daemons retaining that mode. It does not negotiate the newer
`BINARY` RPC mode or ZSTD XML. Using `PRINT` avoids compression/native dependencies
and is supported by the legacy grammar used on Pluto firmware. This is protocol
interoperability, not a complete Java binding to every libiio API or backend.
USB/local/serial discovery, TX, cyclic output, binary-only servers and TLS are
outside this source's scope.

## Configuration

Optional `source.recording.enabled=true` records the delivered complex stream to
a headerless CF32 file. `source.recording.folder` selects the destination and
defaults to Java's home directory. Recording uses a bounded reference queue and
an independent writer; see [IQ recording](iq-recording.md) for filenames, buffer
sizes, failure policies and replay instructions.

Use normal properties and CLI overrides:

```powershell
bin/jsatdecoder.bat --config examples/core/fmcomms2-dummy.properties --set source.uri=ip:192.168.2.1 --set source.frequency=137100000 --ui
```

Run this command from an extracted [distribution](../../distribution/README.md).
The example decoder is **integration-only** and emits raw-IQ test records with
no RF/symbol plot taps. Use a mission decoder
matching the configured input rate for mission reception. Hinode currently
requires 500 kS/s; use the native FPGA configuration below on supporting firmware,
not this source's default 3 MS/s. This source does not alter Hinode's DSP/API.

All keys below are in the `source.` section. Factories receive them without that
prefix, including the `type` selector. Unknown keys fail before network access.
Numbers in properties are decimal. Frequency values may exceed signed 32-bit.
The runtime also accepts `source.output_frequency_offset` (finite signed Hz)
for offset tuning. It consumes the key before calling this factory and rotates
delivered IQ before decoder acceptance, using the output rate after
`fpga_decimation`. Hardware LO configuration and source IQ recording precede
this translation. See [runtime translation](runtime.md#source-output-frequency-translation).

| Key | Default | Meaning / validation |
| --- | --- | --- |
| type | required by runtime | `fmcomms2` |
| uri | `ip:192.168.2.1` | `ip:hostname[:port]` or `ip:[IPv6][:port]`; port defaults 30431; explicit host, no discovery |
| frequency | 2400000000 | RX LO in Hz, 70000000..6000000000; hardware can impose narrower limits |
| sample_rate | 3000000 | Physical complex samples/s, 520833..61440000; Off additionally requires >=2084000 |
| fpga_decimation | 1 | Native FPGA division factor: 1, 2, 4, 8 or 16; PHY sample_rate must be exactly divisible; hardware support is required |
| bandwidth | 2000000 | RX analog RF bandwidth Hz, 200000..52000000 |
| buffer_size | 65536 | IIOD OPEN buffer in complex scans, 1..1048576; larger buffers reduce transaction overhead |
| block_samples | 4096 | Maximum immutable delivery block, 1..buffer_size |
| rx1_en | true | Select RX1; exactly one RX receiver must be enabled |
| rx2_en | false | Select RX2; set rx1_en=false as well; hardware must advertise the second receiver |
| gain1 / gain2 | `slow_attack` | Per-receiver `manual`, `slow_attack`, `fast_attack`, `hybrid` |
| manual_gain1 / manual_gain2 | 64 | dB, -10..73; written only for selected manual receiver; firmware validates its actual frequency-dependent range |
| rf_port_select | `A_BALANCED` | A/B/C_BALANCED, A/B/C_N, A/B/C_P, TX_MONITOR1, TX_MONITOR2, TX_MONITOR1_2; firmware validates availability |
| quadrature | true | RX quadrature tracking correction |
| rfdc | true | RF DC offset tracking |
| bbdc | true | Baseband DC offset tracking |
| filter_source | `Off` | `Off`, `File`, `Keep`; see filter decisions below |
| filter | absent | Required only for File: existing AD9361 FIR configuration, 1..65536 bytes, read/bounded during setup |
| kernel_buffers | 4 | Daemon/kernel buffer count, 1..64, set before OPEN |
| connect_timeout_ms | 5000 | TCP connect budget, 1..120000 ms; OS DNS lookup is outside this socket budget |
| server_timeout_ms | 3000 | IIOD TIMEOUT, 1..60000 ms; must exceed one buffer's signal duration |
| read_timeout_ms | 5000 | Blocking socket read budget, 1..120000 ms; must exceed server_timeout_ms |
| control_device | `ad9361-phy` | XML-advertised name or ID for RF configuration |
| stream_device | `cf-ad9361-lpc` | XML-advertised name or ID for ADC streaming |

### Mapping to GRC and deliberate differences

LO, rate, gains, RF port, corrections, buffer sizing and FIR upload map to the
corresponding FMComms2 controls. This source supports one selected complex RF
stream because the framework source API has one SampleSink. Simultaneous RX1+RX2
is rejected rather than silently interleaving unrelated RF channels. RX1 maps to
ADC voltage0/1; RX2 to voltage2/3, with physical gain channels voltage0/1.

Output is always complex float32; GRC's raw sc16 output and packet length tags
have no counterpart in the existing API. Block count/index/UTC describe delivery.
The source defaults to a configurable 65536-sample buffer; GRC defaults to 32768.
These are
startup settings, not live GUI setters. Retune/reconfiguration requires a new
source instance, preserving the existing immutable source configuration model.

### FIR and rate decisions

- **Off**: writes physical sample rate/bandwidth, then disables the shared FIR,
  following the dependency order used by GNU Radio. Rates below 2084000 fail early.
- **File**: loads the supplied hardware FIR text verbatim. Startup moves to a
  safe 3 MS/s physical rate, disables FIR, uploads `filter_fir_config`, enables
  FIR, then applies the requested rate/bandwidth. The daemon/driver validates the
  coefficients and clock-chain compatibility. No host-side sample filtering occurs.
- **Keep**: leaves the existing hardware FIR untouched, while setting requested
  rate/bandwidth and other controls. The firmware must accept that combination.
- **Auto/Design**: rejected explicitly. GRC implements them with libad9361's
  coefficient design and rate-chain algorithms; they are not IIOD commands.
  This protocol source does not pretend that changing sampling_frequency alone
  reproduces those algorithms. Supply a predesigned FIR file when required.

The PHY sample rate is read back and must equal the requested rate exactly;
the LO readback supplies actual center-frequency metadata. FPGA decimation is
configured separately as described below. Unsupported attributes or rejected writes fail startup;
they are not silently ignored. No samples are delivered before setup completes.
Hardware changes remain after stop; configuration is not transactional/rolled
back on a later failure. Global AD9361 FIR controls may affect RX/TX together;
radio configuration should have a single application owner.

### Native FPGA decimation and 500 kS/s decoder input

`source.sample_rate` always denotes the PHY rate. `source.fpga_decimation`
requests the native ADC streaming rate `sample_rate / fpga_decimation`; blocks,
UTC sample-clock increments, buffer-duration validation and real-time factors
all use this output rate. No Java filtering, resampling or sample dropping occurs.
Factors are explicit; the source never silently substitutes another factor.

At a 4 MS/s PHY rate:

| fpga_decimation | Decoder/network complex samples/s |
| --- | --- |
| 2 | 2000000 |
| 4 | 1000000 |
| 8 | 500000 |
| 16 | 250000 |

For a 2 MHz analog RF bandwidth and 500 kS/s input to Hinode, add these source
settings to your complete runtime/decoder configuration:

```properties
source.type=fmcomms2
source.uri=ip:192.168.2.1
source.sample_rate=4000000
source.bandwidth=2000000
source.fpga_decimation=8
source.filter_source=Off
```

RF analog bandwidth is independent of the streaming rate. A decimated 500 kS/s
complex stream cannot retain the entire 2 MHz input band; the hardware decimator
must filter before downsampling. Its actual passband/alias rejection depends on
the installed FPGA design and must be qualified on the device.

After PHY setup, the source resolves the selected ADC I channel's
`sampling_frequency` control. This control is mandatory when the factor exceeds
one. If `sampling_frequency_available` is advertised, its discrete integer rate
list must contain the requested output. The source reads/writes the ADC rate,
requires exact output-rate readback and verifies that the PHY rate remains
unchanged, all before OPEN or sample delivery. Older firmware without an
available-rate list is accepted only after exact rate readback. Factor one
resets a previously enabled decimator where the ADC control is present.

Support in Java does not add capabilities to hardware. The
[standard ADI ADC driver](https://github.com/analogdevicesinc/linux/blob/main/drivers/iio/adc/cf_axi_adc_core.c)
advertises factors 1 and 8; factors 2, 4 and 16 require firmware/HDL exposing
their corresponding output rates through this IIO control. Missing controls,
unsupported rates, rejected writes, malformed capability lists or mismatched
readback fail startup with a SourceException identifying the factor, PHY rate,
output rate and underlying error. The source retains FAILED and its failure;
the runtime cleans up and the CLI propagates the exception, exiting unsuccessfully
(also closing an enabled UI). There is no software fallback or reconnect.

Monitoring separates `source.sample_rate` (decoder/output Hz),
`source.phy_sample_rate` (PHY Hz) and `source.fpga_decimation` (integer factor).
Before successful startup these are requested values; verified hardware values
are established by successful setup. Supported output rates depend on the
connected device's advertised controls and verified readback.

## Wire transactions and sample representation

One socket uses CRLF ASCII commands; responses have LF-terminated numeric/mask
lines and exact-length binary bodies. A single buffered byte stream handles
both, avoiding Reader prefetch corruption of sample data.

1. Connect, set TIMEOUT, request PRINT. Read bounded XML length, XML bytes, LF.
2. Resolve actual device/channel IDs and attributes; names are not hard-coded IDs.
   XML external DTD/entity/schema fetches are disabled. libiio's normal internal
   context DTD is accepted. XML is capped at 2 MiB; lines at 4096 bytes.
3. WRITE channel/device attributes with exact byte lengths. String writes include
   a terminating NUL, as libiio does; FIR uploads are raw. Read numeric attributes
   using length-prefixed bodies plus LF. Negative responses preserve errno.
4. SET stream-device BUFFERS_COUNT; OPEN device buffer_size channel-mask.
5. READBUF device requested-byte-count. Each positive count precedes binary
   data; the first positive chunk includes the channel mask. It is sent once
   **per READBUF**, not once per socket and not on every chunk. A full requested
   transfer ends without a zero terminator; a short transfer ends with zero.

Mask bits are channel numbers **after libiio scan-index/shift sorting**, not
XML positions or raw scan index values. Mask width includes all advertised
channels and pads complete 32-bit words, highest word first. A returned union
mask that differs from the selected pair fails explicitly; another streaming
client can otherwise change layout. Samples must end at a complete four-byte
complex scan boundary. Fragmented socket reads, multiple chunks and short valid
READBUF responses are supported without padding/fabrication.

Selected I/Q channels must have distinct scan indices, signed scalar 16-bit
storage, 1..16 significant bits and a valid right shift. Little/big endian and
libiio's fully-defined S versus sign-extension-required s are handled. Repeated,
unsigned, packed/shared-index and non-16-bit selected formats fail rather than
being misdecoded. Only selected enabled scans are transferred, with their order
derived from metadata. Values are divided by 2^(significant_bits-1): standard
AD9361 12-bit data therefore use /2048, matching GRC fc32 conversion. Optional
XML physical-voltage scale is not applied to normalized digital complex samples.

## Ownership, cancellation, errors and UTC

The factory/constructor performs no network I/O. Start configures the device on
the management caller, then starts one source worker. The worker refills bounded
raw staging and delivers immutable blocks synchronously to the decoder. Staging
is reused only after callbacks return; callbacks may retain the copied blocks.
No per-sample objects, unbounded queue, pacing sleeps, UI/provider callbacks,
status-map construction or FFT analysis occur on the acquisition path.
The configured decoder must keep pace with live input; the source does not
introduce a lossy queue to disguise overload.

Stop closes the socket to unblock network I/O, waits for startup/worker completion
and joins. Closing the connection releases IIOD per-client buffers. It does not
send CLOSE into an incomplete READBUF body, which would desynchronize the protocol.
From a sample callback call requestStop, not stop/close; sinks must eventually
return. Startup can be cancelled by concurrent stop. Stop-before-start, repeated
close and joined shutdown are supported; restart of a stopped/failed source is not.
Calling `requestStop()` before `start()` also pre-cancels a configured source:
the subsequent start completes as STOPPED without connecting or configuring
hardware. Startup checks cancellation before and after publishing its client so
that a concurrent cancellation cannot miss the newly created socket.

Disconnect, short binary body, invalid mask/count/XML/format, timeout, negative
daemon errno and sink failures enter FAILED and retain diagnostics through close.
There is no automatic reconnect or natural EOF: either would conceal a live
sample discontinuity. Cancellation-induced socket failure is normal stop.

IIOD legacy samples supply no hardware UTC timestamp. The first buffer anchors
a UTC sample timeline to host receipt minus buffer duration; subsequent blocks
advance by delivered sample count at verified Fs. Timestamps are UTC epoch ns,
not raw nanoTime. They are estimates, excluding calibrated RF/kernel/network
latency; hidden sample loss would invalidate continuity. Internal elapsed time
uses a separate monotonic clock. See [time.md](time.md).

## Count-only read diagnostics

`new Fmcomms2SampleSource(configuration, readDiagnostics)` opts one source into a
count-only hardware test. The ordinary constructor and factory retain their
normal behavior. The selected ADC layout and complete READBUF framing are still
validated, but each successful whole READBUF response is discarded without float
conversion, `ComplexSampleBlock` allocation, recording or sample-sink delivery.
A sink must still be wired before `start()`; it is never called in this mode.
Configured IQ recording is bypassed. Existing `source.samples` / `source.blocks`
remain successful sink-delivery counters, therefore stay zero during diagnostics.

`ReadDiagnostics(int capacity, ReadDiagnostics.ReadListener listener)` preallocates
three primitive arrays: UTC completion times, monotonic elapsed durations and
actual returned complex-sample counts. One entry represents one complete
READBUF transaction, including a valid short response; protocol chunks are not
reported as separate reads. UTC values are host receipt instants, not hardware
capture times. Elapsed durations start at zero for the first completed read and
use a monotonic clock. Raw `System.nanoTime()` values never cross the public API.
All storage is allocated before acquisition and never grows or overwrites.

The listener runs synchronously on the acquisition worker after each entry is
recorded. It is a test-functional callback intended only for bounded counters,
measurement state transitions and `requestStop()`. It must not perform console or
file I/O, FFT/Swing work, or lifecycle joins; its exceptions fail the source.
No per-read diagnostics objects are created. Once the fixed array is full,
`overflowReadCount()` explicitly counts unretained reads and the listener still
runs. The Inspector test runner counts these callbacks and immediately cancels
and invalidates an exhausted log rather than accepting a truncated trace.

One diagnostics instance belongs to one source run. Only inspect
`capturedReadCount()`, `overflowReadCount()` and indexed `completionTimeNanos()`,
`elapsedNanos()` / `sampleCount()` after management `stop()` has joined the
source. Format and print the trace after that join, outside acquisition. Comparing
read intervals with cumulative returned samples can show whether rapid reads
catch up after a pause. A pause alone can reflect buffering; a persistent
cumulative deficit is stronger evidence of interruption. Host receipt timing
cannot identify whether a loss originated in RF hardware, kernel, IIOD or network.

## Monitoring and limits of loss detection

Monitoring is pull-only, fixed-schema and cached; it never sends attribute reads
or control commands from an observer. Available keys:

- source.active, connected, uri, device, rx_channel;
- source.sample_rate / source.center_frequency (verified rate/read-back LO);
- source.samples / source.blocks (successful callback deliveries);
- source.elapsed_s / source.realtime_factor;
- source.last_block_time_ns (UTC block start, zero before delivery);
- source.errors / source.failure (retained terminal diagnostic);
- source.reported_overruns (IIOD errno EOVERFLOW observations);
- source.timestamp_source = host_anchored_sample_clock.

reported_overruns is **not** a hardware loss counter. This implementation does
not poll FPGA direct-access overflow registers, and the protocol cannot prove
there were no silent device/kernel FIFO drops. A reported overrun fails the run
rather than continuing a falsely contiguous stream. Real reception qualification
must inspect device behavior and sustained decoder throughput.

## Validation and build

The implementation requires only Java SE **java.base + java.xml**; Swing remains
optional. Full builds include java.xml for context parsing. Existing core/file
CLI paths continue to run with java.base only; FMComms2 XML is loaded only when
this source starts. No Maven runtime dependency is added.

Maven verify runs Fmcomms2SourceTest, an independent TCP daemon
fixture. Checks cover settings/registration, actual commands/write bytes,
channel sorting and multiword masks, RX1/RX2, endian/shift/sign conversion,
fragmented/chunked/short responses, immutable blocks/UTC, FIR upload, readback
validation, FPGA-decimator reset, all four decimation factors with/without an
advertised rate list, unavailable controls/rates, rejected writes, incorrect
readback, decimated metadata/UTC and CLI failure propagation, malformed responses, errno/overrun propagation,
disconnect/truncation, read timeout, sink failure, blocked-read cancellation and
concurrent startup cancellation. A bounded loopback throughput check measures
transport/conversion without a decoder or real hardware.
Count-only tests additionally cover preallocation bounds and validation, one
entry per complete chunked/short READBUF with actual scan counts, UTC completion
times and monotonic offsets, explicit overflow without overwrite, no sink calls
or recording even when storage is configured, listener-failure propagation and
rejection of incomplete complex scans.

Mock protocol tests and loopback throughput cover the Java implementation.
Hardware reception, driver/filter compatibility and sustained loss behavior
require measurements with the connected device and reception settings.
