package eu.dariolucia.jsatdecode.api.decoder;

import eu.dariolucia.jsatdecode.api.source.LeasedSampleBlock;

/** Optional decoder capability for synchronous borrowed IQ delivery. */
public interface LeasedDecoder extends Decoder {
    /**
     * Processes a borrowed read-only block synchronously. It must be retained
     * before any use after return; the caller owns the supplied block.
     *
     * @param samples non-null open borrowed block
     */
    void acceptLeased(LeasedSampleBlock samples);
}
