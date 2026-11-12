"""Inspect GNU Radio's finite-input M&M scheduler tail without changing DSP.

Run with one target/reference/rate_*/costas.cf32 path. This test-only probe
separates stream-end scheduler availability from the clock-recovery algorithm.
"""

import argparse
import hashlib
import json
from pathlib import Path
import csv

import numpy as np
from gnuradio import blocks, digital, gr


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("costas", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--finite-output", type=Path)
    args = parser.parse_args()
    samples = np.fromfile(args.costas, dtype="<c8")
    records = []
    for source_type in ("file", "vector"):
        for batch in (32, 64, 256, 4096, 65536):
            tb = gr.top_block("M&M finite-input scheduling probe")
            if source_type == "file":
                source = blocks.file_source(gr.sizeof_gr_complex, str(args.costas), False)
            else:
                source = blocks.vector_source_c(samples, False)
            mm = digital.clock_recovery_mm_cc(10., .000025, .5, .02, .005)
            mm.set_max_noutput_items(batch)
            sink = blocks.vector_sink_c()
            tb.connect(source, mm, sink)
            tb.run(batch)
            output = np.asarray(sink.data(), dtype="<c8")
            record = {"source": source_type, "max_noutput_items": batch,
                      "source_items_written": source.nitems_written(0),
                      "mm_items_read": mm.nitems_read(0), "symbols": len(output),
                      "output_sha256": hashlib.sha256(output.tobytes()).hexdigest()}
            candidate = args.costas.parent / "java-symbols.cf32"
            if candidate.exists():
                java = np.fromfile(candidate, dtype="<c8")
                common = min(len(java), len(output))
                record["java_symbols"] = len(java)
                record["common_prefix_max_error"] = float(np.max(np.abs(java[:common] - output[:common])))
                record["common_prefix_real_sign_disagreements"] = int(np.count_nonzero((java[:common].real >= 0) != (output[:common].real >= 0)))
            records.append(record)
            print(json.dumps(record))
    destination = args.output or args.costas.parent / "mm_scheduler_probe.json"
    zero_sweep = []
    for length in range(20, 71):
        tb = gr.top_block("M&M forecast boundary probe")
        source = blocks.vector_source_c([0j] * length, False)
        mm = digital.clock_recovery_mm_cc(10., .000025, .5, .02, .005)
        mm.set_max_noutput_items(4096)
        sink = blocks.vector_sink_c()
        tb.connect(source, mm, sink)
        tb.run(4096)
        zero_sweep.append({"input_samples": length, "symbols": len(sink.data()),
                           "mm_items_read": mm.nitems_read(0)})
    destination.write_text(json.dumps({"real_signal_runs": records, "zero_input_length_sweep": zero_sweep},
                                     indent=2) + "\n", encoding="utf-8")
    finite_output = args.finite_output or args.costas.parent / "mm_finite"
    finite_output.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(50000179)
    driven = (.5 * (rng.standard_normal(1051) + 1j * rng.standard_normal(1051))).astype("<c8")
    lengths = list(range(0, 101)) + list(range(1000, 1051))
    finite_records = []
    for waveform, values in (("zero", np.zeros(1051, dtype="<c8")), ("driven", driven)):
        values.tofile(finite_output / (waveform + ".cf32"))
        for length in lengths:
            variants = {}
            for batch in (1, 32, 4096):
                tb = gr.top_block("M&M independent finite-input regression")
                # head(0) supplies an actual empty stream without a zero-length vector source.
                source = blocks.vector_source_c(values[:max(length, 1)], False)
                head = blocks.head(gr.sizeof_gr_complex, length)
                mm = digital.clock_recovery_mm_cc(10., .000025, .5, .02, .005)
                mm.set_max_noutput_items(batch)
                sink = blocks.vector_sink_c()
                tb.connect(source, head, mm, sink)
                tb.run(batch)
                output = np.asarray(sink.data(), dtype="<c8")
                variants[batch] = {"output": output, "omega": mm.omega(), "mu": mm.mu(),
                                   "mm_items_read": mm.nitems_read(0)}
            output = variants[4096]["output"]
            single = variants[1]
            for variant in variants.values():
                common = min(len(output), len(variant["output"]))
                if not np.array_equal(output[:common], variant["output"][:common]):
                    raise AssertionError("GNU M&M value changed with scheduler on common input prefix")
            output.tofile(finite_output / ("expected-" + waveform + "-" + str(length) + ".cf32"))
            single["output"].tofile(finite_output / ("expected-one-" + waveform + "-" + str(length) + ".cf32"))
            finite_records.append({"waveform": waveform, "input_samples": length,
                                   "symbols": len(output), "omega": variants[4096]["omega"],
                                   "mu": variants[4096]["mu"], "mm_items_read": variants[4096]["mm_items_read"],
                                   "one_output_symbols": len(single["output"]),
                                   "one_output_omega": single["omega"], "one_output_mu": single["mu"],
                                   "one_output_mm_items_read": single["mm_items_read"],
                                   "min_symbols": min(len(value["output"]) for value in variants.values()),
                                   "max_symbols": max(len(value["output"]) for value in variants.values())})
    (finite_output / "manifest.json").write_text(json.dumps(finite_records, indent=2) + "\n", encoding="utf-8")
    with (finite_output / "manifest.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(finite_records[0].keys()))
        writer.writeheader()
        writer.writerows(finite_records)
    print(json.dumps({"finite_probe_cases": len(finite_records), "gnu_batches": [1, 32, 4096],
                      "scheduler_dependent_cases": sum(item["min_symbols"] != item["max_symbols"] for item in finite_records),
                      "finite_output": str(finite_output)}))


if __name__ == "__main__":
    main()
