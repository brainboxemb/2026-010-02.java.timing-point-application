package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNode;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessor;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagRegistrationMapper;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.DefaultTimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timingdata.TimingDataPersistence;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.Antenna;
import io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.AntennaManager;
import io.github.brainboxemb.eventtiming.timingpoint.io.storage.FileAppendOnlyRecordStore;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.SystemMonotonicClock;
import io.github.brainboxemb.eventtiming.timingpoint.platform.execution.SerialScheduledExecutor;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsole;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServer;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Api;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Config;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the concrete SI-01 runtime composition.
 *
 * <p>I/O, Platform and infrastructure types keep their own responsibilities.
 * This class only decides which current implementations form the running
 * application.</p>
 */
public final class Composition {
    /*
     * First-executable internal bounds. They deliberately are not YAML/config
     * fields yet; IF-11 remains authority for future deployment configuration.
     */
    private static final int SHARED_IO_WORKERS = 2;
    private static final int SHARED_IO_QUEUE_CAPACITY = 16;
    private static final int ANTENNA_CONTROL_QUEUE_CAPACITY = 8;
    private static final Duration ANTENNA_CONTROL_TIMEOUT =
            Duration.ofSeconds(2);

    private Composition() {
    }

    /**
     * Package-private composition input until IF-11 owns production antenna
     * configuration. Tests and future config adapters use the same runtime path.
     */
    static final class AntennaProcessing {
        private final List<Antenna> antennas;
        private final TagRegistrationMapper mapper;
        private final TagProcessingPolicy policy;

        AntennaProcessing(
                List<Antenna> antennas,
                TagRegistrationMapper mapper,
                TagProcessingPolicy policy) {
            if (antennas == null || antennas.isEmpty()) {
                throw new IllegalArgumentException(
                        "antennas must contain at least one antenna");
            }
            if (mapper == null) {
                throw new IllegalArgumentException("mapper must not be null");
            }
            if (policy == null) {
                throw new IllegalArgumentException("policy must not be null");
            }
            List<Antenna> copy = new ArrayList<>(antennas.size());
            for (Antenna antenna : antennas) {
                if (antenna == null) {
                    throw new IllegalArgumentException(
                            "antennas must not contain null");
                }
                copy.add(antenna);
            }
            this.antennas = Collections.unmodifiableList(copy);
            this.mapper = mapper;
            this.policy = policy;
        }
    }

    public static void run(BuildIdentity buildIdentity, Config config) throws IOException {
        Application application = create(buildIdentity, config);

        Runtime runtime = Runtime.getRuntime();
        Thread shutdownHook = new Thread(application::close, "tp-run-shutdown");
        runtime.addShutdownHook(shutdownHook);

        HttpEndpoint http = null;
        WebSocketEndpoint webSocket = null;
        RemoteShellServer remoteShell = null;
        try {
            application.start();
            http = startHttp(config, application);
            webSocket = startWebSocket(config, application);
            remoteShell = startRemoteShell(config, application);
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
            removeShutdownHook(runtime, shutdownHook);
        }

        System.out.println(application.smokeOutput());
    }

    static Application create(BuildIdentity buildIdentity, Config config) {
        return create(buildIdentity, config, null);
    }

    static Application create(
            BuildIdentity buildIdentity,
            Config config,
            AntennaProcessing antennaProcessing) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        if (config.timingDataPath() == null) {
            throw new IllegalArgumentException(
                    "TimingData storage path must be configured before composition");
        }

        DefaultTimingDataFactory timingDataFactory = new DefaultTimingDataFactory();
        TimingDataPersistence timingDataPersistence =
                new DefaultTimingDataPersistence(
                        new FileAppendOnlyRecordStore(config.timingDataPath()),
                        config.timingNodeId(),
                        new DefaultTimingDataCodec());

        TimingNode timingNode = new TimingNode(
                config.timingNodeId(),
                timingDataPersistence,
                timingDataFactory,
                () -> new TimingTimestamp(Instant.now()));

        if (antennaProcessing == null) {
            return new Application(buildIdentity, timingNode);
        }

        ThreadPoolExecutor sharedIoExecutor = createSharedIoExecutor();
        try {
            TagProcessor tagProcessor = new TagProcessor(
                    timingNode,
                    antennaProcessing.mapper,
                    antennaProcessing.policy,
                    SystemMonotonicClock.INSTANCE,
                    new TagProcessingMetrics(),
                    new SerialScheduledExecutor(
                            "tp-tag-processor-" + config.timingNodeId().value()));
            AntennaManager antennaManager = new AntennaManager(
                    antennaProcessing.antennas,
                    sharedIoExecutor,
                    ANTENNA_CONTROL_QUEUE_CAPACITY,
                    ANTENNA_CONTROL_TIMEOUT);
            AntennaRuntime antennaRuntime = new AntennaRuntime(
                    antennaManager,
                    tagProcessor,
                    sharedIoExecutor);
            return new Application(
                    buildIdentity,
                    timingNode,
                    antennaRuntime);
        } catch (RuntimeException ex) {
            sharedIoExecutor.shutdownNow();
            throw ex;
        }
    }

    private static ThreadPoolExecutor createSharedIoExecutor() {
        AtomicInteger workerNumber = new AtomicInteger();
        return new ThreadPoolExecutor(
                SHARED_IO_WORKERS,
                SHARED_IO_WORKERS,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<Runnable>(SHARED_IO_QUEUE_CAPACITY),
                runnable -> new Thread(
                        runnable,
                        "tp-io-shared-" + workerNumber.incrementAndGet()),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static HttpEndpoint startHttp(Config config, Application application)
            throws IOException {
        Api api = config.presentation().api();
        Api.Http endpoint = api == null ? null : api.http();
        if (endpoint == null) {
            return null;
        }

        HttpEndpoint server = new HttpEndpoint(
                endpoint.bindAddress(),
                endpoint.port(),
                application.presentationGateway());
        server.start();
        return server;
    }

    private static WebSocketEndpoint startWebSocket(Config config, Application application)
            throws IOException {
        Api api = config.presentation().api();
        Api.WebSocket endpoint = api == null ? null : api.webSocket();
        if (endpoint == null) {
            return null;
        }

        WebSocketEndpoint server = new WebSocketEndpoint(
                endpoint.bindAddress(),
                endpoint.port(),
                application.presentationGateway());
        server.start();
        return server;
    }

    private static RemoteShellServer startRemoteShell(Config config, Application application)
            throws IOException {
        Presentation.RemoteShell endpoint = config.presentation().remoteShell();
        if (endpoint == null) {
            return null;
        }

        RemoteShellServer server = new RemoteShellServer(
                endpoint.bindAddress(),
                endpoint.port(),
                application.presentationGateway(),
                application::close);
        server.start();
        return server;
    }

    private static void startLocalConsole(Application application) {
        LocalConsole console = new LocalConsole(
                application.presentationGateway(),
                application::close,
                new InputStreamReader(System.in),
                new OutputStreamWriter(System.out));
        Thread consoleThread = new Thread(console, "tp-prl-console");
        consoleThread.setDaemon(true);
        consoleThread.start();
    }

    private static void removeShutdownHook(Runtime runtime, Thread shutdownHook) {
        try {
            runtime.removeShutdownHook(shutdownHook);
        } catch (IllegalStateException ignored) {
            // JVM shutdown is already in progress.
        }
    }
}
