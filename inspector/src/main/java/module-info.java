/** Standalone JSatInspector application, with source providers discovered through API services. */
module eu.dariolucia.jsatdecode.inspector {
    requires eu.dariolucia.jsatdecode.api;
    requires eu.dariolucia.jsatdecode.core;
    requires java.desktop;

    exports eu.dariolucia.jsatdecode.inspector;

    uses eu.dariolucia.jsatdecode.api.source.SampleSourceFactory;
    provides eu.dariolucia.jsatdecode.api.application.ApplicationLauncher
            with eu.dariolucia.jsatdecode.inspector.InspectorLauncher;
}
