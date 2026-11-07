# Optional Swing monitoring

The monitoring window provides a generic status table, SNR-driven quality bar,
observer controller and headless fallback. It displays RF/baseband spectra
with Java2D plots; see [spectrum-ui.md](spectrum-ui.md), and a bounded I/Q
constellation; see [constellation-ui.md](constellation-ui.md). UI observation
runs independently of source and decoder callbacks.

From an extracted [distribution](../../distribution/README.md) on Windows:

~~~text
bin/jsatdecoder.bat --ui-demo
bin/jsatdecoder.bat --ui-demo --config examples/core/ui-demo.properties
bin/jsatdecoder.bat --config examples/core/dummy.properties --set source.file=C:/recordings/input.cf32 --set source.realtime=true --ui
~~~

The standalone demo needs no file, source, decoder or transport. It generates
synthetic SNR, independent lock states, counters and bounded IQ snapshots when
the observer polls. A recording run displays real generic monitoring and optional
tap spectra where available. The existing dummy decoder correctly reports unavailable SNR
and no taps; it is not modified to pretend to estimate signal quality.

## Activation and lifetime

Requested monitoring windows (including the demo) start fully maximised.
Configured width/height define the restored window size. The status table uses
one third of the central horizontal space on opening and resizing; plots use
the remaining two thirds. The divider can still be dragged manually.

Three labelled round LEDs sit to the right of the SNR bar on the same row and
display the independent carrier, symbol and frame
lock observations: red UNLOCKED, yellow SEARCHING, green LOCKED. Missing or
unrecognized observations display gray UNKNOWN. State text, tooltips and
accessible names accompany each LED so meaning does not depend on color alone.
RF spans the top plot row; baseband and constellation share the lower row.

UI is disabled by default. Normal precedence remains defaults, file, --set, then
dedicated --ui/--headless switches. --ui-demo is a separate action; --headless can
also disable that action's UI. OptionalMonitoringUi lazily loads the desktop
entry point only on an explicit enabled request. Disabled operation loads no
Swing implementation and starts no window, timer, observer or FFT worker.

A requested UI with a headless graphics environment warns once and decoding
continues. The same fallback works when java.desktop is absent. Window creation,
configuration or general observer/render failures warn and disable UI without changing
functional runtime state. A spectrum provider/analysis failure disables only
that spectrum; a symbol-analysis failure disables only constellation. Other
plots/status/quality continue. Fatal VM failures are not treated as recoverable UI
errors. There is no strict desktop-required option.

Closing the window stops only observation and disposes that session's window.
Source, decoder and distributors continue until normal EOF, failure or process
shutdown. The standalone demo exits after window close. At normal CLI completion
or shutdown-hook cleanup, the UI is stopped and the runtime is closed. Runtime
startup failure also cleans the previously opened UI. No System.exit is used by
the window and the UI holds no decoder lifecycle authority.

The core runtime implements Monitorable and exposes observation-only taps().
monitoringSnapshot() assembles source/decoder/output metrics plus actual runtime,
source and decoder lifecycle states (runtime.state, runtime.source_state and
runtime.decoder_state) on the polling caller. It does not take the
runtime lifecycle lock or run protocol code. monitoringDescriptors() returns
source/decoder metadata with decoder precedence for duplicate keys. Additional
runtime/output metrics have empty units. These observation methods preserve
source/decoder ownership and lifecycle boundaries.

## Threading and bounded display work

All component construction, table mutation, model application and disposal run
on the Swing EDT. The window consumes UiViewModel only and has no live provider
references. Formatting/quality calculations and provider/tap reads occur on one
owned daemon named jsatdecode-ui-observer. Synthetic sample generation also runs
there. Headless fallback never constructs that worker or initializes the EDT.

The poller uses fixed delay, with no queued catch-up ticks. It observes at the
maximum configured status/quality/spectrum/constellation rate, refreshes rows/tap metadata at status
cadence and smooths quality at quality cadence. It caches bounded descriptor
units once, supports dynamic snapshot keys and limits rows/text. Large list/map
metadata is summarized by item count; binary values show byte count and explicit
null displays as null. Nonfinite numeric values display as unavailable.

One AtomicReference holds the newest immutable view model. At most one update
runnable is pending on the EDT, in addition to a currently executing update.
New models replace pending models while a display is slow; there is no display
history or per-frame event queue. coalescedUpdates() reports replacement counts.
Functional frames and transport data are unaffected by display coalescing.

stop() is nonblocking and safe on the EDT: it disables updates and shuts down the
scheduler without interrupting provider/DSP code. close() on a management thread
also joins for at most two seconds and preserves interruption; on the EDT or
observer it only signals stop. A misbehaving provider cannot hold decoder
shutdown beyond this budget, though its daemon read may remain until it returns.
Providers must honor their bounded concurrent snapshot contract. A read already
executing at close may finish, but its model is never displayed. Disposal is
posted without waiting for the EDT, preventing shutdown/EDT deadlocks.

## Status and plots

The table displays Metric, Value and Unit, supports sorting, and is read-only.
Snapshots may be weakly consistent. Unknown descriptors are displayed without
units; no mission-specific metric list is compiled into the window. If the row
bound is exceeded, the footer states how many additional metrics were omitted.
Text bounds apply to metric, value and unit fields.

RF/baseband panels show shifted FFT spectra with frequency and relative-dB axes.
Missing taps are normal. Empty/partial snapshots (including ring contention)
retain the latest complete trace, or wait if none has been analyzed. Only
bounded immutable reduced columns cross to Swing. FFT/window plans, primitive
scratch and independent linear-power averaging stay on the observer worker.
Constellation renders bounded finite recent symbol pairs with equal-axis fixed
or auto scaling, grid, persistence and optional reference markers. Its history
and conversion stay on the same observer worker. See spectrum-ui.md and
constellation-ui.md for exact analysis and persistence policies.

## Relative quality and independent locks

Common decoder measurements are read using public `DecoderStatusKeys` constants.
The generic status table displays all mandatory decoder metrics, including frame
good/bad/total and carrier offset, from the descriptor-driven schema. Decoder
authors must implement [the authoritative contract](decoder-implementation-specification.md);
the UI retains safe UNKNOWN/NaN fallback for incomplete/failing providers rather
than inventing missing observations.

signal.snr_db must be numeric and finite. Missing, string, NaN or infinite SNR
shows Unavailable rather than zero quality. An invalid observation clears the
smoothing history; the next valid observation starts from its own score.
Raw numeric SNR remains visible alongside the smoothed percentage.

SignalQualityProfile uses piecewise-linear thresholds at 0, 25, 50, 75 and 100
percent, clamping outside the endpoints. The default -5/0/5/10/15 dB profile is
a generic relative display scale, not a universal decoder performance model.
Configure the profile for the actual decoder and its documented SNR estimator.
It never tunes DSP or claims frame validity.

Smoothing is alpha*current + (1-alpha)*previous, default alpha=0.25 at 8 Hz.
The bar uses numeric percentage and SNR as well as color. Carrier, symbol and
frame states are independently visible; recognized strings/enums are LOCKED,
UNLOCKED, SEARCHING and UNKNOWN, and booleans map directly to locked/unlocked.
Missing or unrecognized states remain UNKNOWN. No lock is inferred from another.
Adjacent confidence text marks SNR as unconfirmed until carrier is actually
locked, then distinguishes carrier/symbol/frame confidence without changing the
SNR score or concealing the separate actual lock values.

Supported properties, all prefixed ui. in global configuration:

| Key | Default | Valid range/meaning |
| --- | --- | --- |
| enabled | false | Explicit optional UI activation |
| refresh.status_hz | 4 | 0.5..20; rows and tap metadata |
| refresh.quality_hz | 8 | 0.5..30; SNR/quality |
| maximum_metrics | 256 | 1..4096 rows |
| maximum_text_characters | 256 | 16..4096 per text field |
| tap_samples | 1024 | 0..65536; legacy metadata-only controller requests |
| quality.alpha | 0.25 | (0,1] smoothing weight |
| quality.unusable_snr_db | -5 | Finite 0-percent threshold |
| quality.poor_snr_db | 0 | Finite 25-percent threshold |
| quality.fair_snr_db | 5 | Finite 50-percent threshold |
| quality.good_snr_db | 10 | Finite 75-percent threshold |
| quality.excellent_snr_db | 15 | Finite 100-percent threshold |
| width | 1180 | 640..4096 restored window pixels; starts maximised |
| height | 800 | 480..2160 restored window pixels; starts maximised |

Quality thresholds must be strictly increasing. Spectrum settings are
listed in spectrum-ui.md; symbol settings are listed in constellation-ui.md.

## Verification

MonitoringUiTest runs without a physical display. It exercises profile/smoothing,
invalid SNR and independent locks, off-EDT provider/tap access, bounded immutable
models, slow-EDT coalescing, failure isolation, joined shutdown, identical file
frames with observation enabled/disabled, and child-JVM CLI fallback. Children
also verify no UI threads and no desktop implementation load when disabled.

Maven verify runs MonitoringUiTest, SpectrumUiTest and ConstellationUiTest
alongside the other core suites. Swing tests use java.desktop and run without
a physical display.

The separate desktop test creates a real window, checks refresh, captures an
image, closes the window and verifies an independent file decoder still runs:

~~~powershell
java '-Djava.awt.headless=false' -cp 'core/target/classes;core/target/test-classes;core/target/test-dependencies/*' eu.dariolucia.jsatdecode.ui.swing.MonitoringUiDesktopTest core/target/monitoring-ui.png
~~~

Run this command from the repository root after compiling tests. A graphical
desktop is required. Live-decoder CPU/throughput measurements require the actual
decoder and input stream.
