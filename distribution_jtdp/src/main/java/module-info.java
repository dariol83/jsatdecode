/** TCP/JTDP publishers, clients, codecs and shared listener registry. */
module eu.dariolucia.jsatdecode.distribution.jtdp {
    requires transitive eu.dariolucia.jsatdecode.api;

    exports eu.dariolucia.jsatdecode.distribution.tcp;
    exports eu.dariolucia.jsatdecode.distribution.tcp.client;
    exports eu.dariolucia.jsatdecode.distribution.tcp.codec;
    exports eu.dariolucia.jsatdecode.distribution.tcp.server;

    provides eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory
            with eu.dariolucia.jsatdecode.distribution.tcp.TcpFrameDistributorFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory
            with eu.dariolucia.jsatdecode.distribution.tcp.TcpMonitoringDistributorFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.DistributionConfigurationNormalizer
            with eu.dariolucia.jsatdecode.distribution.tcp.TcpConfigurationNormalizer;
}
