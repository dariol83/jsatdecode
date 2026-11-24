/** FMComms2 and PlutoPlus sample-source provider. */
module eu.dariolucia.jsatdecode.source.fmcomms2 {
    requires transitive eu.dariolucia.jsatdecode.api;
    requires java.xml;

    exports eu.dariolucia.jsatdecode.source.fmcomms2;

    provides eu.dariolucia.jsatdecode.api.source.SampleSourceFactory
            with eu.dariolucia.jsatdecode.source.fmcomms2.Fmcomms2SourceFactory;
}
