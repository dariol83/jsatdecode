/**
 * Hinode S-band telemetry decoder and its discoverable decoder factory.
 */
module eu.dariolucia.jsatdecode.decoder.hinode {
    requires transitive eu.dariolucia.jsatdecode.core;
    requires transitive eu.dariolucia.jsatdecode.decoder.base;
    requires jradio;

    exports eu.dariolucia.jsatdecode.hinode;

    provides eu.dariolucia.jsatdecode.api.decoder.DecoderFactory
            with eu.dariolucia.jsatdecode.hinode.HinodeDecoderFactory;
}
