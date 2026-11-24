package eu.dariolucia.jsatdecode.distribution.zmq;

import eu.dariolucia.jsatdecode.api.config.Configuration;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorDescriptor;
import eu.dariolucia.jsatdecode.api.distribution.FrameDistributorFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;

/**
 * ServiceLoader factory for bounded SatDecode multipart disk recordings.
 */
public final class FrameFileDistributorFactory implements FrameDistributorFactory {
    /**
     * Creates an inert factory.
     */
    public FrameFileDistributorFactory() {
    }

    @Override
    public FrameDistributorDescriptor descriptor() {
        return new FrameDistributorDescriptor("frame-file", "Frame recording", "1", "Length-prefixed SatDecode v1 multipart frames");
    }

    @Override
    public FrameDistributor create(Configuration configuration) {
        Path folder = Path.of(configuration.getString("folder"));
        String name = configuration.asMap().containsKey("filename_decoder") ? configuration.getString("filename_decoder") : configuration.getString("decoder");
        var settings = new LinkedHashMap<String, Object>(configuration.asMap());
        settings.remove("folder");
        settings.remove("filename_decoder");
        return new FrameFileDistributor(folder, name, new Configuration(settings));
    }
}
