/**
 * Satellite signal acquisition, decoder contracts, runtime, monitoring and desktop tools.
 * Factory providers are discovered through the Java service loader.
 */
module eu.dariolucia.jsatdecode.core {
    requires transitive java.desktop;
    requires java.xml;
    requires org.zeromq.jeromq;

    exports eu.dariolucia.jsatdecode.api.config;
    exports eu.dariolucia.jsatdecode.api.decoder;
    exports eu.dariolucia.jsatdecode.api.distribution;
    exports eu.dariolucia.jsatdecode.api.frame;
    exports eu.dariolucia.jsatdecode.api.lifecycle;
    exports eu.dariolucia.jsatdecode.api.monitoring;
    exports eu.dariolucia.jsatdecode.api.source;
    exports eu.dariolucia.jsatdecode.api.taps;
    exports eu.dariolucia.jsatdecode.core.buffers;
    exports eu.dariolucia.jsatdecode.core.config;
    exports eu.dariolucia.jsatdecode.core.monitoring;
    exports eu.dariolucia.jsatdecode.core.registry;
    exports eu.dariolucia.jsatdecode.core.runtime;
    exports eu.dariolucia.jsatdecode.distribution.zmq;
    exports eu.dariolucia.jsatdecode.distribution.zmq.client;
    exports eu.dariolucia.jsatdecode.dsp.fft;
    exports eu.dariolucia.jsatdecode.source.cf32;
    exports eu.dariolucia.jsatdecode.source.cs16;
    exports eu.dariolucia.jsatdecode.source.file;
    exports eu.dariolucia.jsatdecode.source.fmcomms2;
    exports eu.dariolucia.jsatdecode.source.wav;
    exports eu.dariolucia.jsatdecode.ui.swing;
    exports eu.dariolucia.jsatdecode.util;

    uses eu.dariolucia.jsatdecode.api.decoder.DecoderFactory;
    uses eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
    uses eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;
    uses eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory;

    provides eu.dariolucia.jsatdecode.api.decoder.DecoderFactory
            with eu.dariolucia.jsatdecode.decoder.dummy.DummyDecoderFactory;
    provides eu.dariolucia.jsatdecode.api.source.SampleSourceFactory
            with eu.dariolucia.jsatdecode.source.cf32.Cf32SourceFactory,
                 eu.dariolucia.jsatdecode.source.cs16.Cs16SourceFactory,
                 eu.dariolucia.jsatdecode.source.wav.WavSourceFactory,
                 eu.dariolucia.jsatdecode.source.fmcomms2.Fmcomms2SourceFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory
            with eu.dariolucia.jsatdecode.core.distribution.SyntheticFrameDistributorFactory,
                 eu.dariolucia.jsatdecode.distribution.zmq.ZmqFrameDistributorFactory,
                 eu.dariolucia.jsatdecode.distribution.zmq.FrameFileDistributorFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory
            with eu.dariolucia.jsatdecode.core.distribution.SyntheticMonitoringDistributorFactory,
                 eu.dariolucia.jsatdecode.distribution.zmq.ZmqMonitoringDistributorFactory;
}
