# Hinode decoder for JSatDecode

Independent Java 21 Maven decoder module, factory ID `hinode_s_tlm`. It integrates
through the Decoder/DecoderFactory API and generic monitoring, taps and frame
contracts. The module uses the public `DecoderStatusKeys` vocabulary and
implements the [mandatory decoder contract](../core/docs/decoder-implementation-specification.md).

The receiver accepts complex float I/Q at 500000 samples/s and recovers 892-byte
RS-corrected AOS transfer frames from residual-carrier PM/BPSK telemetry.
The initial jradio long-form DC blocker has averaging length 16384 and is enabled
by default. Set `decoder.dcblocker=false` to bypass it; see
[receiver configuration](docs/receiver.md#initial-dc-blocker-configuration).

Status includes `carrier.offset_hz`: the carrier PLL's offset from the input I/Q
centre in Hz, positive above centre. It is NaN whenever carrier lock is absent
or decoding has stopped. See [receiver properties](docs/receiver.md) for cadence,
accuracy limits and DSP ownership, [verification](docs/qualification.md) for test
coverage and recording procedures, and the [reference GRC](grc/decoder_hinode_solar.grc).

Build from the repository root using JDK 21 or later:

```powershell
mvn -pl decoder_hinode -am verify
java -cp 'decoder_hinode/target/classes;decoder_hinode/target/lib/*' eu.dariolucia.jsatdecode.cli.Main --list-decoders
```

The reactor builds core and `decoder-base` first and copies runtime dependencies
into `target/lib`. Use the framework CLI with this module and its dependencies
on the classpath. `examples/hinode-wav.properties` supplies a starting
configuration; set `source.file` to the recording path. File reading and optional
ZMQ distribution belong to the framework. The decoder never opens an output file.
The Java runtime reads properties configuration files.

jradio 1.121 is pinned as an implementation dependency. Its types appear only in
package-private implementation classes. Timing adaptation attribution and the
Apache 2.0 license are included under `src/main/resources/META-INF`.
