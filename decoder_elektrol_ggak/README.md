# Elektro-L GGAK decoder

Independent Java 21 JSatDecode module, factory ID `elektrol_ggak`. The normative
DSP reference is [decoder_elektrol_ggak.grc](grc/decoder_elektrol_ggak.grc). Output consists of
220-byte, polarity-normalized GGAK frames with the ASM removed. Only frames
passing the reference's 16-bit additive checksum are distributed; both transmitted
checksum bytes are retained. Checksum failures are counted in `frame.bad`.
The reference specifies no payload error correction, FEC or descrambling.

The explicit JPMS module `eu.dariolucia.jsatdecode.decoder.elektrol.ggak` exports
`eu.dariolucia.jsatdecode.elektrol` for direct construction and provides its
`ElektroLGgakDecoderFactory` through the framework's `DecoderFactory` service.
The same JAR retains classpath service registration. See
[Java modules](../core/docs/java-modules.md) for modular launches and plugin
requirements.

From the repository root:

```powershell
mvn -pl decoder_elektrol_ggak -am verify
java --module-path 'decoder_elektrol_ggak/target/classes;decoder_elektrol_ggak/target/lib' --module eu.dariolucia.jsatdecode.core/eu.dariolucia.jsatdecode.MainLauncher --config decoder_elektrol_ggak/examples/elektrol-ggak-wav.properties
```

Use `:` instead of `;` as the path separator on Linux/macOS. The build copies
runtime dependencies to `target/lib`; the framework discovers the decoder
through its JPMS service declaration. Classpath launches remain supported.

Edit the WAV filename before running. The Pluto example uses a 1693.250 MHz LO,
4 MS/s PHY rate, FPGA decimation 4, and a +250 kHz source rotation. The decoder
receives 1 MS/s. Ensure the delivered source rate equals `decoder.sample_rate_hz`.

See [receiver details](docs/receiver.md), [verification](docs/qualification.md),
and the two [example configurations](examples).
