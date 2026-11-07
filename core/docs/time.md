# UTC time contract

All dates, times and timestamps provided by JSatDecode are UTC. A numeric
`timestampNanos` is signed nanoseconds since **1970-01-01T00:00:00Z**, never a
raw `System.nanoTime()` value. This applies to sample blocks, frames, monitoring
snapshots/values, events, distribution snapshots, taps, UI models and plots.
The same contract applies to decoder plugins, including decoder_hinode.

Plugins must provide epoch nanoseconds using `UtcTime.nowNanos()` or a known
hardware/recording UTC instant. Raw `System.nanoTime()` readings are unsuitable
as timestamps: their epoch cannot be recovered without a calibration.
Transport adapters preserve UTC values directly without clock calibration.

`util.UtcTime` anchors to host UTC once, then advances using a monotonic clock.
Reads are thread-safe, allocation-free and nonblocking, preserving the DSP
producer guarantees. Host synchronization accuracy at initialization determines
timestamp accuracy; later NTP/manual clock changes do not rewrite that timeline.
Restart to recalibrate after a host clock correction. The wire encoder reads
current host UTC for publication time; reception and publication times need not
be equal. UTC representation does not imply that host or hardware clocks are
perfectly synchronized.

File-source timestamps use a processing-start UTC anchor plus elapsed sample
duration, continuing across repeats. They do not reconstruct a recording's
original capture epoch. In accelerated playback they can run ahead of host time.
Known original UTC can be supplied using explicit reception annotations.
Tap timestamps denote observation/publication, not calibrated RF arrival time.
Empty/unavailable models may retain zero as an unset timestamp; inspect their
sample count/state rather than treating zero as a current observation.

ZMQ and disk recording preserve UTC API timestamps directly. Explicit
`rx_time_ns` / `reception.timestamp` fields retain their documented priority.
Standard textual timestamp fields accept ISO-8601 instants with an explicit
offset, normalize to UTC, and encode UTC epoch nanoseconds. Offset-free local
dates/times are rejected for those fields. Encoded dates/times must use UTC;
new textual instant displays should use ISO-8601 with `Z`. Decoded-frame filenames
use `yyyy_MM_dd_HH_mm_ss`; source IQ and Inspector recordings use the patterns
documented in [IQ recording](iq-recording.md) and [JSatInspector](jsatinspector.md).
All filename timestamp formatters use UTC explicitly.
Opaque mission payloads and arbitrary metadata strings are not rewritten;
plugin authors must follow this contract for any date/time metadata they provide.

Use checked arithmetic for epoch/sample-offset conversion. The long-nanosecond
representation spans approximately 1677–2262; overflow must fail explicitly.
The SatDecode v1 wire format additionally requires nonnegative epoch times,
so pre-1970 API values cannot be serialized through that adapter.

Internal durations, elapsed processing time, scheduling, refresh cadences,
timeout budgets and rate calculations are not timestamps and have no timezone.
They continue using `System.nanoTime()` for stability. Such clock readings must
never be exposed as absolute timestamps or formatted as calendar times.
