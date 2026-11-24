/** WAV, CF32 and CS16 sample-source providers. */
module eu.dariolucia.jsatdecode.source.file {
    requires transitive eu.dariolucia.jsatdecode.api;

    exports eu.dariolucia.jsatdecode.source.cf32;
    exports eu.dariolucia.jsatdecode.source.cs16;
    exports eu.dariolucia.jsatdecode.source.file;
    exports eu.dariolucia.jsatdecode.source.wav;

    provides eu.dariolucia.jsatdecode.api.source.SampleSourceFactory
            with eu.dariolucia.jsatdecode.source.cf32.Cf32SourceFactory,
                 eu.dariolucia.jsatdecode.source.cs16.Cs16SourceFactory,
                 eu.dariolucia.jsatdecode.source.wav.WavSourceFactory;
}
