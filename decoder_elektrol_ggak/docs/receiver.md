# Elektro-L GGAK receiver

Factory ID **elektrol_ggak**, package `eu.dariolucia.jsatdecode.elektrol`, Java 21,
jradio **1.121**. This independent module implements the existing Decoder and
DecoderFactory APIs and registers its factory through ServiceLoader. Numerical
DSP, settings, framing, tests, examples and reference artifacts remain here.
The root reactor includes this module; its factory is discovered through ServiceLoader.

## Authority and signal

[decoder_elektrol_ggak.grc](../grc/decoder_elektrol_ggak.grc), generated with GNU Radio
3.10.12.0, is the normative DSP/framing reference, receiver **v1.5**. Its SHA-256 is
`42c500815be19bc629b512ec771a8d6f45daa8d091741174cbd5dc6c92bd8694`.
The [decoder implementation specification](../../core/docs/decoder-implementation-specification.md)
and [framework specification](../../core/specs/JSatDecode_Specification.md)
define integration; actual core sources resolve API ambiguities.

The reference receives residual-carrier PM with a **5000 symbol/s** BPSK
component. It tracks the carrier, retains `j*imaginary`, shifts by **-5000 Hz**,
then recovers BPSK phase and timing. One recovered symbol supplies one hard bit.
The receiver does not infer modulation index, pulse shape, polarity, channel
coding or filter settings from particular recordings.

The receiver accepts a single marker, collects the following fixed payload, and
resumes sliding search, following the GRC. The GRC defaults to the centred
1693.000 MHz, 1 MS/s CS16 recording. Its disabled Pluto path uses a 1692.750 MHz
LO and 2 MS/s; the module's Pluto example uses 1693.250 MHz and 1 MS/s delivered
input. RF tuning belongs to the source. See
[signal characteristics](Space_to_Ground_Signal_Characteristics.md) for the
implemented profile and its limits.
Acquisition uses two complete AFC FFT blocks before an estimate can be accepted;
the AFC delay and later buffering run on the input sample clock. The framing
search/hold threshold state is retained independently of status.

## DSP chain and provenance

J = unmodified jradio function; A = streaming adaptation/composition;
N = module code; Core = shared framework function. No DSP stages from other
mission decoders are substituted.

| Stage | Origin | Reference parameters and implementation |
| --- | --- | --- |
| First optional complex DC blocker | J/A | `decoder_base.ComplexDcBlocker`, independent long-form jradio DcBlocker for I/Q; default bypass, length 16384 |
| RF translation to decoder centre | Core | Existing `source.output_frequency_offset` rotator before decoder input; no duplicate RF mixer in this module |
| Channel LPF and rate reduction | J/A | Firdes Hamming low-pass, unity gain, 60 kHz cutoff / 20 kHz transition, FIRFilter convolution; decimation inputRate/250000 |
| Blind carrier acquisition/AFC | N/Core | Complex square, Hann-windowed 65536-point FFT at 250 kS/s, up to eight power spectra averaged; acquire after two FFT blocks, search carrier +/-30 kHz, minimum line confidence 12 dB, retune hysteresis 40 Hz; phase-continuous derotation with 131072-sample delay |
| Timing-rate LPF and decimation | J/A | Hamming low-pass, unity gain, 20 kHz cutoff / 4 kHz transition at 250 kS/s, decimation 5 |
| Residual-carrier PLL | J/A | ControlLoop and fastAtan, bandwidth .002 rad/sample, damping sqrt(1/2), limits +/-0.025 rad/sample (approximately +/-199 Hz); phase before loop update derotates the current sample |
| PM-to-BPSK | N | Exact GRC operation `j*Q * exp(-j*2*pi*5000*n/50000)`; double phase accumulator starting at zero |
| Complex RRC | J/A | Firdes rootRaisedCosine, unity gain, 5000 baud at 50 kS/s, rolloff .5, exactly 31 taps; FIRFilter |
| Power normalization | N | Reference power estimate in complete 250-sample segments at 50 kS/s; time constant .25 s; divide each segment by square root of smoothed mean power; silence at power <=1e-30 emits zeros without changing the estimate |
| BPSK carrier recovery | J | CostasLoop, order 2, bandwidth .005 rad/sample, use_snr=false |
| Complex symbol timing | J/A | MMSEFIRInterpolator, CircularComplexArray and branchlessClip with adapted complex M&M control; omega 10, gain_omega .000025, mu .5, gain_mu .02, relative omega limit .005; bounded streaming scheduler |
| Hard slicing and deframing | N | GRC embedded deframer translated to a bounded rolling 32-bit marker and a 220-byte payload buffer |

All decoder inputs are complex float samples. Rate progression at the default
input rate is **1000000 /4 = 250000 /5 = 50000 -> 5000 nominal symbols/s**.
The GRC 2 MS/s profile uses first decimation 8 and retains all later
rates and coefficients. Filter lengths at 1 MS/s are 121, 151 and 31; at
2 MS/s they are 241, 151 and 31. Initial FIR history is zero. Only complete
decimation groups are emitted. State survives arbitrary input partitions.

The AFC scores the squared-signal carrier line together with its +/-10 kHz
partners and interpolates the winning log-power peak. Its acceptance confidence
compares carrier-bin power with the median spectrum floor. These internal AFC
estimates do not supply a qualified lock detector or change monitoring values.
The analog AGC from earlier reference versions is removed; normalization follows
the RRC and precedes the Costas loop.

The PLL phase detector wraps phase error to [-pi,pi], integrates frequency and
phase using jradio ControlLoop, wraps phase and limits frequency. The second
Costas loop operates on the translated telemetry component and must never be
misreported as the original received RF carrier offset.

### Numerical identity and finite input

The chain, parameter values, tap designs, update order, slicing and frame
semantics follow the GRC. Qualification compares each stage at zero lag without
phase correction and checks exact output payloads. This does not imply
bit-identical floating-point samples for all possible inputs: GNU Radio uses
VOLK convolution and native float trigonometry, while jradio uses Java float
accumulation and Java Math. Small rounding differences can affect timing phase
bins or decisions near a slicer threshold. Stage comparison bounds and test
procedures are described in [verification](qualification.md).

The M&M adapter buffers only genuine samples, preserving GNU Radio's interpolation
lookahead with a deterministic one-output forecast. jradio's complete ClockRecoveryMMComplex
does not expose the current omega or input cursor needed to reproduce that
lookahead. The adapter therefore reuses jradio's eight-tap MMSE interpolation and
clipping while implementing the standard complex M&M decision/control equations
and the reference input guard. Its bounded ring holds 64 complex samples; its
interpolation history begins with two zeros and six real samples. Processing
requires `available >= ceil(omega+8)+16-2` relative to the interpolation cursor:
eight interpolation taps, GNU Radio's 16-sample guard, and two history samples.
The first symbol therefore requires 32 genuine timing-rate samples at omega 10.
The equations, history and forecast are traced to the
[GNU Radio 3.10.12 M&M implementation](https://github.com/gnuradio/gnuradio/blob/v3.10.12.0/gr-digital/lib/clock_recovery_mm_cc_impl.cc).

**Reference scheduling ambiguity:** the embedded GRC AFC analyses all FFT blocks
available in a `work` call before mixing that entire call. The normalizer also
starts its segments at each `work` boundary, which can leave a short segment.
Their numerical update positions therefore depend on scheduler batches.
Java defines these positions on a deterministic sample clock: it analyses each
complete FFT block before producing that block's final delayed output sample,
and buffers each complete 250-sample RRC segment before emitting its normalized
samples as a burst. Both rules are independent of `accept` block sizes. The
independent reference harness applies these same scheduling rules while reusing
the embedded GRC analysis and normalization equations.

GNU Radio also forecasts M&M batches, and its last available symbol can depend
on source delivery and scheduler batch size. The Java adapter uses a deterministic
single-output forecast. A GNU Radio scheduler can expose an additional terminal
symbol on a short stream, so universal scheduler output identity is not
guaranteed. Stop/EOF does not append zero samples, drain the AFC delay, flush a
filter tail, release a partial normalization segment or emit an incomplete payload.
The [verification tools](qualification.md#mm-finite-input-behavior) compare
finite-input timing behavior independently.

### DC blocker

`decoder.dcblocker=false` bypasses input I/Q with exact raw float bits and is the
default for GRC comparison. Enabled operation is the first decoder-owned stage,
before channel filtering or gain/phase recovery. Two independent jradio long-form
filters use four moving-average sections and a delayed original path. Their
averaging length is `dcblocker_length`, default 16384, with delay
**2*(length-1)** input complex samples. History starts at zero and persists
across blocks; no per-sample objects or library EOF padding are used. Changing
this switch or length requires a new decoder.

The runtime rotator precedes decoder input. Consequently, enabling DC rejection
after translating a residual carrier to DC can attenuate that useful carrier.
Enabled-path DC removal and partition invariance are tested separately; the
reference frame comparisons use bypass. The RF tap exposes the original
decoder input before the blocker, after any runtime source rotation.

## Configuration

Factories receive the full **unprefixed decoder section**, including optional
`type`. Present type must be `elektrol_ggak`; omission is accepted for direct
construction. Unknown keys, malformed values, nonfinite rates and inconsistent
thresholds fail before processing. All table keys gain `decoder.` in properties.

| Key | Default | Accepted values / meaning |
| --- | --- | --- |
| type | runtime selector | elektrol_ggak |
| sample_rate_hz | 1000000 | Integer multiple of 250000 from 250000 through 16000000; first decimation derived automatically |
| dcblocker | false | Strict boolean; lifetime DC-removal switch |
| dcblocker_length | 16384 | Integer 2..65536; independent I/Q moving-average length |
| sync_search_errors | 2 | Integer 0..15 mismatches in a 32-bit marker before first complete frame |
| sync_locked_errors | 4 | Integer search_errors..15; sticky threshold after first complete frame |
| tap_capacity | 16384 | Integer 1..1048576 complex samples per tap |

Other sample rates are rejected; no arbitrary-rate resampler silently alters
the GRC chain. The source's delivered sample rate must equal the configured
decoder rate; the user is responsible for aligning WAV and Pluto output rates.
Every input block is checked against this rate. Source indices must be contiguous;
nonfinite I/Q and discontinuities fail decoding rather than corrupting state.
Loop/filter parameters are fixed to the validated reference instead of exposing
unqualified variants. Source settings, frequencies, transport and UI settings
do not enter DSP configuration.

### Examples

[CS16 reprocessing](../examples/elektrol-ggak-cs16.properties) uses interleaved
little-endian signed 16-bit I/Q at 1 MS/s, source pacing and repeat disabled,
and DC removal bypassed. This raw format carries no sample-rate header, so set
the source and decoder rates to the actual capture rate. The example assumes
a centred 1693.000 MHz capture with no source rotation.

[WAV reprocessing](../examples/elektrol-ggak-wav.properties) uses IEEE float32
stereo IQ at 1 MS/s, source pacing disabled, repeat disabled, and synthetic frame
and monitoring distribution. The WAV header must match the requested source rate.
Synthetic distribution consumes frames without retaining an output frame file.
Use the core `--store` option when persisted output is required.

[Pluto SDR](../examples/elektrol-ggak-pluto.properties) uses `fmcomms2`, an LO
of **1693250000 Hz**, PHY rate **4000000**, native FPGA decimation **4**, and
decoder input **1000000**. The existing core source verifies hardware readback
and has no software fallback for unsupported FPGA decimation. Filter source
`Off` is supported by the actual core source; the GRC's `Auto` option is not.
Gain 40 dB, bandwidth 1.5 MHz and the URI are source acquisition settings.
Frames/status use ZMQ ports 5555/5556 and the generic monitoring UI is enabled.

At this LO, a 1693.000 MHz signal lies at **-250 kHz** in IQ. The core rotator
defines positive offsets as multiplication by `exp(+j*2*pi*f*n/Fs)`;
`source.output_frequency_offset=250000` centres it. A centred recording instead
uses offset zero. No source RF translation is implemented again in the decoder.

## Framing and output

Slice real(symbol)>=0 to one, otherwise zero. Slide a 32-bit window and compare
both normal and complemented `0x1ACFFC1D`. Accept the smaller Hamming distance
within the active threshold; a tie selects normal polarity. After a marker,
collect the next **1760 bits**, normalize every bit with the detected polarity,
and pack MSB first. The full CADU is **1792 bits / 224 bytes**, including the
four-byte ASM; the emitted GGAK frame is **220 bytes**, excluding that marker.
The reference replaces marker bits with the canonical ASM internally; they
are omitted from output here as well.

Collection is non-overlapping; markers inside a payload are data. After a
complete payload, resume sliding marker search. The first complete candidate,
including one rejected by its checksum, permanently selects the locked error threshold for this decoder instance,
including after gaps. This sticky GRC state is separate from monitored frame
activity. There is no additional marker confirmation, CRC, Reed-Solomon,
Viterbi, randomization, differential decoding, deinterleaving or payload parsing.
Repeated transmitted payloads are retained. There is one decision hypothesis
and no replay of candidates needing duplicate suppression.

### Mandatory additive checksum

After full polarity normalization and ASM removal, sum the **unsigned** octets
at GGAK offsets **0..217**, add **0x0202**, and retain the low 16 bits:

```text
expected = (0x0202 + sum(frame[0..217])) & 0xffff
received = (unsigned(frame[218]) << 8) | unsigned(frame[219])
```

Only candidates with `expected == received` reach the functional sink and its
distributors or recordings. Output remains the full **220 received bytes**,
including the original checksum octets at 218..219; no payload bytes or checksum
fields are repaired or rewritten. This empirical additive rule is not a CRC or
an error-correcting code. Its coverage excludes the ASM and checksum octets. It
uses neither end-around carry nor complement. No fill/type exemption or disable
switch is supported by the reference.

A mismatch counts one complete candidate in `frame.bad`, leaves carrier/timing
recovery state intact, and does not refresh the accepted-frame activity timestamp.
The next marker is searched normally with the GRC's sticky hold threshold. The
checksum rejects corruption it detects but cannot detect byte reordering or
compensating errors; its applicability and additive constant remain empirical
as stated in the signal-characteristics document.

Each immutable AnnotatedFrame contains frame_type=GGAK, quality=UNKNOWN,
frame_length=220, asm_errors, cadu_length=224, polarity_inverted,
input_sample_index, rx_time_ns, rx_time_source=sample_clock, and
timestamp_reference=last_payload_symbol_causal_input. It additionally carries
**checksum_valid=true** (Boolean) and **checksum** (Integer, unsigned 16-bit
received value). `quality=UNKNOWN` remains the generic quality label; checksum
validation is explicit and does not assert guaranteed error-free content.

Frame UTC is the source block timestamp plus the checked offset of the causal
input sample producing the last payload symbol. It includes AFC delay,
FIR/timing latency and the complete-segment normalization batching;
no estimated delay is subtracted and it is not an asserted original RF arrival
time. The recorded-source core's processing-start sample epoch is preserved when
no original capture UTC exists. Tap/status host observations use UtcTime.
Sink exceptions propagate and fail the decoder; accepted counters advance only
after successful functional delivery.

## Monitoring and observation

All ten DecoderStatusKeys have fixed descriptors and typed, non-null values in
configured, running, stopped, failed and closed states. Snapshot allocation occurs
on the polling caller. No monitoring decision feeds back into functional DSP.

| Key | Type / unit | Meaning |
| --- | --- | --- |
| signal.snr_db | Double / dB | NaN; reference defines no supported SNR estimator, smoothing, cadence or calibrated useful range |
| carrier.lock | String | UNKNOWN; running carrier tracking has no qualified independent lock detector |
| carrier.offset_hz | Double / Hz | NaN without a supported lock detector; no fabricated subcarrier/Costas offset |
| symbol.lock | String | UNKNOWN; M&M timing has no qualified independent lock detector |
| symbol.rate_baud | Double / baud | Known nominal 5000, including after shutdown |
| frame.lock | String | SEARCHING while running before first accepted frame; LOCKED after checksum-valid delivery; UNLOCKED after >3 input signal seconds without an accepted frame or when inactive |
| frame.good | Long / frames | Checksum-valid 220-byte GGAK payloads successfully handed to sink |
| frame.bad | Long / frames | Complete 220-byte candidates rejected by the additive checksum; not a count of lost transmitted frames |
| frame.total | Long / frames | Captured good+bad with checked arithmetic |
| frame.rate_hz | Double / Hz | Lifetime good / processed input signal seconds; zero before any samples |

Unmatched noise, rejected marker windows and unfinished payloads are excluded
from classified totals. Counters survive lock loss and shutdown. Frame good
does not imply network subscriber delivery or durable storage. Source activity,
output counters, backpressure and transport status belong to core/distributors.

Bounded taps expose decoder RF input at configured rate, post-Costas baseband
at 50 kS/s and actual recovered constellation symbols at nominal 5000 baud.
Primitive staging arrays publish to the core single-producer ring; observer
snapshots may be empty or overwritten and never pace DSP. A publication failure
disables only that tap. The AFC FFT is functional DSP on the decoder's processing
thread. Observer FFTs, Swing painting, transport and file I/O execute outside
production DSP. Decoder construction is inert; the owner serializes
lifecycle calls and accept. Sink wiring precedes one-shot start. Stop/close are
idempotent; failures remain inspectable and no decoder threads/resources need
joining. Live Pluto acquisition remains a separate hardware qualification.
