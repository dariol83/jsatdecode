# Hinode S-band telemetry profile

This table describes the signal and framing parameters implemented by the
receiver. See [receiver details](receiver.md) for DSP settings, configuration and
monitoring, and [verification](qualification.md) for test procedures.

| Parameter | Implemented profile |
| --- | --- |
| Spacecraft | Hinode (Solar-B) |
| Link | Space-to-ground S-band telemetry |
| Nominal carrier frequency | 2256.22 MHz |
| Decoder input | 500 ksps complex float I/Q |
| Waveform | Residual-carrier phase modulation; BPSK information from the quadrature component |
| Residual-carrier recovery | Carrier-tracking PLL before telemetry extraction |
| Data extraction | Retain imaginary/Q component after residual-carrier removal |
| Frequency translation | -65.6 kHz applied to the extracted complex Q component |
| Symbol modulation and rate | BPSK, 65.6 ksym/s |
| Input samples per symbol | 500000 / 65600 = 7.6219512195 |
| Matched filter | Root-raised-cosine, roll-off 0.6, 31 taps |
| BPSK phase recovery | Order-2 Costas loop |
| Timing recovery | Complex Mueller & Mueller, nominal omega 7.6219512195, relative limit 0.005 |
| Convolutional coding | CCSDS-compatible K=7, rate 1/2 |
| Generator notation | GNU Radio [79,-109]; bit-reversed octal 171 / inverted 133 for the receiver's newest-bit-low shift register |
| Differential decoding | None |
| Convolutional decoding | Continuous soft-decision Viterbi, survivor depth 56 |
| Ambiguities | Four hypotheses: coded-pair phase 0/1 and normal/inverted BPSK polarity |
| Attached synchronization marker | 0x1ACFFC1D, 32 bits, MSB first |
| Encoded CADU length | 1024 bytes / 8192 bits including the 4-byte ASM |
| Randomization | CCSDS x^8+x^7+x^5+x^3+1; reset to all ones after ASM |
| Derandomization order | After ASM removal and before Reed-Solomon decoding |
| Reed-Solomon | RS(255,223), dual basis, interleave 4, 32 parity bytes per codeword |
| RS-protected payload after ASM | 4 * 255 = 1020 bytes |
| Published corrected frame | 4 * 223 = 892 bytes; ASM and parity removed |
| Frame family | CCSDS AOS Transfer Frame |
| Virtual channel metadata | Low six bits of byte 1; no fixed channel sequence enforced |
| Virtual channel frame counter | 24-bit value in bytes 2..4 |
