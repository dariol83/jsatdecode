package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.EventMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.FrameRecord;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.MonitoringMessage;
import eu.dariolucia.jsatdecode.distribution.tcp.codec.TcpMessageEncoder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Deterministic pre/post-extraction protocol and recording capture. */
public final class DistributionWireReplay {
    private DistributionWireReplay() { }

    /** @param args new, empty output directory */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected output directory");
        Path directory = Path.of(args[0]);
        Files.createDirectory(directory);
        capture(directory);
        for (String name : List.of("satdecode.bin", "frame-file.bin", "jtdp.bin")) {
            byte[] bytes = Files.readAllBytes(directory.resolve(name));
            System.out.println(name + " bytes=" + bytes.length + " sha256="
                    + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        }
    }

    static void capture(Path directory) throws Exception {
        var config = new Configuration(Map.of("decoder", "fixture-decoder", "receiver_id", "fixture-receiver",
                "queue_capacity", 8));
        var clock = new UtcClock(() -> 1_700_000_000_123_456_789L);
        var wire = new SatDecodeWire(ZmqSettings.read(config, false), clock);
        var first = new AnnotatedFrame(new byte[]{0, 1, (byte) 0xff, 42}, 123456789L,
                Map.of("quality", "GOOD", "frame_type", "TM", "rx_time_source", "recording", "snr", 4.5));
        var second = new AnnotatedFrame(new byte[]{9, 8, 7}, 123456799L,
                Map.of("frame.valid", false, "metadata", Map.of("counter", 2L)));
        var firstParts = wire.frame(first, 0);
        var secondParts = wire.frame(second, 1);

        var recorded = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(recorded)) {
            writeParts(out, firstParts);
            writeParts(out, secondParts);
        }
        var all = new ByteArrayOutputStream();
        try (var out = new DataOutputStream(all)) {
            out.write(recorded.toByteArray());
            writeParts(out, wire.status(Map.of("carrier.lock", "LOCKED", "frame.good", 12L, "signal.snr_db", 4.5),
                    Map.of("frame.good", 123456789L), 2));
            writeParts(out, wire.event(new RuntimeEvent(123456800L, EventSeverity.WARNING, "decoder",
                    "frame.loss", "Lost frame", Map.of("reason", "fixture", "count", 2L)), 3));
        }
        write(directory.resolve("satdecode.bin"), all.toByteArray());

        Path folder = directory.resolve("recording");
        try (var output = new FrameFileDistributor(folder, "fixture-decoder", config, clock)) {
            output.start();
            output.distribute(first);
            output.distribute(second);
            output.stop();
            if (output.monitoringSnapshot().messagesSent() != 2 || output.monitoringSnapshot().messagesDropped() != 0)
                throw new AssertionError("Frame recording did not drain both frames");
            byte[] bytes = Files.readAllBytes(output.file());
            if (!Arrays.equals(recorded.toByteArray(), bytes)) throw new AssertionError("Frame recording differs from SatDecode frames");
            write(directory.resolve("frame-file.bin"), bytes);
        }

        var encoder = new TcpMessageEncoder(0xBEEF, 4096);
        var jtdp = new ByteArrayOutputStream();
        jtdp.write(encoder.encodeFrameBatch(List.of(new FrameRecord((byte) 1, 123456789L, first.data()),
                new FrameRecord((byte) 2, 123456799L, second.data()))));
        jtdp.write(encoder.encodeMonitoring(new MonitoringMessage((byte) 0x6D, 145000000L, -123,
                4.5f, 14, 2, 12, 2)));
        jtdp.write(encoder.encodeEvent(new EventMessage((byte) 7, 123456800L, (byte) 0xFC, 145000000L)));
        write(directory.resolve("jtdp.bin"), jtdp.toByteArray());
    }

    private static void writeParts(DataOutputStream out, List<byte[]> parts) throws Exception {
        for (byte[] part : parts) {
            out.writeInt(part.length);
            out.write(part);
        }
    }

    private static void write(Path path, byte[] bytes) throws Exception {
        Files.write(path, bytes, StandardOpenOption.CREATE_NEW);
    }
}
