/**
 * Elektro-L GGAK telemetry decoder and its discoverable decoder factory.
 */
module eu.dariolucia.jsatdecode.decoder.elektrol.ggak {
    requires transitive eu.dariolucia.jsatdecode.core;
    requires transitive eu.dariolucia.jsatdecode.decoder.base;
    requires jradio;

    exports eu.dariolucia.jsatdecode.elektrol;

    provides eu.dariolucia.jsatdecode.api.decoder.DecoderFactory
            with eu.dariolucia.jsatdecode.elektrol.ElektroLGgakDecoderFactory;
}
