package eu.dariolucia.jsatdecode.distribution.zmq;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Byte fixtures captured from the pre-extraction implementation with a fixed clock. */
class DistributionWireCompatibilityTest {
    @TempDir Path directory;

    @Test void publisherAndRecordingBytesMatchBaseline() throws Exception {
        DistributionWireReplay.capture(directory);
        assertHash("satdecode.bin", "896f994510837121bbc40baa0cbff4fe2658623758715373d11348aa05c84f81");
        assertHash("frame-file.bin", "02a9eb1b7bc6fa3fc6b023d0025fe73681ba328bd74e7e4db4b0bf658f63635b");
        assertHash("jtdp.bin", "5aa661598390cc38067c8dfb5249554715ffa436914f168f7cfd085c5c6369f8");
    }

    private void assertHash(String name, String expected) throws Exception {
        byte[] bytes = Files.readAllBytes(directory.resolve(name));
        assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), name);
    }
}
