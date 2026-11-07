# Expected receiver and signal properties

Lifecycle bookkeeping, the jradio DC push adapter, strict DC boolean parsing and
monitoring assembly use [decoder_base](../../decoder_base/README.md). Mission
DSP/framing, clock arithmetic and tap policy belong to this module.

This document describes the implementation and its reference parameters.
See [verification](qualification.md) for automated test coverage and recording
comparison procedures.

The framework's [authoritative decoder implementation specification](../../core/docs/decoder-implementation-specification.md)
defines the mandatory status schema. Hinode uses the public
`api.monitoring.DecoderStatusKeys` constants for all common metric names.

## Signal and framing

The [reference GRC](../grc/decoder_hinode_solar.grc) describes the signal chain.
Java runtime configuration is provided by
[`hinode-wav.properties`](../examples/hinode-wav.properties).

| Property | Value |
| --- | --- |
| Nominal RF | 2256.22 MHz |
| Input | Complex float I/Q, 500000 samples/s, interleaved immutable framework blocks |
| Symbol / coded bit rate | 65600 symbols/s, 7.6219512 input samples/symbol |
| Demodulation | Residual carrier PM tracking; Q extraction; -65600 Hz translation; BPSK |
| ASM | 1A CF FC 1D, MSB first, default maximum four bit errors |
| Convolutional code | K=7, rate 1/2; GNU Radio CCSDS polynomials [79,-109] |
| Polynomial notation | Bit-reversed octal 171 / inverted 133 for newest-bit-low shift registers |
| Randomizer | CCSDS x^8+x^7+x^5+x^3+1, reset to all ones after ASM; FF 48 0E C0 9A prefix |
| Outer FEC | CCSDS RS(255,223), dual basis, interleave 4; GF polynomial 0x187, first root 112, primitive step 11 |
| Encoded CADU | 4-byte ASM plus 1020 randomized/interleaved RS bytes |
| Published frame | 892-byte corrected AOS transfer frame, no ASM or RS parity |
| Ambiguities | Both coded-pair phases, normal/inverted BPSK polarity |
| Duplicates | Suppress exact matches among last three accepted frames; repeated idle frames can recur |

No sample-rate conversion or live Pluto decimation is implemented in this module.
Wrong sample rate, unsupported configuration and nonfinite samples fail explicitly.
Partial input blocks preserve every DSP/FEC state; there is no padding at EOF.
The last continuous-Viterbi survivor delay needs subsequent coded bits to resolve.

## DSP parameters and provenance

J = direct jradio reuse; A = adaptation using jradio; N = private native implementation.

| Stage | Provenance | Parameters / reason |
| --- | --- | --- |
| Initial DC blocker | J/A | Two independent jradio 1.121 long-form DcBlocker instances; averaging length 16384; default enabled |
| Complex AGC | N | Rate .001, reference 1, initial gain 1, maximum gain 65536; reference update equations |
| Carrier PLL | N | Bandwidth .03 -> .01 rad/sample, frequency bounds +/- .5 rad/sample; private loop exposes required transition/state |
| Q extraction and rotator | N | Input complex (0,Q), -65600 Hz at 500000 samples/s; renormalize every 512 samples |
| RRC FIR | N | Fixed symmetric 31-coefficient float filter in `HinodeDsp` |
| Order-2 Costas | N | Bandwidth .02 -> .005, I*Q clipped to +/-1, frequency bounds +/-1; private loop supports transition and frequency observation |
| Complex Mueller & Mueller | A/J | jradio MMSE eight-tap interpolator and circular history; push adaptation of ClockRecoveryMMComplex TED and 0/1 slicer |
| Timing loop | A | Omega 500000/65600; gain omega .0001 -> .000025; gain mu .03 -> .01; initial mu .5; omega relative limit .005 |
| Symbol gain | N | 5 |
| Continuous Viterbi | N | Four bounded 64-state register-exchange decoders, survivor depth 56; jradio's fixed-packet terminated decoder is unsuitable for continuous stream |
| ASM search | N | All bit alignments, overlapping candidates, 8192-bit history; 64 pending candidates/branch |
| Derandomizer | N | Independently checked against jradio CcittScrambler |
| Dual-basis RS | J | Instance per branch, no shared mutable static CCSDS decoder |
| SNR and independent lock detectors | N | M2M4 exponential moments, alpha .001; reference thresholds and hysteresis |

Acquisition transitions occur exactly after 6000000 input samples and 787200
recovered symbols (12 signal seconds). Numerical trigonometry, filter startup,
Viterbi survivor depth and transition boundaries affect recording output. The
receiver explicitly covers four unique phase/polarity hypotheses. Use an
independently established expected output for an external byte comparison.

## Observation and ownership

One source/functional worker owns all sample, symbol, FEC and tap writes.
`accept` performs synchronous DSP without creating workers or queues.
Lifecycle calls must be serialized with accept. Frame dispatch uses the supplied
bounded framework sink; sink exceptions fail the decoder and propagate upstream.
No Swing, network, filesystem, sleep, observer callback or logging occurs in DSP.
Immutable input is read via primitive indexed access; no per-sample objects.

RF is the unmodified 500 kS/s input, baseband is the 500 kS/s Costas output, and
symbols are the gain-scaled timing output at nominal 65.6 ksym/s. Fixed 4096-pair
staging arrays publish into bounded framework rings. End-of-block partial writes
make even short recordings observable. Rings default to 16384 complex samples
and never wait for observers. Empty snapshots under contention are normal.
Frame allocation occurs only for complete candidates, not every sample/symbol.

The runtime passes the complete decoder section with the `decoder.` prefix
removed, including `decoder.type` as `type`. Hinode accepts this selector and,
when present, requires its value to equal `hinode_s_tlm` exactly. It is optional
for direct programmatic construction. The framework/API does not strip or change
the selector; the decoder validates it locally.

Other accepted settings are `dcblocker=true/false` (default true),
`sample_rate_hz=500000`, `asm_errors=0..4` (default 4)
and `tap_capacity=1..1048576` (default 16384), under the decoder section. Unknown
settings and a mismatched type fail early with ConfigurationException.

`signal.snr_db` and `decoder.hinode_s_tlm.carrier_snr_db` are pre-clock complex
M2M4 results in dB, not calibrated RF power. Post-clock M2M4 is
`decoder.hinode_s_tlm.symbol_snr_db`. Invalid discriminant, insufficient data
(10000 samples) or undefined noise yields NaN; finite results are clipped to
[-30,60] dB. Reporting/lock updates use 0.5 signal seconds, independent of host
processing speed and observer cadence.
The mandatory SNR key is NaN while inactive. Diagnostic estimator fields may
retain their last values for post-run inspection.

Carrier lock uses the standard deviation of eight Costas frequency observations:
lock <= .0025 rad/sample with SNR >= -1 dB; unlock >= .01 or SNR <= -4 dB.
Symbol lock requires carrier lock and post-clock SNR >= 0 dB, unlocking below
-2 dB or when carrier unlocks. Both require three consecutive qualifying reports.
They never use frame success. Frame lock alone uses RS-valid activity and expires
after three signal seconds without an accepted frame. Stop/EOF clears all locks.

### Carrier offset / Doppler estimate

`carrier.offset_hz` is a DOUBLE status metric in Hz. It reports the residual RF
carrier PLL's tracked frequency, converted from radians per input sample as
`frequency * 500000 / (2*pi)`. It uses the initial carrier-tracking PLL, not the
Costas loop after Q extraction and the -65.6 kHz subcarrier translation.
Positive values mean the received residual carrier is above the input I/Q
centre frequency; negative values mean below. Approximate received RF is the
tuned RF centre plus this offset, when that centre is known.

The value is a Doppler estimate relative to the tuned centre, not an orbital
prediction or a calibrated Doppler-only measurement: receiver/transmitter
oscillator error and residual PLL modulation/data ripple are included. It is an
instantaneous PLL estimate sampled at the existing 0.5 signal-second lock/status
cadence, with no additional smoothing, FFT or estimator in the sample path.

The metric is finite only while the existing carrier detector reports LOCKED
and the decoder is running. SEARCHING, UNLOCKED, pre-start, stop/EOF, close and
failure return Double.NaN (not a stale last measurement or a false zero). Lock
and offset are published as one immutable pair so concurrent polling cannot
combine an unlocked state with an old finite offset. The generic framework status
table and monitoring distributors expose the metric using its Hz descriptor;
NaN denotes unavailable in monitoring, including the CBOR status stream.

Only the low-rate update reads the PLL frequency. It publishes one immutable
status pair per half signal second; observer snapshot construction occurs on
the polling caller. The offset metric does not feed back into DSP or framing.

`symbol.rate_baud` is nominal; `frame.rate_hz` is lifetime accepted frames per
processed signal second. `decoder.hinode_s_tlm.frames`, `duplicates`, `rs_valid`,
`rs_rejected` and `asm_candidate_drops` count accepted frames, recent duplicates,
pre-deduplication valid branches, uncorrectable complete candidates and bounded
search overflow respectively. A search overflow drops only an unvalidated ASM
hypothesis and is counted. Monitoring is independently published gauges, not an
atomic cross-metric transaction.

All ten mandatory keys and descriptors are available in configured, running and
terminal states. `frame.good` is the accepted deduplicated RS-valid frame count
(same as `decoder.hinode_s_tlm.frames`); `frame.bad` is the number of
complete ASM candidates rejected by RS across the four ambiguity branches
(same as `decoder.hinode_s_tlm.rs_rejected`). `frame.total` is their sum, captured
coherently within a snapshot. Exact recent duplicates and incomplete candidates
are excluded. Rejected branch hypotheses do not imply lost transmitted frames.
Counters/rate persist after stop; applicable locks clear and SNR/offset become NaN.
This accounting is observation-side only and does not feed into DSP/FEC decisions.

Annotations include frame type, quality GOOD, fec_valid, backend jradio, branch
0..3 (p0 normal/inverted then p1 normal/inverted), length, VCID (low six bits of
byte 1) and 24-bit VC counter (bytes 2..4). UTC epoch-nanosecond frame time refers to the
source sample resolving that frame. Reference-byte qualification ignores
host timestamps but requires identical frame count/order/contents.

## Initial DC blocker configuration

The receiver supports decoder.dcblocker=true (enabled) and decoder.dcblocker=false
(exact bypass). The default here is true. The factory reads the unprefixed
dcblocker boolean; malformed values fail construction. This is the first DSP
stage, before gain, translation, filtering or carrier recovery. Raw RF taps
retain original input samples before this stage.

Enabled operation uses ru.r2cloud.jradio.blocks.DcBlocker (jradio 1.121),
longForm=true and averaging length 16384, with separate filter histories for
I and Q. The shared `ComplexDcBlocker` class adapts pushed samples to the
library's FloatInput interface; it implements no filtering equations.
The library subtracts four cascaded moving averages from a delayed input.
The signal delay is 32766 input samples: 65.532 ms at Hinode's 500 ksps.
The averaging-window scale is inputSampleRate/16384 Hz; it is not a stated
-3 dB cutoff. The fixed length keeps the notch narrow for residual-carrier PM.

Histories start at zero and persist across input blocks. Each received complex
sample produces one filtered complex sample, with no per-sample allocation.
No EOF is supplied to the pull adapter, so jradio's optional zero tail is never
requested; pending delayed samples are not flushed after source EOF.
Raw RF taps retain original input samples. Frame timestamps identify the
source sample that resolves emission; no filter-delay correction is applied.
Changing the switch requires a new decoder. A carrier close to DC can be
attenuated along with the offset; qualify each setting against the actual signal.
