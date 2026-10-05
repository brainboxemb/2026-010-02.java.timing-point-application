package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds one complete {@link Application} object graph from validated runtime input.
 *
 * <p>This class is construction-only. It is not another runtime component and is
 * not involved in normal domain/application calls after {@link #build()} returns.
 * Keeping construction here makes ownership visible: runtime creates executors,
 * storage adapters and I/O managers, then injects them into the components that
 * own the corresponding behaviour.</p>
 */
public final class ApplicationBootstrap {
    private static final int ANTENNA_CONTROL_QUEUE_CAPACITY = 8;
    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);

    private final BuildIdentity buildIdentity;
    private final Config config;

    private List<AntennaInstallation> antennaInstallations =
            Collections.emptyList();
    private TagRegistrationMapper tagRegistrationMapper =
            tagId -> null;

    private ApplicationBootstrap(
            BuildIdentity buildIdentity,
            Config config) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        this.buildIdentity = buildIdentity;
        this.config = config;
    }

    public static ApplicationBootstrap builder(
            BuildIdentity buildIdentity,
            Config config) {
        return new ApplicationBootstrap(buildIdentity, config);
    }

    /**
     * Supplies installation-level antennas for an explicitly composed runtime.
     *
     * <p>Production IF-11 mapping can call this once antenna installation
     * configuration is consumed. The simulator uses the same path and therefore
     * exercises the real AntennaManager/TagProcessor/TimingNode components.</p>
     */
    public ApplicationBootstrap antennaInstallations(
            List<AntennaInstallation> installations) {
        if (installations == null) {
            throw new IllegalArgumentException(
                    "installations must not be null");
        }

        List<AntennaInstallation> copy =
                new ArrayList<AntennaInstallation>(installations.size());
        for (AntennaInstallation installation : installations) {
            if (installation == null) {
                throw new IllegalArgumentException(
                        "installations must not contain null");
            }
            copy.add(installation);
        }
        antennaInstallations = Collections.unmodifiableList(copy);
        return this;
    }

    public ApplicationBootstrap tagRegistrationMapper(
            TagRegistrationMapper mapper) {
        if (mapper == null) {
            throw new IllegalArgumentException(
                    "mapper must not be null");
        }
        tagRegistrationMapper = mapper;
        return this;
    }

    /**
     * Constructs the application graph and transfers component lifecycle to
     * {@link Application}.
     */
    public Application build() {
        requireCompleteConfig();

        ApplicationConfiguration applicationConfiguration =
                ApplicationConfiguration.singleTimingNode(
                        config.timingNodeId(),
                        config.tagProcessingPolicy());

        RuntimeExecutors executors = new RuntimeExecutors();
        try {
            RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                    executors.createTimingNodeExecutors(
                            config.timingNodeId());

            TimingDataPersistence persistence =
                    new DefaultTimingDataPersistence(
                            new FileAppendOnlyRecordStore(
                                    config.timingDataPath()),
                            config.timingNodeId(),
                            new DefaultTimingDataCodec());

            /*
             * TimingNode owns its TagProcessor as a node-local child component.
             * Runtime supplies both serial lanes and deployment dependencies;
             * TimingNode decides when those child/component lanes start and stop.
             */
            TimingNode timingNode = new TimingNode(
                    config.timingNodeId(),
                    persistence,
                    new DefaultTimingDataFactory(),
                    () -> new TimingTimestamp(Instant.now()),
                    applicationConfiguration
                            .timingNode(config.timingNodeId())
                            .tagProcessing(),
                    tagRegistrationMapper,
                    nodeExecutors.timingNode(),
                    nodeExecutors.tagProcessor());

            AntennaManager antennaManager = null;
            if (!antennaInstallations.isEmpty()) {
                antennaManager = new AntennaManager(
                        antennaInstallations,
                        executors.sharedIoExecutor(),
                        executors.antennaScheduler(),
                        ANTENNA_CONTROL_QUEUE_CAPACITY,
                        ANTENNA_CONTROL_TIMEOUT);
            }

            return new Application(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    antennaManager,
                    executors);
        } catch (RuntimeException ex) {
            executors.close();
            throw ex;
        }
    }

    private void requireCompleteConfig() {
        if (config.timingDataPath() == null) {
            throw new IllegalArgumentException(
                    "TimingData storage path must be configured before composition");
        }
    }
}
