# Decoded-frame recording

Add `--store <folder>` to a normal framework invocation:

```powershell
bin/jsatdecoder.bat --config examples/decoder_hinode/hinode-wav.properties --set source.file=C:/recordings/Hinode_90secs.wav --ui --store 'C:/recordings/decoded frames'
```

Run this command from an extracted [distribution](../../distribution/README.md),
which includes mission decoders and dependencies. For development classpath
commands, see the [implementation guide](implementation-guide.md). IntelliJ Run
arguments accept `--store "C:/decoded frames"`.

The destination directory and missing parent directories are created on startup.
One file per processing run is named
`<decoder name>_yyyy_MM_dd_HH_mm_ss.frame.bin`, for example
`hinode_s_tlm_2026_10_16_18_30_05.frame.bin`. The name uses the registered decoder
ID (`decoder.type`) and UTC date/time when the recording starts, immediately
before acquisition. MM is month and HH is the 24-hour clock. An empty run still
creates an empty file. Existing files are never truncated or appended: a
same-name collision fails startup. Invalid paths, permissions or disk errors
fail explicitly rather than silently disabling recording.

`--store` adds recording alongside the configured frame output, including ZMQ.
It does not replace the distributor or require a ZMQ socket. It maps to the
global `storage.folder` property; dedicated CLI switches override properties and
`--set`. Repeated `--store` uses the last value. Without that property/switch no
recording is created. It applies to normal decoding, not `--ui-demo`.

## Binary format

ZMQ frames consist of three separate multipart bodies, rather than a single
self-delimiting byte stream. Disk records preserve those exact body formats and
add only lengths to retain boundaries. The file is a repetition of:

| Field | Encoding |
| --- | --- |
| Topic length | 4-byte unsigned big-endian integer |
| Topic | Exactly that many bytes: ASCII `satdecode.v1.frame` |
| Metadata length | 4-byte unsigned big-endian integer |
| Metadata | Canonical SatDecode v1 CBOR envelope, same encoder as ZMQ |
| Payload length | 4-byte unsigned big-endian integer |
| Payload | Exact decoded frame bytes, with no payload transformation |

There is no file header, separator, padding or end marker. Read three
length-prefixed parts per frame until clean EOF at the next topic length.
EOF inside a length or part indicates a truncated record. Each message is bounded
by `distribution.frames.max_message_bytes` (default 16 MiB). Readers should check
length limits before allocating. No ZMQ transport headers are stored.

The metadata includes schema version, message type, receiver/decoder identity,
sequence, reception time/source, publish time, frame type/length, quality and
all extensible annotations. Binary/null metadata retain their CBOR types.
See [ZMQ compatibility](zmq-compatibility.md) for the envelope and UTC mapping.
Recording sequences start at zero. Publish time is the disk worker's encoding
time; separate live ZMQ and disk outputs therefore have the same schema and
annotations but may have different publish timestamps. Source API UTC timestamps
are preserved without recalibration; see [time.md](time.md).

Example Python reader (requires cbor2):

```python
import struct
import cbor2

def read_part(stream, clean_eof=False):
    header = stream.read(4)
    if clean_eof and not header:
        return None
    if len(header) != 4:
        raise ValueError("Truncated length")
    length, = struct.unpack(">I", header)
    if length > 16 * 1024 * 1024:  # use the recording's configured bound
        raise ValueError("Oversized part")
    part = stream.read(length)
    if len(part) != length:
        raise ValueError("Truncated part")
    return part

with open("hinode_s_tlm_2026_10_16_18_30_05.frame.bin", "rb") as stream:
    while (topic := read_part(stream, clean_eof=True)) is not None:
        metadata = cbor2.loads(read_part(stream))
        payload = read_part(stream)
        assert topic == b"satdecode.v1.frame"
        assert metadata["frame_length"] == len(payload)
        # Process metadata and payload here.
```

## Threading, failure and shutdown

Disk recording implements the existing FrameDistributor contract, registered as
`frame-file`. Runtime discovers it through ServiceLoader; the public API is
unchanged. Wire encoding stays inside the existing SatDecode compatibility
implementation, ensuring it cannot drift from ZMQ serialization.

Frame submission retains immutable references in a bounded queue (the configured
frame queue capacity, default 4096). Only the recording worker serializes and
writes. It always uses FAIL_RUNTIME on overflow, even if the network output is
configured to drop. Slow/full storage cannot block sample processing; overload
fails the run visibly. Normal stop first quiesces acquisition/decoder, drains
recording, then flushes and closes the stream. Disk failures may leave a partial
last record; earlier complete records remain readable. Close flushes buffered
data but does not promise power-loss durability/fsync.

Monitoring appears under `distribution.frames.frame-file.*` with received/sent,
dropped/errors and queue counters. Sent means written to the buffered stream;
successful shutdown completes its flush. Aggregate output counters count
deliveries to each configured distributor, so adding recording increases them.
