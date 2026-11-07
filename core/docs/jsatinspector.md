# JSatInspector

JSatInspector is a standalone Swing spectrum analyser and complex-IQ recorder
for a PlutoPlus exposing the existing IIOD TCP interface. Application code lives
in `eu.dariolucia.jsatdecode.inspector` in core. It constructs no decoder,
decoder runtime, distributor or JSatDecode monitoring session.

The [stand-alone distribution](../../distribution/README.md) includes JSatInspector
and all runtime dependencies. After extraction, launch `bin/jsatinspector.bat`
on Windows or `./bin/jsatinspector.sh` on Linux/macOS. Both scripts use the Java
module system and forward all supplied arguments, including `--help`. Java 21 or
later must be available through `JAVA_HOME` or `PATH`; Maven is not needed at
runtime. A graphical desktop is required to open the application; `--help`
also works without a display.

To build and run the individual core JAR from the repository root with Java 21
or newer:

```text
mvn -pl core verify
java -jar core/target/jsatdecoder.jar --inspector
```

JSatInspector is packaged in the same JAR as JSatDecode. Use the `--inspector` flag
to launch JSatInspector instead of the decoder CLI. No additional native libraries or
installation are needed. A graphical desktop is required; `java -jar core/target/jsatdecoder.jar --inspector --help`
also works without a display.

The collapsible right sidebar groups radio, FFT, waterfall and storage controls.
The left side contains a spectrum and a horizontally aligned waterfall,
separated by a resizable divider. The Waterfall settings group sets its drawing rate.
Hardware settings are editable while disconnected. FFT window type, rate, size,
relative-dB range, averaging and waterfall drawing rate change while receiving.
Storage can be started/stopped independently of reception. Disconnect and window
close finish accepted recording data before releasing their workers.

## Radio and sample rates

Frequency is entered as floating-point MHz; the shared adapter tunes integer Hz,
so sub-Hz input is rounded and reported in the console. RF bandwidth and selected
sample rate each offer 500 k, 1 M, 2 M, 3 M, 4 M and 5 M. Gain is manual, 1..70 dB.
The RX buffer is in **complex samples** (one I/Q pair), defaults to 16384 and
accepts 1..1048576 samples. Its count passes directly to the source's
`buffer_size`.
The IP field accepts a hostname/IP, optionally with `:port`; bracket IPv6 addresses.

The adapter's existing FIR-Off mode requires a PHY rate of at least 2.084 MHz.
Inspector acquires the 500 ksps, 1 Msps and 2 Msps selections at a 4 Msps PHY rate
and applies software division by 8, 4 or 2 respectively. The other selections use
their chosen PHY rate. The additional user-selected decimation factor is 1, 2, 4
or 8. Both divisions use stateful cascaded 63-tap Blackman half-band filters;
there is no unfiltered sample dropping. FIR state and decimation phase survive
source block boundaries. Startup is causal with zero initial filter history;
the signal has the filters' group delay.

The effective complex sample rate and full plotted frequency span are:

```text
effective sample rate = selected sample rate / decimation factor
frequency axis = tuning frequency - effective rate/2 ... tuning frequency + effective rate/2
```

The analog RF bandwidth is independently configured and can exceed the effective
digital passband. The effective rate appears in the controls and recording name.
Inspector deliberately uses software filtering to support all requested choices
independently of firmware-specific FPGA division capabilities. The shared source
implementation and its explicit FPGA-decimation contract are unchanged.

## PlutoPlus test mode

While disconnected, select **Test mode** below Connect to run sample-delivery
tests against the configured IP address. The progress dialog is modal, shows
the current parameters and phase, and provides Cancel. Other Inspector controls
are disabled until acquisition has stopped. Test samples are discarded; they
never reach the FFT, waterfall or a recording file. Normal Inspector settings
are retained for subsequent connections.

The test covers every combination of durations 5, 10, 30 and 60 seconds; rates
500 k, 1 M, 2 M, 3 M, 4 M and 5 M complex samples/s; and buffers of 4096, 65536,
131072, 262144 and 524288 complex samples. These explicit lists produce **120
runs** (4 × 6 × 5), about 57 minutes plus connection and diagnostic-log time.
Both Test mode buffers and the normal Inspector RX buffer control use complex samples.

Each run configures 2250 MHz, 5 MHz RF bandwidth and manual gain 50 dB. Rates
below 3 Msps use a 4 Msps PHY with native FPGA division by 8, 4 or 2 to obtain
500 ksps, 1 Msps or 2 Msps respectively. The actual network stream is measured;
there is no host-side decimation. Unsupported native division, configuration
errors, disconnects and missing reads are reported as failed runs.

STARTING discards two seconds after the first completed read. INITIALISING uses
the next read as T1, resets the counter and starts the requested interval. Reads
in COUNTING add their sample counts. Once the interval expires, EVALUATING uses
the next completion as T2 and adds that read's sample count exactly once. The
elapsed interval uses a monotonic clock; UTC timestamps identify read completions.
The T1 read is excluded, so the measured window contains every read completed
after T1 through and including T2. The table, CSV and diagnostic cumulative
counts use this same window for measured samples, average rate, dropped
percentage and estimated lost signal time.

After each run stops, the console receives its preallocated read diagnostic
log, including completion times, returned sample counts, read intervals and
cumulative delivery versus the expected rate. This reveals whether a pause is
followed by catch-up delivery or leaves a sustained deficit. Diagnostic capacity
exhaustion invalidates and stops that run instead of silently omitting reads.

The results dialog retains completed and failed runs, including partial results
after cancellation. It shows duration, rate, buffer size, effective duration,
expected and measured sample counts, measured rate, estimated dropped percentage
and estimated lost signal time. Significant deficits and errors appear in red;
the row status explains the assessment. Timing estimates do not establish which
device, driver or network component caused a deficit. A deficit is marked
significant when expected minus measured samples exceeds the larger of 0.1% of
the expected samples or 2 ms at the configured rate. The evaluation read is
already included in the measured count; no read-size allowance or additional
subtraction is applied to this deficit.
**Save CSV** exports the table through a file chooser, with confirmation before
replacing an existing file.

## Spectrum and waterfall

The application reuses `ComplexFft`, `Window`, `Spectrum`, `ExponentialAverager`,
`ComplexFloatRingBuffer`, `SpectrumFrame` and `SpectrumPanel`. Spectrum rendering
and frame construction are public reuse points. `SpectrumSettings` accepts powers of
two up to 131072. Inspector offers sizes 1024..131072, rates 0.5..30 Hz, and the
existing RECTANGULAR, HANN, HAMMING and BLACKMAN windows.

Hover over the spectrum or a populated waterfall row to see the absolute
frequency in MHz and signed offset from its center frequency in kHz. The readout
follows the cursor position and plot width; waterfall history retains each row's
frequency axis.

The manual min/max dB values also set the waterfall color scale. Values are
uncalibrated relative FFT-bin power: no dBm, FFT-size/window-gain or bandwidth
normalization is implied. None/Low/Medium/High/Highest averaging use linear-power
EMA weights 1, 1/2, 1/4, 1/8 and 1/16 respectively. Changing analysis parameters
resets the analysis history. Partial snapshots never become zero-padded FFTs.

One FFT worker owns its plans, reusable scratch and averaging. The source
callback publishes only bounded primitive latest-sample observations; it performs
no FFT or Swing work. Spectrum publication is coalesced to the latest immutable
frame, with at most 4096 reduced columns. The Waterfall group selects an integer
1..15 lines per second, default 4, independently of the spectrum update rate.
The waterfall adds newly processed rows at that cadence, without catch-up bursts
or repeated idle rows. Its pending queue and raster history are bounded (64 and
1024 rows). Its time axis uses the visible rows' observation timestamps, including
history drawn at different rates. The waterfall is independent of recording; dropping an overloaded
display update does not drop recorded samples. Snapshot contention/display
overruns are logged.

## IQ recording and loss reporting

The storage settings offer CF32 (default) and CS16. Both contain headerless
little-endian `I0,Q0,I1,Q1,...` **after** Inspector's filtering/decimation. CF32
stores IEEE float32 components, eight bytes per complex sample, preserving raw
float bits. CS16 stores signed 16-bit components, four bytes per complex sample:
each normalized float component is rounded after multiplication by 32768 and
saturated to -32768..32767. NaN becomes zero; infinities saturate to the matching
limit. Use the effective sample rate when replaying with an application supporting
the selected format; JSatDecode's `cf32` source supports CF32. No decoder
transformations are applied. The format is fixed for each recording.

```text
<actual center frequency Hz>_<effective samples/s>_<manual gain>_<UTC start>.<cf32|cs16>
137100000_500000_40_20261103T200000.cf32
137100000_500000_40_20261103T200000.cs16
```

The UTC start instant is captured once when recording starts and formatted as
`uuuuMMdd'T'HHmmss`: whole seconds, without fractional digits or a timezone suffix.
The remaining filename pattern is the same for both formats. The actual LO
readback comes from the existing source's monitoring data. Directories are
created as needed and `CREATE_NEW` protects existing files, including recordings
started in the same second with the same metadata and format.

Recording retains immutable float sample-block references in a bounded SPSC queue
(up to 64 MiB of retained float IQ and 65536 references), independently of the
selected file format. One independent writer serializes to a reusable 1 MiB
direct buffer. The acquisition callback does not perform disk I/O, serialization,
blocking queue insertion or console logging. Starting a file
opens it before publishing the recorder to acquisition. Stopping atomically
closes its submission gate and drains every accepted block, including an offer
concurrent with stop. Reception continues during storage setup and finalization.

Normal recordings preserve all accepted sample counts and order, with the chosen
format's representation. No finite buffer can guarantee losslessness if the
disk is indefinitely slower than the input. A full queue or disk error explicitly
stops that recording, logs the
failure, identifies the file as incomplete in the window, and leaves reception
running. A queue-overflow recording preserves its accepted contiguous prefix;
a partial failed write is truncated to a complete complex sample (eight bytes
for CF32, four for CS16) when the channel permits it. Window close may wait for
an operating-system write to return.

Management polling reports source failures, index discontinuities and all
IIOD-reported overruns to the console. The existing legacy IIOD protocol has no
hardware sample timestamps or comprehensive FIFO loss counter; hidden device or
kernel drops cannot be proven absent. Live PlutoPlus throughput and disk soak
qualification remain hardware-dependent. See [source limits](fmcomms2-source.md).

## Saved settings and checks

On normal window exit, all settings (including storage format and waterfall
lines per second), sidebar visibility and window dimensions are stored in
`%APPDATA%/JSatInspector/settings.properties` on Windows, or
`~/.jsatinspector/settings.properties` elsewhere (also the Windows fallback when
APPDATA is unavailable). Writes replace the file atomically where supported.
The RX buffer is saved as `rx_buffer_samples`. Older preferences containing only
`rx_buffer_bytes` are converted by dividing by four, preserving their receive
capacity; the sample-based key takes precedence when both keys exist.
Missing/invalid files restore defaults with a console diagnostic. Startup always
leaves connection and recording off. The JVM shutdown hook also joins acquisition
and storage and saves the last submitted valid settings on orderly termination.

Maven runs `InspectorSettingsTest`, `InspectorReceiverTest`, `IqRecorderTest`
and `InspectorAnalysisTest`, alongside all existing core suites. They cover
preferences, all rate/decimation combinations, streaming anti-alias filtering,
real loopback IIOD recording in both formats, exact binary data, bounded overload/disk
faults, concurrent stop/drain, maximum FFTs, averaging, cadence and Java2D rendering.
`InspectorWindowDesktopTest` is an opt-in desktop layout/control check; it uses
synthetic spectra and stub actions without connecting to a radio.
`InspectorApplicationDesktopTest` additionally verifies the actual launch/exit
and preferences lifecycle in a child JVM with isolated home/AppData directories.
Run `mvn -pl core verify` from the repository root for automated Inspector checks.
Desktop test runners require a graphical desktop; live reception and storage
measurements require a PlutoPlus and a suitable output device.
