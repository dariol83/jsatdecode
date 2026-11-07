# Constellation display

The optional monitoring window renders the SYMBOLS complex tap in one custom
Java2D panel. Horizontal position is I; positive Q points upward. The synthetic
demo supplies four noisy unit-energy QPSK clusters near (+/-0.707, +/-0.707).
The dummy decoder still supplies no symbols; unavailable taps are normal.

## Bounded observation and persistence

The existing jsatdecode-ui-observer daemon pulls symbols at an independent
configured target cadence. No source/decoder callback or EDT method reads taps,
converts snapshots or manages symbol history. The scheduler has fixed delay and
no catch-up queue. A symbol-provider failure warns once and disables only the
constellation for this session; both spectra, status and quality continue.
General monitoring/display failures disable UI observation as described in
[Swing monitoring](swing-ui.md).

Each observation requests at most constellation.points samples (default 1024).
History is a reusable primitive ring with capacity points*history_frames, where
history_frames defaults to one and is a capacity multiplier, not a strict count
of observations. Partial snapshots append finite pairs, evicting oldest pairs
when full; a full snapshot replaces the previous contents at the default bound.
Choosing a larger multiplier retains earlier observations too. Oldest-to-newest
display uses four progressively stronger colors. Empty snapshots, including ring
contention, preserve the last complete model and timestamp. History persists
between repaints without depending on repaint frequency. There is no unbounded
screen accumulation, time-based decay timer or Swing object per symbol.

Consecutive snapshots with the same timestamp are not appended again. The
existing ring supplies its last-write timestamp. Other providers should also
identify unchanged observations with an unchanged timestamp. The tap API has no
sample sequence offsets, so partially overlapping snapshots with new timestamps
can repeat symbols in history; the display does not claim unique-symbol counts.

Missing taps clear history and show unavailable. Reappearance waits for symbols;
sample-rate changes also clear history. Nonfinite I/Q pairs are skipped together.
An observation with no finite pairs clears history and shows invalid input;
the next finite observation starts cleanly. Oversized snapshots violate the tap
contract and disable only the constellation. Partial nonempty snapshots need no
padding and can be displayed immediately.

The worker reuses ring and linear scratch storage, allocated lazily on first
finite input. ConstellationFrame owns a copy of only the retained primitive IQ
values and exposes indexed reads, never arrays. Older frames survive ring/scratch
reuse. The existing latest-model slot and bounded EDT notification coalesce slow
displays. At the maximum configuration, ring, scratch and each frame contain at
most 131072 complex points; default frames contain at most 1024. Buffers do not
grow with run length. Closing the UI stops and joins the shared observer under
the existing management budget without touching runtime/DSP ownership.

## Scaling and rendering

The plot is square in pixels with the same symmetric half-range on I and Q,
including non-square/resized panels. The grid and axes run through zero.
Automatic scaling uses 1.1 times the largest absolute I or Q in retained history,
with a 1e-12 minimum half-range and a half-range of one for all-zero data.
Fixed scaling uses the configured positive half-range and clips outliers in the
renderer; sample values are never normalized or changed. No gain or recovery
setting in the decoder is affected. Optional BPSK references are (+/-1,0);
QPSK references are (+/-1/sqrt(2),+/-1/sqrt(2)). They are display hints, not
classification, lock detection or demodulation.

ConstellationPanel paints immutable frames, a grid, labels, reference markers
and points directly. It has no provider, worker history, per-point allocation or
child Swing component. Plot scaling is computed in analysis; drawing maps model
coordinates into the current pixel square. Very small panels can skip drawing.
Headless/disabled/missing-desktop startup creates no constellation worker or EDT.

## Configuration

All keys below are prefixed ui. globally; ConstellationSettings reads the
unprefixed UI section. Invalid settings disable optional UI startup, leaving
functional decoding available.

| Key | Default | Meaning/bounds |
| --- | --- | --- |
| refresh.constellation_hz | 15 | Target observation cadence, 0.5..30 Hz |
| constellation.points | 1024 | Samples per snapshot, 1..16384; 512/1024/2048/4096 are typical |
| constellation.history_frames | 1 | History capacity multiplier, 1..8 |
| constellation.scale_mode | AUTO | AUTO or FIXED, case insensitive |
| constellation.scale | 1.5 | Fixed half-range, finite 1e-12..1e30; validated in either mode |
| constellation.reference | NONE | NONE, BPSK or QPSK, case insensitive |

The complete window uses constellation.points for symbol observations.
Status-only/spectrum-only controller constructors use ui.tap_samples for tap
metadata. UiViewModel.constellation() is null when symbol analysis is disabled.

## Verification

ConstellationUiTest uses synthetic BPSK/QPSK sets, checks exact symbol coordinates
and rendered pixels at wide/tall sizes, and verifies equal scaling, fixed/auto
ranges, persistence order, wraparound, maximum bounds, immutable frames,
missing/empty/partial/invalid input, timestamp deduplication and resets. It also
checks independent cadence, off-EDT provider access, EDT-only publication,
coalescing, isolated symbol failure, continued spectrum/quality observation and
joined shutdown. Paced file decoding with symbol/FFT analysis running preserves
the UI-off payloads, order and annotations. The real desktop test captures the
QPSK panel and confirms window close leaves an independent runtime active.

These tests cover observation behavior. Mission DSP, FEC and live reception have
separate checks in the decoder modules and require suitable recordings or hardware.
