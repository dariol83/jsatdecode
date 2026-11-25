/**
 * Satellite signal acquisition, decoder contracts, runtime, monitoring and desktop tools.
 * Factory providers are discovered through the Java service loader.
 */
module eu.dariolucia.jsatdecode.core {
    requires transitive eu.dariolucia.jsatdecode.api;
    requires transitive java.desktop;

    exports eu.dariolucia.jsatdecode.core.config;
    exports eu.dariolucia.jsatdecode.core.registry;
    exports eu.dariolucia.jsatdecode.core.runtime;
    exports eu.dariolucia.jsatdecode.ui.swing;
    exports eu.dariolucia.jsatdecode.ui;

    uses eu.dariolucia.jsatdecode.api.application.ApplicationLauncher;
    uses eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
    uses eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
    uses eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;
    uses eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;
    uses eu.dariolucia.jsatdecode.api.distribution.DistributionConfigurationNormalizer;

    provides eu.dariolucia.jsatdecode.api.decoder.DecoderFactory
            with eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoderFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory
            with eu.dariolucia.jsatdecode.core.distribution.SyntheticFrameDistributorFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory
            with eu.dariolucia.jsatdecode.core.distribution.SyntheticMonitoringDistributorFactory;
}
