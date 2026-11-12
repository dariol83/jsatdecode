"""Generate deterministic GGAK signals and run the normative GRC DSP headlessly.

This optional qualification tool requires GNU Radio 3.10.12, NumPy, SciPy and
PyYAML; it is independent of the Java build and decoder implementation. The
embedded AFC analysis, PM conversion, power normalization and deframer are
loaded directly from the GRC file. The AFC estimate changes at fixed FFT
boundaries and normalization uses complete 250-sample segments, independent
of GNU Radio's work-buffer partition. Neither buffered stage drains at EOF.
All generated binary artifacts belong under target/reference and are disposable.

Windows radioconda example (PowerShell, from the repository root)::

    $env:PATH = 'C:/Users/dario/radioconda/Library/bin;' + $env:PATH
    & C:/Users/dario/radioconda/python.exe `
        decoder_elektrol_ggak/src/test/python/qualify_reference.py
"""

import argparse
import contextlib
import hashlib
import io
import json
from pathlib import Path
import time

import numpy as np
from scipy.signal import resample_poly, upfirdn
import yaml
from gnuradio import analog, blocks, digital, filter, gr
from gnuradio.filter import firdes, window


ROOT = Path(__file__).resolve().parents[3]
GRC = ROOT / "grc" / "decoder_elektrol_ggak.grc"
ASM = bytes.fromhex("1acffc1d")
SYMBOL_RATE = 5000
TIMING_RATE = 50000
CHANNEL_RATE = 250000


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for piece in iter(lambda: stream.read(1 << 20), b""):
            digest.update(piece)
    return digest.hexdigest()


def load_grc():
    document = yaml.safe_load(GRC.read_text(encoding="utf-8"))
    return {entry["name"]: entry for entry in document["blocks"]}


def embedded_block(blocks_by_name, name):
    source = blocks_by_name[name]["parameters"]["_source_code"]
    # Windows PMT's from_long is 32-bit. Preserve the DSP and frame decisions;
    # only allow the existing host timestamp metadata to represent nanoseconds.
    source = source.replace("pmt.from_long(time.time_ns())", "pmt.from_uint64(time.time_ns())")
    namespace = {"__name__": "ggak_grc_" + name}
    exec(compile(source, str(GRC) + ":" + name, "exec"), namespace)
    return namespace["blk"]


def canonical_afc(grc_blocks):
    """Run unchanged GRC AFC analysis on an explicit channel sample clock.

    The embedded work() analyses a complete scheduler buffer before mixing
    it. Its estimate-change position therefore depends on work-buffer sizes.
    This qualification fixes the decision to the Nth input sample: analyse
    that sample before mixing its delayed output. The FFT, averaging, scoring,
    acceptance gate, delay and phase-continuous mixing equations are unchanged.
    """
    reference = embedded_block(grc_blocks, "epy_block_afc")

    class SampleClockAfc(reference):
        def _mix(self, samples, destination):
            if self._f is None:
                destination[:] = 0
                return
            inc = -2.0 * np.pi * self._f / self.fs
            phases = self._phase + inc * np.arange(len(samples), dtype=np.float64)
            destination[:] = (samples * np.exp(1j * phases)).astype(np.complex64)
            self._phase = float((self._phase + inc * len(samples)) % (2.0 * np.pi))

        def work(self, input_items, output_items):
            samples, destination = input_items[0], output_items[0]
            count = len(samples)
            self._delay = np.concatenate((self._delay, samples))
            delayed = self._delay[:count]
            self._delay = self._delay[count:]
            first = 0
            while first < count:
                take = min(self.nfft - len(self._pend), count - first)
                self._pend = np.concatenate((self._pend, samples[first:first + take]))
                if len(self._pend) == self.nfft:
                    # Only the boundary sample uses the estimate just acquired.
                    self._mix(delayed[first:first + take - 1], destination[first:first + take - 1])
                    self._analyse(self._pend)
                    self._pend = np.zeros(0, np.complex64)
                    self._mix(delayed[first + take - 1:first + take],
                              destination[first + take - 1:first + take])
                else:
                    self._mix(delayed[first:first + take], destination[first:first + take])
                first += take
            return count

    return SampleClockAfc(CHANNEL_RATE, 65536, 2, 8, 30000., SYMBOL_RATE, 12., 40.)


def canonical_normalizer(grc_blocks):
    """Apply the embedded power equations to complete 250-sample segments.

    Scheduler work calls may end between segments in the original GRC. The
    canonical port buffers a complete segment before emitting it; an incomplete
    terminal segment is retained and never padded or flushed.
    """
    reference = embedded_block(grc_blocks, "epy_block_norm")

    class SegmentedNormalizer(gr.basic_block):
        def __init__(self):
            gr.basic_block.__init__(self, name="GGAK fixed-segment reference power normalizer",
                                   in_sig=[np.complex64], out_sig=[np.complex64])
            self.reference = reference(TIMING_RATE, .25, 250)
            self.set_output_multiple(250)

        def forecast(self, noutput_items, ninputs):
            return [250] * ninputs

        def general_work(self, input_items, output_items):
            samples, destination = input_items[0], output_items[0]
            count = min(len(samples), len(destination)) // 250 * 250
            for first in range(0, count, 250):
                self.reference.work([samples[first:first + 250]],
                                    [destination[first:first + 250]])
            self.consume(0, count)
            return count

    return SegmentedNormalizer()


def create_signal(destination, rate, frame_count, preamble_bits, seed, noise, carrier_offset,
                  corrupt_frame_indices):
    """Independent shaped BPSK subcarrier, phase modulating a residual carrier.

    A 5 kHz real BPSK subcarrier phase modulates a unit amplitude carrier with
    modulation index 0.8 radians. The transmit pulse is an RRC alpha=0.5, 101
    taps at 10 samples/symbol. Neither noise nor timing is inferred from any
    recording. Fixed PRNG seed and explicit frame indices make truth auditable.
    """
    rng = np.random.default_rng(seed)
    payloads = []
    for index in range(frame_count):
        payload = bytearray(rng.integers(0, 256, 220, dtype=np.uint8).tobytes())
        payload[:8] = b"GGAK" + index.to_bytes(4, "big")
        # Generate the documented checksum independently of the embedded receiver block.
        checksum = (sum(payload[:218]) + 0x0202) & 0xffff
        payload[218:] = checksum.to_bytes(2, "big")
        if index in corrupt_frame_indices:
            payload[219] ^= 1
        payloads.append(bytes(payload))
    transmitted = b"".join(ASM + payload for payload in payloads)
    preamble = rng.integers(0, 2, preamble_bits, dtype=np.uint8)
    # Real transmitted tail carries the last frame through the .524288 s AFC
    # delay and the finite FIR/normalizer/timing lookahead; no receiver EOF drain.
    tail_bits = 4096
    bits = np.concatenate((preamble, np.unpackbits(np.frombuffer(transmitted, dtype=np.uint8)),
                           rng.integers(0, 2, tail_bits, dtype=np.uint8)))
    symbols = 2.0 * bits.astype(np.float64) - 1.0
    tx_taps = np.asarray(firdes.root_raised_cosine(10., TIMING_RATE, SYMBOL_RATE, .5, 101))
    shaped = upfirdn(tx_taps, symbols, up=10)
    interpolated = resample_poly(shaped, rate // TIMING_RATE, 1)
    index = np.arange(len(interpolated), dtype=np.float64)
    subcarrier = np.cos(2.0 * np.pi * SYMBOL_RATE / rate * index)
    phase = .8 * interpolated * subcarrier + 2.0 * np.pi * carrier_offset / rate * index
    signal = np.exp(1j * phase).astype(np.complex64)
    if noise:
        # Separate PRNG preserves the transmitted payloads across noise settings.
        noise_rng = np.random.default_rng(seed + 1)
        signal += (noise / np.sqrt(2.) * (noise_rng.standard_normal(len(signal))
                   + 1j * noise_rng.standard_normal(len(signal)))).astype(np.complex64)
    signal.astype("<c8").tofile(destination / "input.cf32")
    accepted = b"".join(payload for index, payload in enumerate(payloads)
                        if index not in corrupt_frame_indices)
    (destination / "expected_frames.bin").write_bytes(accepted)
    (destination / "transmitted_frames.bin").write_bytes(b"".join(payloads))
    bits.tofile(destination / "transmitted_bits.u8")
    return {"samples": len(signal), "frame_count": frame_count,
            "candidate_frames": frame_count,
            "expected_accepted_frames": frame_count - len(corrupt_frame_indices),
            "expected_rejected_frames": len(corrupt_frame_indices),
            "corrupt_frame_indices": sorted(corrupt_frame_indices),
            "preamble_bits": preamble_bits, "tail_bits": tail_bits, "seed": seed, "noise_rms": noise,
            "modulation_index_radians": .8, "carrier_offset_hz": carrier_offset,
            "transmit_rrc_taps": len(tx_taps),
            "payload_sha256": hashlib.sha256(accepted).hexdigest(),
            "transmitted_payload_sha256": hashlib.sha256(b"".join(payloads)).hexdigest()}


def run_reference(destination, rate, grc_blocks):
    if rate % CHANNEL_RATE:
        raise ValueError("Qualification rates must be integer multiples of 250000 Hz")
    channel_taps = firdes.low_pass(1., rate, 60000., 20000., window.WIN_HAMMING)
    timing_taps = firdes.low_pass(1., CHANNEL_RATE, 20000., 4000., window.WIN_HAMMING)
    rrc_taps = firdes.root_raised_cosine(1., TIMING_RATE, SYMBOL_RATE, .5, 31)
    for name, taps in (("channel", channel_taps), ("timing", timing_taps), ("rrc", rrc_taps)):
        np.asarray(taps, dtype="<f4").tofile(destination / ("taps_" + name + ".f32"))
    tb = gr.top_block("Elektro-L GGAK v1.5 fixed-segment GRC DSP qualification")
    source = blocks.file_source(gr.sizeof_gr_complex, str(destination / "input.cf32"), False)
    stages = [
        ("channel", filter.freq_xlating_fir_filter_ccc(rate // CHANNEL_RATE, channel_taps, 0., rate)),
        ("afc", canonical_afc(grc_blocks)),
        ("timing", filter.fir_filter_ccc(5, timing_taps)),
        ("pll", analog.pll_carriertracking_cc(.002, .025, -.025)),
        ("pm", embedded_block(grc_blocks, "epy_block_pm")(TIMING_RATE, SYMBOL_RATE)),
        ("rrc", filter.fir_filter_ccc(1, rrc_taps)),
        ("norm", canonical_normalizer(grc_blocks)),
        ("costas", digital.costas_loop_cc(.005, 2, False)),
        ("symbols", digital.clock_recovery_mm_cc(10., .000025, .5, .02, .005)),
    ]
    deframer = embedded_block(grc_blocks, "epy_block_0")(1792, 2, 4, str(destination / "frames.bin"))
    previous = source
    sinks = []
    for name, stage in stages:
        tb.connect(previous, stage)
        sink = blocks.file_sink(gr.sizeof_gr_complex, str(destination / (name + ".cf32")), False)
        tb.connect(stage, sink)
        sinks.append(sink)
        previous = stage
    tb.connect(previous, deframer)
    started = time.perf_counter()
    tb.run(4096)
    elapsed = time.perf_counter() - started
    # File sink buffers are owned by Python wrappers until close()/destruction.
    for sink in sinks:
        sink.close()
    deframer.stop()
    actual = (destination / "frames.bin").read_bytes()
    expected = (destination / "expected_frames.bin").read_bytes()
    transmitted = (destination / "transmitted_frames.bin").read_bytes()
    truth = [transmitted[i:i + 220] for i in range(0, len(transmitted), 220)]
    frames = [actual[i:i + 220] for i in range(0, len(actual), 220)]
    matched = [truth.index(frame) if frame in truth else None for frame in frames]
    if actual != expected or len(actual) % 220 or any(index is None for index in matched):
        raise AssertionError("GRC frames do not exactly match transmitted truth: " + repr(matched))
    expected_rejected = sum(((sum(frame[:218]) + 0x0202) & 0xffff)
                            != int.from_bytes(frame[218:220], "big") for frame in truth)
    if deframer.frames != len(frames) or deframer.rejected_frames != expected_rejected:
        raise AssertionError("GRC checksum candidate accounting differs from transmitted truth")
    return {"wall_seconds": elapsed, "recovered_frames": len(frames),
            "scheduler_policy": "AFC analysis before mixing each Nth channel sample; complete 250-sample power segments; no EOF drain",
            "rejected_frames": deframer.rejected_frames,
            "classified_candidates": deframer.frames + deframer.rejected_frames,
            "recovered_frame_indices": matched,
            "stage_samples": {name: (destination / (name + ".cf32")).stat().st_size // 8
                              for name, _ in stages},
            "tap_counts": {"channel": len(channel_taps), "timing": len(timing_taps), "rrc": len(rrc_taps)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "target" / "reference")
    parser.add_argument("--rates", nargs="+", type=int, default=[1000000, 2000000])
    parser.add_argument("--frames", type=int, default=16)
    parser.add_argument("--preamble-bits", type=int, default=4096)
    parser.add_argument("--seed", type=int, default=1693250)
    parser.add_argument("--noise-rms", type=float, default=.005)
    parser.add_argument("--carrier-offset-hz", type=float, default=0.)
    parser.add_argument("--corrupt-frame-indices", default="",
                        help="Comma-separated zero-based frame indices whose checksum is corrupted")
    args = parser.parse_args()
    try:
        corrupt = {int(index) for index in args.corrupt_frame_indices.split(",") if index.strip()}
    except ValueError as error:
        parser.error("--corrupt-frame-indices requires comma-separated integer indices: " + str(error))
    if args.frames < 1 or any(index < 0 or index >= args.frames for index in corrupt):
        parser.error("Frame count must be positive and corrupt indices must be in [0, frames)")
    grc_blocks = load_grc()
    args.output.mkdir(parents=True, exist_ok=True)
    reports = []
    for rate in args.rates:
        destination = args.output / ("rate_" + str(rate))
        destination.mkdir(parents=True, exist_ok=True)
        signal = create_signal(destination, rate, args.frames, args.preamble_bits, args.seed,
                               args.noise_rms, args.carrier_offset_hz, corrupt)
        log = io.StringIO()
        with contextlib.redirect_stdout(log):
            reference = run_reference(destination, rate, grc_blocks)
        (destination / "grc_stdout.txt").write_text(log.getvalue(), encoding="utf-8")
        report = {"sample_rate_hz": rate, "gnuradio_version": gr.version(),
                  "normative_grc_sha256": sha256(GRC), **signal, **reference}
        report["signal_seconds"] = signal["samples"] / rate
        report["realtime_factor"] = report["signal_seconds"] / reference["wall_seconds"]
        report["sha256"] = {path.name: sha256(path) for path in sorted(destination.glob("*.bin"))}
        report["sha256"]["input.cf32"] = sha256(destination / "input.cf32")
        (destination / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        reports.append(report)
        print(json.dumps(report))
    (args.output / "manifest.json").write_text(json.dumps(reports, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
