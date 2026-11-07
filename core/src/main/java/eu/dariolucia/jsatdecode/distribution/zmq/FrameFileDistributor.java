package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.config.ConfigurationException;
import eu.dariolucia.jsatdecode.api.distribution.*;
import eu.dariolucia.jsatdecode.api.frame.AnnotatedFrame;
import eu.dariolucia.jsatdecode.api.lifecycle.ComponentState;
import eu.dariolucia.jsatdecode.core.distribution.SyntheticFrameDistributor;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Append-only SatDecode v1 multipart recording. Each frame stores three parts,
 * each preceded by a four-byte big-endian length: topic, CBOR metadata, payload.
 * Part contents use the same encoder as ZMQ, with no socket or network activity.
 * Submission only enqueues immutable frames in a bounded mailbox; overflow fails
 * rather than silently dropping recordings. Management serializes lifecycle and
 * quiesces producers before stop, which joins the worker and flushes/closes disk.
 */
public final class FrameFileDistributor implements FrameDistributor {
    private final Path folder;
    private final String decoder;
    private final SatDecodeWire wire;
    private final SyntheticFrameDistributor output;
    private DataOutputStream stream;
    private long sequence;
    private volatile Path file;
    private volatile Throwable failure;

    /**
     * Validates settings without creating folders, files or threads.
     *
     * @param folder        destination directory, created on start if missing
     * @param decoder       registered decoder name used in the filename; safe single path component
     * @param configuration normalized unprefixed frame-distribution identity/size/queue settings
     */
    public FrameFileDistributor(Path folder, String decoder, Configuration configuration) {
        this(folder, decoder, configuration, new UtcClock());
    }

    FrameFileDistributor(Path folder, String decoder, Configuration configuration, UtcClock clock) {
        this.folder = Objects.requireNonNull(folder);
        if (decoder == null || !decoder.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"))
            throw new ConfigurationException("Decoder name is not safe for a recording filename");
        this.decoder = decoder;
        ZmqSettings settings = ZmqSettings.read(configuration, false);
        wire = new SatDecodeWire(settings, clock);
        output = new SyntheticFrameDistributor(settings.capacity(), OverflowPolicy.FAIL_RUNTIME, this::write);
    }

    private void write(AnnotatedFrame frame) {
        try {
            if (sequence < 0) throw new OutputException("Recording sequence exhausted");
            for (byte[] part : wire.frame(frame, sequence++)) {
                stream.writeInt(part.length);
                stream.write(part);
            }
        } catch (IOException e) {
            throw new OutputException("Cannot write frame recording: " + file, e);
        }
    }

    /**
     * Returns the created recording path, or null before successful file creation.
     *
     * @return recording path; management/observer reads may be concurrent
     */
    public Path file() {
        return file;
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("frame-file", "Frame recording", "1", "Length-prefixed SatDecode v1 multipart frames");
    }

    @Override
    public void distribute(AnnotatedFrame frame) {
        output.distribute(frame);
    }

    @Override
    public void start() {
        if (state() != ComponentState.CONFIGURED) throw new IllegalStateException("Recording is one-shot");
        try {
            Files.createDirectories(folder);
            Path destination = folder.resolve(decoder + "_" + DateTimeFormatter.ofPattern("yyyy_MM_dd_HH_mm_ss")
                    .withZone(ZoneOffset.UTC).format(Instant.now()) + ".frame.bin");
            stream = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)));
            file = destination;
            output.start();
        } catch (IOException | RuntimeException e) {
            failure = e;
            closeStream();
            throw new OutputException("Cannot start frame recording in " + folder, e);
        }
    }

    @Override
    public void stop() {
        output.stop();
        closeStream();
    }

    private void closeStream() {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException e) {
                failure = e;
                throw new OutputException("Cannot close frame recording: " + file, e);
            } finally {
                stream = null;
            }
        }
    }

    @Override
    public void close() {
        try {
            stop();
        } finally {
            output.close();
        }
    }

    @Override
    public ComponentState state() {
        return failure != null && output.state() != ComponentState.CLOSED ? ComponentState.FAILED : output.state();
    }

    @Override
    public DistributionSnapshot monitoringSnapshot() {
        DistributionSnapshot snapshot = output.monitoringSnapshot();
        return failure == null ? snapshot : new DistributionSnapshot(false, false, snapshot.messagesReceived(), snapshot.messagesSent(), snapshot.messagesDropped(), snapshot.errors() + 1,
                snapshot.queueDepth(), snapshot.queueCapacity(), snapshot.timestampNanos());
    }
}
