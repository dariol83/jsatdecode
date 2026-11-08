# RF/baseband spectrum display

The optional monitoring window renders RF and baseband complex taps as shifted
spectra. The synthetic demo supplies a +6000 Hz RF tone at 48000 samples/s with
center 137100000 Hz, and a -1500 Hz baseband tone at 12000 samples/s. Their peaks
appear at 137.106 MHz and -1.5 kHz. The dummy decoder still supplies no taps;
missing taps are normal. For constellation drawing, see [constellation-ui.md](constellation-ui.md).

## Analysis and ownership

The existing single daemon jsatdecode-ui-observer is also the FFT analysis worker.
It pulls each spectrum independently at its configured target cadence, using
fixed-delay scheduling without queued catch-up work. Status and quality retain
their own deadlines. RF and baseband own independent reusable ComplexFft/Window
plans, four N-element double scratch arrays, reduction storage and optional
linear-power averaging history. Plans and scratch are allocated lazily on the
first complete, visible snapshot. No DSP/source callback invokes FFT or
Swing. Disabled/headless/missing-desktop operation starts no UI/FFT worker.

Each observation requests exactly N complex samples. A tap must retain at least
N samples to yield a complete spectrum. Empty snapshots, including bounded ring
contention, and partial snapshots are skipped without zero padding or advancing
averaging. Before the first complete observation the panel waits; afterwards it
retains the latest complete trace. Its timestamp remains that of the analyzed
snapshot. Missing taps clear the trace and averaging; reappearance waits for a
complete snapshot. Invalid samples/axes clear the trace and history and show an
invalid-input message. Rate, RF center or absolute/relative-axis changes reset
history. A provider/analysis exception warns once and disables only that spectrum
for this session; the other spectrum, status and quality continue. General
monitoring/display failures disable UI observation as described in [Swing monitoring](swing-ui.md).

ComplexSnapshot.i/q offer immutable indexed reads so analysis does not allocate
an additional IQ-array copy. Existing snapshot construction and iq() copying
semantics remain intact. After windowing, the unnormalized forward FFT produces
power, FFT shift, optional EMA and relative dB. Maximum-per-column reduction
retains narrow peaks. Column groups use the floor-boundary convention in [spectrum utilities](spectrum.md);
the representative frequency is the center of each original bin group, so
reduced peak location is accurate to its display column, not individual bin.

The worker copies only the bounded reduced primitive trace into SpectrumFrame.
UiViewModel copies its trace list; frames expose indexed reads and no arrays.
One latest-model slot and at most one queued EDT callback bound display backlog.
Old displayed frames remain immutable when scratch is reused or the window is
resized. The EDT publishes drawable widths through atomic integers. The worker
never reads Swing dimensions. SpectrumPanel reuses a Path2D and paints only its
immutable frame, axes and labels; no FFT, provider reads, reduction, large
temporary arrays or per-bin objects occur in painting.

## Frequency and amplitude

Shifted bin k represents center + (k-N/2)*Fs/N over [-Fs/2,+Fs/2).
Baseband always uses offset Hz. RF center precedence is:

1. Explicit ui.spectrum.rf_center_frequency_hz, including zero.
2. Finite positive numeric source.center_frequency_hz, then source.center_frequency.
3. Unknown center: relative offset Hz.

The absolute RF axis shows MHz for MHz centers; other axes use kHz or Hz according
to span. The right axis boundary is +Fs/2; no FFT bin exists at that endpoint.
Y values are 10*log10(|FFT(window*IQ)|^2), floored for display. They are relative
dB referenced to one, never calibrated dBm or PSD. No FFT-size, coherent-gain,
window-energy, bandwidth or hardware normalization is applied. For a bin-centered
complex tone of amplitude A, the peak is approximately
20*log10(N*A*window.coherentGain()). Changing N/window therefore changes amplitude.
The optional EMA averages linear power, not decibels. Each spectrum independently
adapts its vertical range on its first complete trace, then once per second on
the next available complete observation: minimum analyzed bin level minus 10 dB
through maximum analyzed bin level plus 10 dB. Extrema use all bins after any
averaging and numerical flooring, before maximum-per-column reduction. Empty or
partial observations retain the previous trace/range; stream/axis resets initialize
the range afresh. The observer worker computes limits and publishes them in the
immutable frame; no scan, FFT or adaptation occurs in painting or DSP.

## Configuration

All keys below belong to global ui.* (SpectrumSettings reads the unprefixed UI section).
Invalid settings fail optional UI startup, preserving headless decoding.

| Key | Default | Bounds/meaning |
| --- | --- | --- |
| fft.size | 4096 | Power of two from 512 through 131072; larger sizes also support the standalone JSatInspector |
| fft.window | HANN | HANN, HAMMING, BLACKMAN, RECTANGULAR; case insensitive |
| refresh.spectrum_hz | 15 | RF target, 0.5..30 Hz |
| refresh.baseband_hz | 15 | Baseband target, 0.5..30 Hz |
| spectrum.average_alpha | 1 | (0,1]; base EMA weight; RF smoothing divides it by the selected factor (default 8) |
| spectrum.floor_db | -140 | Numerical dB floor before adaptive range calculation; finite, >= -1000 and below ceiling |
| spectrum.ceiling_db | 80 | Fallback limit before a complete trace; adaptive range replaces it; finite, <= 1000 and above floor |
| spectrum.maximum_columns | 2048 | 1..4096; actual count also bounded by width and N |
| spectrum.rf_center_frequency_hz | metadata/relative | Explicit finite nonnegative Hz |

ui.tap_samples bounds tap metadata requests in status-only/spectrum-only
controller configurations. The complete window uses constellation.points for
symbols. MonitoringUiController constructors select metadata-only observation
or enable spectrum and constellation analysis.

## Verification

SpectrumUiTest covers all supported sizes/windows, positive/negative/DC/Nyquist
tones, amplitude convention, peak retention, immutable models, linear-power EMA,
partial/empty/missing/invalid input, resets, worker/EDT ownership, bounded display
coalescing, RF failure isolation, resizing and actual Java2D rendering. Paced file
decoding with FFT running produces byte-identical payloads and annotations to
the UI-disabled run. Headless/fallback tests cover optional desktop loading.
MonitoringUiDesktopTest checks both READY spectra, captures the real window and
verifies window close leaves an independent file runtime running.

Constellation observations share the observer worker while keeping their own
cadence and bounded buffers. Mission decoding and hardware throughput require
the relevant decoder and source checks.

## Interactive RF controls

Hover over the RF plot to see the cursor's absolute frequency in MHz and signed
offset from the center frequency in kHz. The tooltip is available when a complete
spectrum with a known RF center is displayed.

Below the RF spectrum, Auto range starts enabled and uses the
once-per-second adaptive vertical range. Disable it to enable minimum/maximum
sliders (initially -150 and 0 relative dB, adjustable from -200 to 100).
The limits remain strictly ordered and take effect immediately, including when
observations are paused. Enabling Auto range restores the current adaptive range.
These display limits do not change the numerical FFT floor or decoder processing.

The RF FFT smoothing combo offers Lowest, Low, Medium, High (default) and Highest.
Their averaging factors are 1, 2, 4, 8 and 16: each divides the configured
spectrum.average_alpha by that factor for linear-power EMA on the observer worker.
High starts selected and active, giving alpha=0.125 with the default configured alpha=1.
Changing levels resets RF averaging history; Lowest preserves the configured
implementation. Baseband averaging is unchanged. Controls apply to the current
window; selections are not saved.
