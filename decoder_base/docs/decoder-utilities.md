# Decoder utilities

`eu.dariolucia.jsatdecode.decoder.base.dsp.SimpleDcBlocker` provides single-pole
I/Q mean estimation/subtraction in the [`decoder_base`](../README.md) Maven module.
The class has no external DSP dependency and is not wired into any mission decoder.

```java
SimpleDcBlocker blocker = new SimpleDcBlocker(); // enabled
blocker.sample(inputI, inputQ);
float filteredI = blocker.i();
float filteredQ = blocker.q();
```

For each channel, using float arithmetic in this order:

```text
mean = .0001f * input + .9999f * previousMean
output = input - mean
```

The fixed coefficient is 0.0001. Means and initial outputs start at zero.
Each push immediately replaces the latest output; state persists across input
blocks. No samples are buffered for later emission, and there is no EOF flush.
Create one instance per processing worker/stream. Instances are not thread-safe;
processing is synchronous, nonblocking and allocation-free.

`new SimpleDcBlocker(false)` bypasses the estimator and copies I/Q bits exactly.
Enablement is fixed for the instance lifetime. A new instance starts with zero
history. Callers validate input samples; nonfinite enabled inputs propagate into
history. The approximate high-pass corner is
`0.0001 * sampleRate / (2 * pi)` Hz.

## Long-form DC blocker

`eu.dariolucia.jsatdecode.decoder.base.dsp.ComplexDcBlocker` adapts two independent
jradio 1.121 long-form filters to pushed I/Q samples. It provides exact bypass
and one output per input, with no EOF tail. Enablement and averaging length are
explicit constructor inputs; decoder settings supply their defaults.

Enabled delay is `2*(length-1)` input samples. The worker reads the latest
outputs from primitive `i` and `q` fields. State persists across input blocks;
processing is synchronous, nonblocking and allocation-free. The single-pole
estimator has a different response and startup from this long-form blocker.

## Shared infrastructure

The library also provides lifecycle bookkeeping, string constants, configuration
validation, the mandatory metric schema and polling-only snapshot assembly.
Mission DSP, status detection/cadence, framing, sample-clock arithmetic and tap
policy belong to each receiver. `DecoderStatusKeys`, public interfaces, ring
buffers and metric storage are in core. Core does not depend on `decoder_base`.
