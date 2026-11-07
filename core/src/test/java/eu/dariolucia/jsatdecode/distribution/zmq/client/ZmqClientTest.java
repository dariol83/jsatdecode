package eu.dariolucia.jsatdecode.distribution.zmq.client;

import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.fasterxml.jackson.dataformat.cbor.CBORGenerator;
import eu.dariolucia.jsatdecode.api.config.BinaryValue;
import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.NullValue;
import eu.dariolucia.jsatdecode.api.distribution.EventSeverity;
import eu.dariolucia.jsatdecode.api.distribution.RuntimeEvent;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.monitoring.DecoderStatusKeys;
import eu.dariolucia.jsatdecode.api.monitoring.MonitoringSnapshot;
import eu.dariolucia.jsatdecode.distribution.zmq.ZmqFrameDistributor;
import eu.dariolucia.jsatdecode.distribution.zmq.ZmqMonitoringDistributor;
import eu.dariolucia.jsatdecode.util.UtcTime;
import org.zeromq.SocketType;
import org.zeromq.ZContext;
import org.zeromq.ZMQ;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

/** Self-contained wire-decoding and real TCP reference-client contract tests. */
public final class ZmqClientTest {
    private static final byte[] FRAME = ascii("satdecode.v1.frame");
    private static final byte[] STATUS = ascii("satdecode.v1.monitor.status");
    private static final byte[] EVENT = ascii("satdecode.v1.monitor.event");
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static int checks;

    private ZmqClientTest() { }

    /**
     * Executes independent CBOR fixtures, TCP reception and lifecycle checks.
     *
     * @param args unused
     * @throws Exception for a failed fixture, bounded wait or transport operation
     */
    public static void main(String[] args) throws Exception {
        builderValidation();
        keyContract();
        wireValues();
        wireRejections();
        monitoringOnlyAndAbsentService();
        realDistributors();
        channelStatusAndReconnect();
        malformedRecovery();
        callbackIsolationAndShutdown();
        System.out.println("ZMQ reference client: " + checks + " checks passed");
    }

    private static void builderValidation() {
        expect(IllegalStateException.class, () -> ZmqClient.builder().build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint(" ").build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:0").build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:65536").build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://*:5555").build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("inproc://unsupported").build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555")
                .availabilityTimeout(Duration.ZERO).build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555")
                .statusInterval(Duration.ofNanos(-1)).build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555")
                .receiveHighWaterMark(0).build());
        expect(IllegalArgumentException.class, () -> ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555")
                .maxMessageBytes(0).build());
        try (var client = ZmqClient.builder().monitoringEndpoint("tcp://127.0.0.1:5556").build()) {
            check(!client.isRunning() && client.failure().isEmpty(), "unstarted client is healthy and inactive");
            check(client.status(ZmqClient.Channel.FRAMES).isEmpty(), "unconfigured channel has no status");
        }
        var stopped = ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555").build();
        stopped.requestStop();
        expect(IllegalStateException.class, stopped::start);
        stopped.close();
        stopped.close();
        var closed = ZmqClient.builder().framesEndpoint("tcp://127.0.0.1:5555").build();
        closed.close();
        expect(IllegalStateException.class, closed::start);
    }

    private static void keyContract() {
        Set<String> frames = new java.util.HashSet<>(), monitoring = new java.util.HashSet<>();
        for (FrameKey key : FrameKey.values()) {
            check(frames.add(key.key()) && key.valueType() != null, "unique typed frame key " + key);
        }
        for (MonitoringKey key : MonitoringKey.values()) {
            check(monitoring.add(key.key()) && key.valueType() != null, "unique typed monitoring key " + key);
        }
        check(frames.containsAll(frameInformation().keySet()), "frame enum describes every mandatory wire key");
        check(monitoring.containsAll(statusInformation().keySet()), "monitoring enum describes status wire keys");
        check(monitoring.containsAll(eventInformation().keySet()), "monitoring enum describes event wire keys");
        check(FrameKey.SCHEMA_VERSION.key().equals("schema_version") &&
                FrameKey.SCHEMA_VERSION.valueType() == Long.class, "frame integer vocabulary");
        check(MonitoringKey.MESSAGE_TYPE.key().equals("message_type") &&
                MonitoringKey.MESSAGE_TYPE.valueType() == String.class, "monitoring text vocabulary");
        check(MonitoringMetricKey.SIGNAL_SNR_DB.key().equals(DecoderStatusKeys.SIGNAL_SNR_DB) &&
                MonitoringMetricKey.FRAME_LOCK.key().equals(DecoderStatusKeys.FRAME_LOCK) &&
                MonitoringMetricKey.OUTPUT_FRAMES_SENT.key().equals("output.frames_sent"), "nested standardized metric vocabulary");
    }

    private static void wireValues() throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("small", 4L);
        values.put("maximum", Long.MAX_VALUE);
        values.put("minimum", Long.MIN_VALUE);
        values.put("floating", 1.25);
        values.put("nan", Double.NaN);
        values.put("positive_infinity", Double.POSITIVE_INFINITY);
        values.put("negative_infinity", Double.NEGATIVE_INFINITY);
        values.put("boolean", true);
        values.put("null", null);
        values.put("binary", new byte[]{0, 1, (byte) 255});
        values.put("unicode", "caf\u00e8 \ud83d\ude80");
        values.put("nested", Arrays.asList(Map.of("value", -2L), null, new byte[0]));
        Map<String, Object> frame = frameInformation();
        frame.put("metadata", values);
        frame.put("future_extension", "retained");
        var decoded = WireDecoder.decode(frameMessage(frame, new byte[]{7, 8, 9}), MAX_BYTES);
        check(decoded.frame() && Arrays.equals(decoded.payload(), new byte[]{7, 8, 9}), "frame payload and kind");
        check(decoded.information().get("future_extension").equals("retained"), "unknown fields retained");
        Map<String, Object> metadata = map(decoded.information().get("metadata"));
        check(metadata.get("small").equals(4L) && metadata.get("maximum").equals(Long.MAX_VALUE) &&
                metadata.get("minimum").equals(Long.MIN_VALUE), "integers normalized to signed Long");
        check(metadata.get("floating").equals(1.25) && Double.isNaN((Double) metadata.get("nan")), "floating values");
        check(metadata.get("positive_infinity").equals(Double.POSITIVE_INFINITY) &&
                metadata.get("negative_infinity").equals(Double.NEGATIVE_INFINITY), "nonfinite measurements preserved");
        check(metadata.containsKey("null") && metadata.get("null") == null && metadata.get("boolean").equals(true), "null and boolean");
        check(Arrays.equals((byte[]) metadata.get("binary"), new byte[]{0, 1, (byte) 255}), "binary metadata");
        check(metadata.get("unicode").equals("caf\u00e8 \ud83d\ude80"), "strict Unicode text");
        expect(UnsupportedOperationException.class, () -> decoded.information().put("extra", 1L));
        expect(UnsupportedOperationException.class, () -> metadata.put("extra", 1L));
        List<?> nested = (List<?>) metadata.get("nested");
        check(nested.get(1) == null && ((byte[]) nested.get(2)).length == 0, "nested null and empty binary");
        expect(UnsupportedOperationException.class, nested::clear);
        expect(UnsupportedOperationException.class, () -> map(nested.get(0)).put("value", 3L));

        for (Map.Entry<byte[], Map<String, Object>> fixture : List.of(
                Map.entry(STATUS, statusInformation()), Map.entry(EVENT, eventInformation()))) {
            var message = WireDecoder.decode(List.of(fixture.getKey(), encode(fixture.getValue())), MAX_BYTES);
            check(!message.frame() && message.payload() == null, "monitoring has no payload");
            check(message.information().get("sequence").equals(1L), "monitoring envelope integers normalized");
        }

        // Jackson is an independent fixture writer. Replace its double with the
        // protocol's preferred half encoding to exercise every binary16 class.
        for (int half : new int[]{0, 0x8000, 1, 0x3ff, 0x400, 0x3555, 0x3e00, 0x7bff, 0x7c00, 0xfc00, 0x7e00}) {
            Map<String, Object> info = frameInformation();
            info.put("metadata", Map.of("half", 1.23456789));
            byte[] encoded = replace(encode(info), encode(1.23456789), new byte[]{(byte) 0xf9, (byte) (half >>> 8), (byte) half});
            double actual = (Double) map(WireDecoder.decode(List.of(FRAME, encoded, new byte[]{1, 2, 3}), MAX_BYTES)
                    .information().get("metadata")).get("half");
            double expected = halfValue(half);
            check(Double.doubleToLongBits(actual) == Double.doubleToLongBits(expected), "binary16 class " + half);
        }
    }

    private static void wireRejections() throws IOException {
        List<byte[]> good = frameMessage(frameInformation(), new byte[]{1, 2, 3});
        reject(List.of());
        reject(List.of(FRAME));
        reject(good.subList(0, 2));
        reject(List.of(FRAME, good.get(1), good.get(2), new byte[0]));
        reject(List.of(ascii("satdecode.v1.frame.extra"), good.get(1), good.get(2)));
        reject(List.of(FRAME, good.get(1), new byte[]{1}));
        reject(List.of(STATUS, encode(statusInformation()), new byte[0]));
        reject(List.of(EVENT, encode(statusInformation())));
        for (String key : frameInformation().keySet()) {
            Map<String, Object> info = frameInformation();
            info.remove(key);
            reject(frameMessage(info, new byte[]{1, 2, 3}));
        }
        for (Map.Entry<String, Object> change : List.<Map.Entry<String, Object>>of(
                Map.entry("schema_version", 2L), Map.entry("schema_version", "1"),
                Map.entry("message_type", "monitor.status"), Map.entry("sequence", -1L),
                Map.entry("rx_time_ns", -1L), Map.entry("publish_time_ns", -1L),
                Map.entry("frame_length", "3"), Map.entry("frame_length", 0L),
                Map.entry("metadata", List.of()), Map.entry("quality", "MAYBE"),
                Map.entry("rx_time_source", "made_up"), Map.entry("decoder", " "))) {
            Map<String, Object> info = frameInformation();
            info.put(change.getKey(), change.getValue());
            reject(frameMessage(info, new byte[]{1, 2, 3}));
        }
        for (byte[] invalid : List.of(new byte[]{(byte) 0xff}, new byte[]{0},
                new byte[]{(byte) 0xa1, 1, 1}, new byte[]{(byte) 0xa1, 0x61, (byte) 0xff, 0},
                new byte[]{(byte) 0xba, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff})) {
            reject(List.of(FRAME, invalid, new byte[]{1, 2, 3}));
        }
        byte[] encoded = good.get(1);
        for (int length = 0; length < encoded.length; length++) {
            reject(List.of(FRAME, Arrays.copyOf(encoded, length), good.get(2)));
        }
        reject(List.of(FRAME, concat(encoded, new byte[]{0}), good.get(2)));
        reject(List.of(FRAME, duplicateField(frameInformation(), "sequence", 1L), good.get(2)));
        Map<String, Object> blankEnvelopeKey = frameInformation();
        blankEnvelopeKey.put(" ", 1L);
        reject(frameMessage(blankEnvelopeKey, good.get(2)));
        Map<String, Object> blankMetadataKey = frameInformation();
        blankMetadataKey.put("metadata", Map.of(" ", 1L));
        reject(frameMessage(blankMetadataKey, good.get(2)));
        Map<String, Object> nested = frameInformation();
        nested.put("metadata", Map.of("nested", 1.23456789));
        byte[] encodedNested = encode(nested);
        for (byte[] invalid : List.of(
                new byte[]{(byte) 0xa2, 0x61, 0x61, 1, 0x61, 0x61, 2}, // duplicate string key
                new byte[]{(byte) 0xa1, 1, 1},                         // nonstring nested key
                new byte[]{(byte) 0xc0, 0},                           // tagged value
                new byte[]{(byte) 0x9f, 0, (byte) 0xff},               // indefinite array
                new byte[]{(byte) 0xf7},                              // undefined simple value
                new byte[]{0x5b, 0x7f, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff})) { // huge binary length
            reject(List.of(FRAME, replace(encodedNested, encode(1.23456789), invalid), good.get(2)));
        }
        Map<String, Object> overflow = frameInformation();
        overflow.put("sequence", 777L);
        reject(List.of(FRAME, replace(encode(overflow), encode(777L),
                new byte[]{0x1b, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff,
                        (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff}), good.get(2)));
        Object deep = 1L;
        for (int i = 0; i < 100; i++) deep = List.of(deep);
        Map<String, Object> info = frameInformation();
        info.put("metadata", Map.of("deep", deep));
        reject(frameMessage(info, new byte[]{1, 2, 3}));
        int exact = good.stream().mapToInt(p -> p.length).sum();
        check(WireDecoder.decode(good, exact).frame(), "inclusive multipart size bound");
        expect(IllegalArgumentException.class, () -> WireDecoder.decode(good, exact - 1));
        for (Map.Entry<byte[], Map<String, Object>> fixture : List.of(
                Map.entry(STATUS, statusInformation()), Map.entry(EVENT, eventInformation()))) {
            for (String key : fixture.getValue().keySet()) {
                Map<String, Object> missing = new LinkedHashMap<>(fixture.getValue());
                missing.remove(key);
                reject(List.of(fixture.getKey(), encode(missing)));
            }
        }
        for (Map.Entry<String, Object> change : List.<Map.Entry<String, Object>>of(
                Map.entry("timestamp_ns", -1L), Map.entry("metrics", List.of()),
                Map.entry("metric_timestamps_ns", List.of()), Map.entry("metric_timestamps_ns", Map.of()),
                Map.entry("metric_timestamps_ns", Map.of(DecoderStatusKeys.SIGNAL_SNR_DB, -1L)),
                Map.entry("metric_timestamps_ns", Map.of(DecoderStatusKeys.SIGNAL_SNR_DB, 1.0)))) {
            Map<String, Object> invalid = statusInformation();
            invalid.put(change.getKey(), change.getValue());
            reject(List.of(STATUS, encode(invalid)));
        }
        for (Map.Entry<String, Object> change : List.<Map.Entry<String, Object>>of(
                Map.entry("event", " "), Map.entry("severity", "SEVERE"), Map.entry("details", List.of()))) {
            Map<String, Object> invalid = eventInformation();
            invalid.put(change.getKey(), change.getValue());
            reject(List.of(EVENT, encode(invalid)));
        }
    }

    private static void monitoringOnlyAndAbsentService() throws Exception {
        String absentEndpoint;
        try (var publisher = new Publisher()) {
            absentEndpoint = publisher.endpoint;
        }
        AtomicInteger observations = new AtomicInteger();
        try (var client = ZmqClient.builder().framesEndpoint(absentEndpoint).statusInterval(Duration.ofMillis(15))
                .onStatus(value -> observations.incrementAndGet()).build()) {
            long start = System.nanoTime();
            client.start();
            check(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5), "absent publisher does not delay local start");
            await(() -> observations.get() >= 3, "absent publisher still yields periodic local status");
            check(client.isRunning() && client.failure().isEmpty() && !status(client, ZmqClient.Channel.FRAMES).connected() &&
                    !status(client, ZmqClient.Channel.FRAMES).dataPresent(), "missing service is healthy disconnected reception");
        }
        AtomicInteger original = new AtomicInteger(), replacement = new AtomicInteger();
        try (var publisher = new Publisher()) {
            ZmqClient.Builder builder = ZmqClient.builder().monitoringEndpoint(publisher.endpoint).onMonitoring(value -> original.incrementAndGet());
            try (var client = builder.build()) {
                builder.monitoringEndpoint(absentEndpoint).onMonitoring(value -> replacement.incrementAndGet());
                client.start();
                publisher.awaitSubscriptions(2);
                publisher.send(List.of(STATUS, encode(statusInformation())));
                publisher.send(List.of(EVENT, encode(eventInformation())));
                await(() -> original.get() == 2, "monitor-only client receives status and event");
                check(replacement.get() == 0 && client.status(ZmqClient.Channel.FRAMES).isEmpty(), "builder snapshot and disabled frame subscription");
            }
        }
    }

    private static void realDistributors() throws Exception {
        Configuration settings = new Configuration(Map.of("endpoint", "tcp://127.0.0.1:*", "decoder", "test-decoder",
                "receiver_id", "test-rx", "interval_s", 0.025, "linger_ms", 0));
        BlockingQueue<ReceivedFrame> received = new LinkedBlockingQueue<>();
        BlockingQueue<Map<String, Object>> monitoring = new LinkedBlockingQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try (var frames = new ZmqFrameDistributor(settings); var monitor = new ZmqMonitoringDistributor(settings)) {
            frames.start();
            monitor.start();
            try (var client = ZmqClient.builder().framesEndpoint(frames.endpoint().orElseThrow())
                    .monitoringEndpoint(monitor.endpoint().orElseThrow()).statusInterval(Duration.ofMillis(20))
                    .onFrame((payload, information) -> received.add(new ReceivedFrame(payload, information)))
                    .onMonitoring(monitoring::add).onError((channel, error) -> errors.add(error)).build()) {
                client.start();
                await(() -> status(client, ZmqClient.Channel.FRAMES).connected() &&
                        status(client, ZmqClient.Channel.MONITORING).connected(), "both real TCP publishers connect");
                AnnotatedFrame frame = new AnnotatedFrame(new byte[]{3, 4, 5}, 123456789L,
                        Map.of("rx_time_source", "recording", "frame_type", "CCSDS_TM", "quality", "GOOD",
                                "opaque", new BinaryValue(new byte[]{0, (byte) 255}), "absent", NullValue.INSTANCE));
                awaitPublishing(() -> !received.isEmpty(), () -> frames.distribute(frame), "real frame distributor reception");
                ReceivedFrame first = received.remove();
                check(Arrays.equals(first.payload(), frame.data()), "real publisher exact payload");
                check(first.information().get("rx_time_ns").equals(123456789L) &&
                        first.information().get("rx_time_source").equals("recording"), "real UTC reception metadata");
                check(first.information().get("frame_type").equals("CCSDS_TM") &&
                        first.information().get("quality").equals("GOOD"), "real frame classification");
                Map<String, Object> metadata = map(first.information().get("metadata"));
                check(Arrays.equals((byte[]) metadata.get("opaque"), new byte[]{0, (byte) 255}) &&
                        metadata.containsKey("absent") && metadata.get("absent") == null, "real binary/null metadata");
                monitor.publishMonitoring(new MonitoringSnapshot(123456789L,
                        Map.of(DecoderStatusKeys.SIGNAL_SNR_DB, 6.25, DecoderStatusKeys.FRAME_LOCK, "LOCKED")));
                await(() -> monitoring.stream().anyMatch(value -> value.get("message_type").equals("monitor.status") &&
                        map(value.get("metrics")).getOrDefault(DecoderStatusKeys.FRAME_LOCK, "").equals("LOCKED")),
                        "merged real monitoring state");
                RuntimeEvent event = new RuntimeEvent(123456789L, EventSeverity.WARNING, "test", "test.event", "Test event",
                        Map.of("metric", DecoderStatusKeys.FRAME_LOCK, "opaque", new BinaryValue(new byte[]{1, 2})));
                awaitPublishing(() -> monitoring.stream().anyMatch(value -> value.get("message_type").equals("monitor.event")),
                        () -> monitor.publishEvent(event), "real event distributor reception");
                Map<String, Object> observed = monitoring.stream().filter(value -> value.get("message_type").equals("monitor.event"))
                        .findFirst().orElseThrow();
                check(observed.get("event").equals("test.event") && observed.get("severity").equals("WARNING") &&
                        observed.get("timestamp_ns").equals(123456789L), "real event identity, severity and UTC");
                check(map(observed.get("details")).get("metric").equals(DecoderStatusKeys.FRAME_LOCK), "real event details");
                check(errors.isEmpty() && client.failure().isEmpty(), "real publishers have no client errors");
            }
            check(frames.failure().isEmpty() && monitor.failure().isEmpty(), "real distributor workers healthy");
        }
    }

    private static void channelStatusAndReconnect() throws Exception {
        List<ClientStatus> observations = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicInteger framesReceived = new AtomicInteger(), monitoringReceived = new AtomicInteger();
        try (var frames = new Publisher(); var monitor = new Publisher()) {
            String reconnectEndpoint = frames.endpoint;
            try (var client = ZmqClient.builder().framesEndpoint(frames.endpoint).monitoringEndpoint(monitor.endpoint)
                    .availabilityTimeout(Duration.ofMillis(180)).statusInterval(Duration.ofMillis(20))
                    .onFrame((bytes, info) -> framesReceived.incrementAndGet()).onMonitoring(info -> monitoringReceived.incrementAndGet())
                    .onStatus(observations::add).build()) {
                client.start();
                frames.awaitSubscriptions(1);
                monitor.awaitSubscriptions(2);
                await(() -> status(client, ZmqClient.Channel.FRAMES).connected() &&
                        status(client, ZmqClient.Channel.MONITORING).connected(), "socket-monitor connected events");
                check(!status(client, ZmqClient.Channel.FRAMES).dataPresent() &&
                        !status(client, ZmqClient.Channel.MONITORING).dataPresent(), "connected idle services have no data");
                long frameBytes = 0, monitoringBytes = 0;
                for (int i = 0; i < 10; i++) frameBytes += frames.send(frameMessage(frameInformation(), new byte[]{1, 2, 3}));
                for (int i = 0; i < 5; i++) monitoringBytes += monitor.send(List.of(STATUS, encode(statusInformation())));
                for (int i = 0; i < 3; i++) monitoringBytes += monitor.send(List.of(EVENT, encode(eventInformation())));
                await(() -> framesReceived.get() == 10 && monitoringReceived.get() == 8, "exact subscribed multipart delivery");
                await(() -> observations.stream().anyMatch(value -> value.channel() == ZmqClient.Channel.FRAMES &&
                        value.receivedBytesPerSecond() > 0 && value.receivedMessagesPerSecond() > 0), "positive receive rates");
                ClientStatus f = status(client, ZmqClient.Channel.FRAMES), m = status(client, ZmqClient.Channel.MONITORING);
                check(f.receivedMessages() == 10 && m.receivedMessages() == 8, "channel counters independent");
                check(f.receivedBytes() == frameBytes && m.receivedBytes() == monitoringBytes, "byte rate counts multipart bodies including topic");
                check(f.malformedMessages() == 0 && m.malformedMessages() == 0, "healthy inputs are well formed");
                check(f.lastReceiveTimeNanos() > 0 && Math.abs(f.timestampNanos() - UtcTime.nowNanos()) < TimeUnit.SECONDS.toNanos(5),
                        "status timestamps use UTC epoch nanoseconds");
                await(() -> !status(client, ZmqClient.Channel.FRAMES).dataPresent() &&
                        !status(client, ZmqClient.Channel.MONITORING).dataPresent(), "time-window inactivity for each channel");
                await(() -> status(client, ZmqClient.Channel.FRAMES).receivedBytesPerSecond() == 0 &&
                        status(client, ZmqClient.Channel.FRAMES).receivedMessagesPerSecond() == 0, "idle receive rate returns to zero");
                check(status(client, ZmqClient.Channel.FRAMES).connected(), "inactivity retains actual TCP connection state");
                frames.close();
                await(() -> !status(client, ZmqClient.Channel.FRAMES).connected(), "publisher disconnect observed independently");
                check(status(client, ZmqClient.Channel.MONITORING).connected(), "other endpoint connection preserved");
                try (var replacement = new Publisher(reconnectEndpoint)) {
                    replacement.awaitSubscriptions(1);
                    replacement.send(frameMessage(frameInformation(), new byte[]{1, 2, 3}));
                    await(() -> framesReceived.get() == 11 && status(client, ZmqClient.Channel.FRAMES).connected() &&
                            status(client, ZmqClient.Channel.FRAMES).dataPresent(), "publisher restart reconnects and resumes data");
                    check(status(client, ZmqClient.Channel.FRAMES).receivedMessages() == 11, "reconnect preserves lifetime counters");
                }
                client.close();
                check(!client.isRunning() && !status(client, ZmqClient.Channel.FRAMES).running() &&
                        !status(client, ZmqClient.Channel.FRAMES).connected() && !status(client, ZmqClient.Channel.FRAMES).dataPresent(),
                        "close publishes inactive disconnected status");
                expect(IllegalStateException.class, client::start);
                client.close();
            }
        }
    }

    private static void malformedRecovery() throws Exception {
        AtomicInteger accepted = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        try (var publisher = new Publisher(); var client = ZmqClient.builder().framesEndpoint(publisher.endpoint)
                .maxMessageBytes(1024).statusInterval(Duration.ofMillis(10))
                .onFrame((bytes, info) -> accepted.incrementAndGet()).onError((channel, error) -> errors.add(error)).build()) {
            client.start();
            publisher.awaitSubscriptions(1);
            publisher.send(List.of(FRAME, new byte[]{(byte) 0xff}, new byte[]{1}));
            publisher.send(List.of(FRAME, encode(frameInformation()), new byte[]{1, 2, 3}, new byte[]{4}));
            Map<String, Object> oversized = frameInformation();
            oversized.put("metadata", Map.of("padding", "x".repeat(500)));
            oversized.put("frame_length", 600L);
            publisher.send(frameMessage(oversized, new byte[600]));
            publisher.send(List.of(FRAME, encode(statusInformation()), new byte[]{1, 2, 3}));
            List<byte[]> manyParts = new java.util.ArrayList<>(frameMessage(frameInformation(), new byte[]{1, 2, 3}));
            for (int i = 0; i < 100; i++) manyParts.add(new byte[0]);
            publisher.send(manyParts);
            await(() -> status(client, ZmqClient.Channel.FRAMES).malformedMessages() == 5,
                    "all malformed inputs are rejected");
            check(accepted.get() == 0 && !status(client, ZmqClient.Channel.FRAMES).dataPresent(), "malformed traffic does not establish data presence");
            publisher.send(frameMessage(frameInformation(), new byte[]{1, 2, 3}));
            await(() -> accepted.get() == 1 && status(client, ZmqClient.Channel.FRAMES).malformedMessages() == 5,
                    "malformed multipart messages drain and valid reception resumes");
            check(errors.size() == 5 && client.failure().isEmpty() && client.isRunning(), "bad input is reported without worker failure");
        }
    }

    private static void callbackIsolationAndShutdown() throws Exception {
        AtomicInteger received = new AtomicInteger(), callbackDepth = new AtomicInteger(), errorCalls = new AtomicInteger();
        AtomicReference<ZmqClient> reference = new AtomicReference<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        ConcurrentLinkedQueue<Thread> callbackThreads = new ConcurrentLinkedQueue<>();
        try (var publisher = new Publisher(); var client = ZmqClient.builder().framesEndpoint(publisher.endpoint)
                .statusInterval(Duration.ofMillis(15)).onFrame((bytes, info) -> {
                    check(callbackDepth.incrementAndGet() == 1, "callbacks never overlap");
                    callbackThreads.add(Thread.currentThread());
                    try {
                        int count = received.incrementAndGet();
                        if (count == 1) {
                            bytes[0] = 99;
                            ((byte[]) map(info.get("metadata")).get("binary"))[0] = 99;
                            throw new IllegalStateException("expected consumer failure");
                        }
                        if (count == 2) {
                            check(bytes[0] == 1 && ((byte[]) map(info.get("metadata")).get("binary"))[0] == 7,
                                    "callback payload and nested binary arrays are never reused");
                            expect(IllegalStateException.class, reference.get()::start);
                            expect(IllegalStateException.class, reference.get()::close);
                            reference.get().requestStop();
                        }
                    } catch (AssertionError error) {
                        callbackFailure.set(error);
                        reference.get().requestStop();
                    } finally {
                        callbackDepth.decrementAndGet();
                    }
                }).onError((channel, error) -> {
                    errorCalls.incrementAndGet();
                    throw new IllegalArgumentException("expected error-observer failure");
                }).build()) {
            reference.set(client);
            client.start();
            publisher.awaitSubscriptions(1);
            Map<String, Object> binaryInformation = frameInformation();
            binaryInformation.put("metadata", Map.of("binary", new byte[]{7}));
            publisher.send(frameMessage(binaryInformation, new byte[]{1, 2, 3}));
            await(() -> received.get() == 1 && errorCalls.get() >= 1, "callback exception reaches error observer");
            publisher.send(frameMessage(binaryInformation, new byte[]{1, 2, 3}));
            await(() -> !client.isRunning(), "callback-safe requestStop ends receive loop");
            client.close();
            check(received.get() == 2 && callbackFailure.get() == null && client.failure().isEmpty(),
                    "observer exceptions are isolated and next frame received");
            check(callbackThreads.size() == 2 && callbackThreads.stream().distinct().count() == 1 &&
                    callbackThreads.peek() != Thread.currentThread(), "serial callbacks run on owned worker");
            check(callbackThreads.stream().noneMatch(Thread::isAlive), "management close joins callback worker");
            expect(IllegalStateException.class, client::start);
        }
        try (var publisher = new Publisher(); var client = ZmqClient.builder().framesEndpoint(publisher.endpoint).build()) {
            client.start();
            publisher.awaitSubscriptions(1);
            Thread.currentThread().interrupt();
            try {
                client.close();
                check(Thread.currentThread().isInterrupted() && !client.isRunning(), "close joins and preserves management interruption");
            } finally {
                Thread.interrupted();
            }
        }
    }

    private static Map<String, Object> common(String type) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema_version", 1L);
        result.put("message_type", type);
        result.put("receiver_id", "test-rx");
        result.put("decoder", "test-decoder");
        result.put("sequence", 1L);
        return result;
    }

    private static Map<String, Object> frameInformation() {
        Map<String, Object> result = common("tm.frame");
        result.put("rx_time_ns", 123456789L);
        result.put("rx_time_source", "recording");
        result.put("publish_time_ns", 234567890L);
        result.put("frame_type", "RAW");
        result.put("frame_length", 3L);
        result.put("quality", "UNKNOWN");
        result.put("metadata", Map.of());
        return result;
    }

    private static Map<String, Object> statusInformation() {
        Map<String, Object> result = common("monitor.status");
        result.put("timestamp_ns", 123456789L);
        result.put("metrics", Map.of(DecoderStatusKeys.SIGNAL_SNR_DB, 3.5));
        result.put("metric_timestamps_ns", Map.of(DecoderStatusKeys.SIGNAL_SNR_DB, 123456789L));
        return result;
    }

    private static Map<String, Object> eventInformation() {
        Map<String, Object> result = common("monitor.event");
        result.put("timestamp_ns", 123456789L);
        result.put("event", "test.event");
        result.put("severity", "INFO");
        result.put("details", Map.of("test", true));
        return result;
    }

    private static List<byte[]> frameMessage(Map<String, Object> information, byte[] payload) throws IOException {
        return List.of(FRAME, encode(information), payload);
    }

    private static byte[] encode(Object value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CBORGenerator generator = new CBORFactory().createGenerator(output)) {
            write(generator, value);
        }
        return output.toByteArray();
    }

    private static void write(CBORGenerator generator, Object value) throws IOException {
        if (value == null) generator.writeNull();
        else if (value instanceof String text) generator.writeString(text);
        else if (value instanceof Long integer) generator.writeNumber(integer);
        else if (value instanceof Double floating) generator.writeNumber(floating);
        else if (value instanceof Boolean bool) generator.writeBoolean(bool);
        else if (value instanceof byte[] bytes) generator.writeBinary(bytes);
        else if (value instanceof Map<?, ?> map) {
            generator.writeStartObject(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                generator.writeFieldName((String) entry.getKey());
                write(generator, entry.getValue());
            }
            generator.writeEndObject();
        } else if (value instanceof List<?> list) {
            generator.writeStartArray(list.size());
            for (Object item : list) write(generator, item);
            generator.writeEndArray();
        } else throw new AssertionError("Unsupported fixture value: " + value);
    }

    private static byte[] duplicateField(Map<String, Object> value, String key, Object duplicate) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (CBORGenerator generator = new CBORFactory().createGenerator(output)) {
            generator.writeStartObject(value.size() + 1);
            for (Map.Entry<String, Object> entry : value.entrySet()) {
                generator.writeFieldName(entry.getKey());
                write(generator, entry.getValue());
            }
            generator.writeFieldName(key);
            write(generator, duplicate);
            generator.writeEndObject();
        }
        return output.toByteArray();
    }

    private static byte[] replace(byte[] source, byte[] search, byte[] replacement) {
        for (int at = 0; at <= source.length - search.length; at++) {
            if (Arrays.equals(source, at, at + search.length, search, 0, search.length)) {
                byte[] result = new byte[source.length - search.length + replacement.length];
                System.arraycopy(source, 0, result, 0, at);
                System.arraycopy(replacement, 0, result, at, replacement.length);
                System.arraycopy(source, at + search.length, result, at + replacement.length, source.length - at - search.length);
                return result;
            }
        }
        throw new AssertionError("Fixture replacement not found");
    }

    private static double halfValue(int half) {
        int sign = half >>> 15, exponent = (half >>> 10) & 31, mantissa = half & 1023;
        double magnitude = exponent == 0 ? Math.scalb((double) mantissa, -24) :
                exponent == 31 ? (mantissa == 0 ? Double.POSITIVE_INFINITY : Double.NaN) :
                        Math.scalb(1.0 + mantissa / 1024.0, exponent - 15);
        return sign == 0 ? magnitude : -magnitude;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static ClientStatus status(ZmqClient client, ZmqClient.Channel channel) {
        return client.status(channel).orElseGet(() -> new ClientStatus(channel, "", false, false, false,
                0, 0, 0, 0, 0, 0, 0));
    }

    private static void reject(List<byte[]> message) {
        expect(IllegalArgumentException.class, () -> WireDecoder.decode(message, MAX_BYTES));
    }

    private static void await(BooleanSupplier condition, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("Timeout: " + description);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
        checks++;
    }

    private static void awaitPublishing(BooleanSupplier condition, Runnable publish, String description) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) throw new AssertionError("Timeout: " + description);
            publish.run();
            long next = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(25);
            while (!condition.getAsBoolean() && System.nanoTime() - next < 0)
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        checks++;
    }

    private static void expect(Class<? extends Throwable> type, Runnable operation) {
        try {
            operation.run();
        } catch (Throwable error) {
            if (type.isInstance(error)) {
                checks++;
                return;
            }
            throw new AssertionError("Expected " + type.getSimpleName() + " but received " + error, error);
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks++;
    }

    private record ReceivedFrame(byte[] payload, Map<String, Object> information) { }

    /* XPUB supplies a subscription acknowledgment so delivery/count assertions
       do not depend on guessed slow-joiner sleeps. The test thread owns it. */
    private static final class Publisher implements AutoCloseable {
        private final ZContext context = new ZContext();
        private final ZMQ.Socket socket = context.createSocket(SocketType.XPUB);
        private final String endpoint;
        private boolean closed;

        Publisher() {
            this("tcp://127.0.0.1:*");
        }

        Publisher(String endpoint) {
            socket.setLinger(0);
            socket.setReceiveTimeOut(20);
            socket.setSendTimeOut(1000);
            socket.setSndHWM(1000);
            socket.setXpubVerbose(true);
            check(socket.bind(endpoint), "test publisher bind");
            this.endpoint = socket.getLastEndpoint();
        }

        void awaitSubscriptions(int expected) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            int received = 0;
            while (received < expected) {
                if (System.nanoTime() - deadline >= 0) throw new AssertionError("Timeout: XPUB subscriptions " + received + "/" + expected);
                byte[] subscription = socket.recv();
                if (subscription != null && subscription.length > 0 && subscription[0] == 1) received++;
            }
            checks++;
        }

        long send(List<byte[]> parts) {
            long bytes = 0;
            for (int index = 0; index < parts.size(); index++) {
                byte[] part = parts.get(index);
                check(socket.send(part, index + 1 < parts.size() ? ZMQ.SNDMORE : 0), "test multipart send");
                bytes += part.length;
            }
            return bytes;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                socket.close();
                context.close();
            }
        }
    }
}
