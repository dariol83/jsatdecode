/** SatDecode ZMQ publishers, clients and frame recording. */
module eu.dariolucia.jsatdecode.distribution.zmq {
    requires transitive eu.dariolucia.jsatdecode.api;
    requires org.zeromq.jeromq;

    exports eu.dariolucia.jsatdecode.distribution.zmq;
    exports eu.dariolucia.jsatdecode.distribution.zmq.client;

    provides eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory
            with eu.dariolucia.jsatdecode.distribution.zmq.ZmqFrameDistributorFactory,
                 eu.dariolucia.jsatdecode.distribution.zmq.FrameFileDistributorFactory;
    provides eu.dariolucia.jsatdecode.api.distribution.MonitoringDistributorFactory
            with eu.dariolucia.jsatdecode.distribution.zmq.ZmqMonitoringDistributorFactory;
}
