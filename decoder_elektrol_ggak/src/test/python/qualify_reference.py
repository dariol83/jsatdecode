"""Generate deterministic GGAK signals and run the normative GRC DSP headlessly.

This optional qualification tool requires GNU Radio 3.10.12, NumPy, SciPy and
PyYAML; it is independent of the Java build and decoder implementation. The
embedded PM conversion and deframer are loaded directly from the GRC file.
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
    bits = np.concatenate((preamble, np.unpackbits(np.frombuffer(transmitted, dtype=np.uint8)),
                           rng.integers(0, 2, 128, dtype=np.uint8)))
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
            "preamble_bits": preamble_bits, "seed": seed, "noise_rms": noise,
            "modulation_index_radians": .8, "carrier_offset_hz": carrier_offset,
            "transmit_rrc_taps": len(tx_taps),
            "payload_sha256": hashlib.sha256(accepted).hexdigest(),
            "transmitted_payload_sha256": hashlib.sha256(b"".join(payloads)).hexdigest()}


def run_reference(destination, rate, grc_blocks):
    if rate % CHANNEL_RATE:
        raise ValueError("Qualification rates must be integer multiples of 250000 Hz")
    channel_taps = firdes.low_pass(1., rate, 40000., 15000., window.WIN_HAMMING)
    timing_taps = firdes.low_pass(1., CHANNEL_RATE, 20000., 4000., window.WIN_HAMMING)
    rrc_taps = firdes.root_raised_cosine(1., TIMING_RATE, SYMBOL_RATE, .5, 31)
    for name, taps in (("channel", channel_taps), ("timing", timing_taps), ("rrc", rrc_taps)):
        np.asarray(taps, dtype="<f4").tofile(destination / ("taps_" + name + ".f32"))
    tb = gr.top_block("Elektro-L GGAK unchanged GRC DSP qualification")
    source = blocks.file_source(gr.sizeof_gr_complex, str(destination / "input.cf32"), False)
    stages = [
        ("channel", filter.freq_xlating_fir_filter_ccc(rate // CHANNEL_RATE, channel_taps, 0., rate)),
        ("timing", filter.fir_filter_ccc(5, timing_taps)),
        ("agc", analog.agc_cc(.01, 1., 1., 65536.)),
        ("pll", analog.pll_carriertracking_cc(.02, .5, -.5)),
        ("pm", embedded_block(grc_blocks, "epy_block_pm")(TIMING_RATE, SYMBOL_RATE)),
        ("rrc", filter.fir_filter_ccc(1, rrc_taps)),
        ("costas", digital.costas_loop_cc(.005, 2, False)),
        ("symbols", digital.clock_recovery_mm_cc(10., .000025, .5, .01, .005)),
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
