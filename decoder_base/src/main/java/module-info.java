/**
 * Shared lifecycle, configuration, signal processing and monitoring support for
 * JSatDecode decoders.
 */
module eu.dariolucia.jsatdecode.decoder.base {
    requires transitive eu.dariolucia.jsatdecode.core;
    requires jradio;

    exports eu.dariolucia.jsatdecode.decoder.base;
    exports eu.dariolucia.jsatdecode.decoder.base.config;
    exports eu.dariolucia.jsatdecode.decoder.base.dsp;
    exports eu.dariolucia.jsatdecode.decoder.base.monitoring;
}
