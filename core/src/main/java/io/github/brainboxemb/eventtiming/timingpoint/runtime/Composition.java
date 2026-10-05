package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.Conductor;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.DynamicConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaInstallation;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsole;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServer;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Api;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.configuration.ApplicationConfiguration;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Concrete composition root for one SI-01 executable.
 *
 * <p>The important rule is intentionally simple: read validated configuration,
 * construct objects, wire events, then start owned execution resources and
 * components. Component constructors do not hide cross-component wiring or start
 * physical application workers.</p>
 */
public final class Composition {
    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);

    private Composition() {
    }

    public static void run(
            BuildIdentity buildIdentity,
            Config config)
            throws IOException {
        Application application =
                create(buildIdentity, config);

        Runtime runtime = Runtime.getRuntime();
        Thread shutdownHook =
                new Thread(
                        application::close,
                        "tp-run-shutdown");
        runtime.addShutdownHook(shutdownHook);

        HttpEndpoint http = null;
        WebSocketEndpoint webSocket = null;
        RemoteShellServer remoteShell = null;
        try {
            application.start();

            http =
                    startHttp(
                            config,
                            application);
            webSocket =
                    startWebSocket(
                            config,
                            application);
            remoteShell =
                    startRemoteShell(
                            config,
                            application);
            startLocalConsole(application);

            try {
                application.awaitStopped();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (webSocket != null) {
                webSocket.close();
            }
            if (http != null) {
                http.close();
            }
            if (remoteShell != null) {
                remoteShell.close();
            }
            application.close();
            removeShutdownHook(
                    runtime,
                    shutdownHook);
        }

        System.out.println(
                application.smokeOutput());
    }

    static Application create(
            BuildIdentity buildIdentity,
            Config config) {
        return create(
                buildIdentity,
                config,
                Collections.<AntennaInstallation>emptyList(),
                tagId -> null);
    }

    /**
     * Composes the same production object graph with explicitly supplied antenna
     * installations and tag mapping. Simulation uses this overload rather than a
     * separate runtime path.
     */
    public static Application create(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            TagRegistrationMapper tagRegistrationMapper) {
        requireCompositionInput(
                buildIdentity,
                config,
                antennaInstallations,
                tagRegistrationMapper);

        List<AntennaInstallation> installations =
                copyInstallations(
                        antennaInstallations);

        /*
         * 1. Resolve the runtime configuration objects used by the composed
         *    components.
         */
        ApplicationConfiguration applicationConfiguration =
                ApplicationConfiguration.singleTimingNode(
                        config.timingNodeId(),
                        config.tagProcessingPolicy());

        /*
         * 2. Construct shared Runtime execution resources. Their constructors do
         *    not start physical workers.
         */
        RuntimeExecutors executors =
                new RuntimeExecutors();

        try {
            RuntimeExecutors.TimingNodeExecutors nodeExecutors =
                    executors.createTimingNodeExecutors(
                            config.timingNodeId());

            /*
             * 3. Construct Infrastructure, Domain, I/O and Application objects.
             */
            TimingDataPersistence persistence =
                    new DefaultTimingDataPersistence(
                            new FileAppendOnlyRecordStore(
                                    config.timingDataPath()),
                            config.timingNodeId(),
                            new DefaultTimingDataCodec());

            TimingNode timingNode =
                    new TimingNode(
                            config.timingNodeId(),
                            persistence,
                            new DefaultTimingDataFactory(),
                            () -> new TimingTimestamp(
                                    Instant.now()),
                            applicationConfiguration
                                    .timingNode(
                                            config.timingNodeId())
                                    .tagProcessing(),
                            tagRegistrationMapper,
                            nodeExecutors.timingNode(),
                            nodeExecutors.tagProcessor());

            AntennaManager antennaManager = null;
            if (!installations.isEmpty()) {
                antennaManager =
                        new AntennaManager(
                                installations,
                                executors
                                        .createAntennaControlExecutor(),
                                executors.antennaScheduler(),
                                ANTENNA_CONTROL_TIMEOUT);
            }

            Conductor conductor =
                    new Conductor(
                            antennaManager);

            ConfigurationControl configurationControl =
                    createConfigurationControl(
                            applicationConfiguration);

            PresentationGateway presentationGateway =
                    new PresentationGateway(
                            buildIdentity,
                            timingNode,
                            configurationControl);

            /*
             * 4. Wire cross-component relationships explicitly.
             */
            if (antennaManager != null) {
                timingNode.statusChangedEvent()
                        .subscribe(
                                conductor
                                        ::onTimingNodeStatusChanged);

                for (AntennaInstallation installation
                        : installations) {
                    antennaManager
                            .tagObservedEvent(
                                    installation
                                            .antennaId())
                            .subscribe(
                                    timingNode
                                            .tagProcessor()
                                            ::onTagObserved);
                }
            }

            /*
             * 5. Return the already constructed and wired lifecycle owner.
             *    Application.start() performs the explicit start phase.
             */
            return new Application(
                    buildIdentity,
                    timingNode,
                    applicationConfiguration,
                    presentationGateway,
                    conductor,
                    antennaManager,
                    executors);
        } catch (RuntimeException ex) {
            executors.close();
            throw ex;
        }
    }

    private static ConfigurationControl createConfigurationControl(
            ApplicationConfiguration configuration) {
        Map<NodeId,
                DynamicConfiguration<TagProcessingPolicy>> tagProcessing =
                new LinkedHashMap<NodeId,
                        DynamicConfiguration<TagProcessingPolicy>>();

        for (NodeId nodeId
                : configuration.timingNodeIds()) {
            tagProcessing.put(
                    nodeId,
                    configuration
                            .timingNode(nodeId)
                            .tagProcessing());
        }

        return new ConfigurationControl(
                tagProcessing);
    }

    private static List<AntennaInstallation> copyInstallations(
            List<AntennaInstallation> installations) {
        List<AntennaInstallation> copy =
                new ArrayList<AntennaInstallation>(
                        installations.size());

        for (AntennaInstallation installation
                : installations) {
            if (installation == null) {
                throw new IllegalArgumentException(
                        "antennaInstallations must not contain null");
            }
            copy.add(installation);
        }

        return Collections.unmodifiableList(
                copy);
    }

    private static void requireCompositionInput(
            BuildIdentity buildIdentity,
            Config config,
            List<AntennaInstallation> antennaInstallations,
            TagRegistrationMapper tagRegistrationMapper) {
        if (buildIdentity == null) {
            throw new IllegalArgumentException(
                    "buildIdentity must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException(
                    "config must not be null");
        }
        if (config.timingDataPath() == null) {
            throw new IllegalArgumentException(
                    "TimingData storage path must be configured before composition");
        }
        if (antennaInstallations == null) {
            throw new IllegalArgumentException(
                    "antennaInstallations must not be null");
        }
        if (tagRegistrationMapper == null) {
            throw new IllegalArgumentException(
                    "tagRegistrationMapper must not be null");
        }
    }

    private static HttpEndpoint startHttp(
            Config config,
            Application application)
            throws IOException {
        Api api =
                config.presentation().api();
        Api.Http endpoint =
                api == null
                        ? null
                        : api.http();
        if (endpoint == null) {
            return null;
        }

        HttpEndpoint server =
                new HttpEndpoint(
                        endpoint.bindAddress(),
                        endpoint.port(),
                        application
                                .presentationGateway());
        server.start();
        return server;
    }

    private static WebSocketEndpoint startWebSocket(
            Config config,
            Application application)
            throws IOException {
        Api api =
                config.presentation().api();
        Api.WebSocket endpoint =
                api == null
                        ? null
                        : api.webSocket();
        if (endpoint == null) {
            return null;
        }

        WebSocketEndpoint server =
                new WebSocketEndpoint(
                        endpoint.bindAddress(),
                        endpoint.port(),
                        application
                                .presentationGateway());
        server.start();
        return server;
    }

    private static RemoteShellServer startRemoteShell(
            Config config,
            Application application)
            throws IOException {
        Presentation.RemoteShell endpoint =
                config.presentation()
                        .remoteShell();
        if (endpoint == null) {
            return null;
        }

        RemoteShellServer server =
                new RemoteShellServer(
                        endpoint.bindAddress(),
                        endpoint.port(),
                        application
                                .presentationGateway(),
                        application::close);
        server.start();
        return server;
    }

    private static void startLocalConsole(
            Application application) {
        LocalConsole console =
                new LocalConsole(
                        application
                                .presentationGateway(),
                        application::close,
                        new InputStreamReader(
                                System.in),
                        new OutputStreamWriter(
                                System.out));

        Thread consoleThread =
                new Thread(
                        console,
                        "tp-prl-console");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static void removeShutdownHook(
            Runtime runtime,
            Thread shutdownHook) {
        try {
            runtime.removeShutdownHook(
                    shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
