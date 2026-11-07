# Elektro-L GGAK signal profile

This document describes the profile implemented by the decoder and its
[normative GRC reference](../grc/decoder_elektrol_ggak.grc). It is an engineering
receiver profile, not an operator-issued transmitter interface specification.
See [receiver details](receiver.md) for configuration and monitoring.

| Parameter | Implemented profile |
| --- | --- |
| Service | Elektro-L GGAK telemetry |
| Nominal RF reference | 1693.000 MHz; source tuning/rotation is configured outside the decoder |
| Decoder input | Complex float I/Q; default 1 MS/s; integer multiples of 250 kS/s from 250 kS/s through 16 MS/s |
| Waveform | Residual-carrier phase modulation with a BPSK telemetry component |
| Rate progression | Input -> 250 kS/s -> 50 kS/s -> nominal 5000 symbols/s |
| Symbol and recovered bit rate | 5000 symbols/s and 5000 bit/s; one hard bit per symbol |
| Residual-carrier recovery | PLL at 50 kS/s, bandwidth 0.02 rad/sample |
| Telemetry extraction | Retain j*Im(z) and translate by -5000 Hz |
| Matched filter | Complex root-raised-cosine, roll-off 0.5, 31 taps at 50 kS/s |
| BPSK phase recovery | Order-2 Costas loop, bandwidth 0.005 rad/sample |
| Symbol timing | Complex Mueller & Mueller; nominal omega 10, relative omega limit 0.005 |
| Decision | Real(symbol) >= 0 becomes one; otherwise zero |
| Polarity | Search normal and complemented ASM; normalize the entire payload |
| Differential decoding, Viterbi, Reed-Solomon, derandomization | None |
| Attached synchronization marker | 0x1ACFFC1D, 32 bits; complemented marker 0xE53003E2 |
| Serialization | MSB first |
| Synchronization unit | 1792 bits / 224 bytes including the four-byte ASM |
| Published frame | 1760 bits / 220 bytes after ASM removal; original checksum bytes retained |
| Marker thresholds | Default two errors in search, four after first complete candidate; configurable |
| Acquisition policy | One marker starts fixed-length collection; resume sliding search after each complete payload |
| Hold policy | First complete candidate, including a checksum reject, permanently selects the hold threshold |
| Repeated payloads | Retained; no duplicate suppression |
| Frame integrity | Mandatory 16-bit additive checksum; no payload error correction |
| Checksum coverage | Unsigned GGAK bytes 0..217; ASM and checksum bytes excluded |
| Checksum calculation | `(0x0202 + sum(unsigned frame[0..217])) & 0xffff` |
| Checksum field | Bytes 218..219, big-endian |
| Delivery policy | Deliver only complete checksum-valid candidates; reject mismatches including fill-like data |
| Output annotations | `frame_type=GGAK`, `quality=UNKNOWN`, `checksum_valid=true`, unsigned integer `checksum` |
| Nominal contiguous unit cadence | 1792/5000 = 0.3584 seconds/unit, approximately 2.79018 units/s |

The additive checksum cannot detect byte reordering or compensating byte errors.
Its protocol scope and the meaning of constant 0x0202 are not operator-confirmed;
the decoder applies the same rule to all candidates. Checksum validity does not
guarantee error-free content.

The receiver forwards the complete 220-byte GGAK frame and does not parse
internal headers, counters, CCSDS transfer frames or space packets. A CCSDS-style
ASM alone does not establish those higher-layer formats. Transmitter
polarization, EIRP, frequency tolerance, modulation index, occupied bandwidth,
clock tolerance and exact transmit pulse shaping are outside this implemented
profile. The RRC and loop values above are receiver settings.
