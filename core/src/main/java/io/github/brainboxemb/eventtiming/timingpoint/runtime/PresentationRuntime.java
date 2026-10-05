package io.github.brainboxemb.eventtiming.timingpoint.runtime;

import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.HttpEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api.WebSocketEndpoint;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.console.LocalConsole;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell.RemoteShellServer;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Api;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.config.Presentation;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;

/**
 * Owns the concrete Presentation adapters of one composed TimingApplication.
 *
 * <p>Runtime composition decides which adapters exist from the validated
 * Presentation configuration. Main does not construct, order or stop individual
 * Presentation endpoints.</p>
 *
 * <p>This object owns adapter lifecycle only. Transport-independent application
 * behaviour remains behind {@link PresentationGateway}.</p>
 */
final class PresentationRuntime {

    private final HttpEndpoint http;
    private final WebSocketEndpoint webSocket;
    private final RemoteShellServer remoteShell;
    private final LocalConsole localConsole;

    private Thread consoleThread;
    private boolean active;

    PresentationRuntime(
            Presentation configuration,
            PresentationGateway gateway,
            Runnable shutdownRequest,
            Reader consoleInput,
            Writer consoleOutput) {
        if (configuration == null) {
            throw new IllegalArgumentException(
                    "configuration must not be null");
        }
        if (gateway == null) {
            throw new IllegalArgumentException(
                    "gateway must not be null");
        }
        if (shutdownRequest == null) {
            throw new IllegalArgumentException(
                    "shutdownRequest must not be null");
        }
        if ((consoleInput == null) != (consoleOutput == null)) {
            throw new IllegalArgumentException(
                    "consoleInput and consoleOutput must either both be supplied or both be null");
        }

        Api api =
                configuration.api();

        Api.Http httpConfig =
                api == null
                        ? null
                        : api.http();
        http =
                httpConfig == null
                        ? null
                        : new HttpEndpoint(
                                httpConfig.bindAddress(),
                                httpConfig.port(),
                                gateway);

        Api.WebSocket webSocketConfig =
                api == null
                        ? null
                        : api.webSocket();
        webSocket =
                webSocketConfig == null
                        ? null
                        : new WebSocketEndpoint(
                                webSocketConfig.bindAddress(),
                                webSocketConfig.port(),
                                gateway);

        Presentation.RemoteShell shellConfig =
                configuration.remoteShell();
        remoteShell =
                shellConfig == null
                        ? null
                        : new RemoteShellServer(
                                shellConfig.bindAddress(),
                                shellConfig.port(),
                                gateway,
                                shutdownRequest);

        localConsole =
                consoleInput == null
                        ? null
                        : new LocalConsole(
                                gateway,
                                shutdownRequest,
                                consoleInput,
                                consoleOutput);
    }

    /**
     * Activates configured Presentation adapters in a fixed visible order.
     *
     * <p>If one adapter fails to start, adapters already activated by this call
     * are immediately closed before the failure is propagated.</p>
     */
    synchronized void activate() {
        if (active) {
            throw new IllegalStateException(
                    "PresentationRuntime is already active");
        }

        try {
            if (http != null) {
                http.start();
            }
            if (webSocket != null) {
                webSocket.start();
            }
            if (remoteShell != null) {
                remoteShell.start();
            }

            if (localConsole != null) {
                consoleThread =
                        new Thread(
                                localConsole,
                                "tp-prl-console");
                consoleThread.setDaemon(true);
                consoleThread.start();
            }

            active = true;
        } catch (IOException ex) {
            cleanupPartialActivation();
            throw new IllegalStateException(
                    "Unable to activate Presentation",
                    ex);
        } catch (RuntimeException ex) {
            cleanupPartialActivation();
            throw ex;
        }
    }

    /**
     * Deactivates Presentation before Application/Domain components disappear.
     */
    synchronized void deactivate() {
        Thread thread = consoleThread;
        consoleThread = null;
        if (thread != null) {
            thread.interrupt();
        }

        if (remoteShell != null) {
            remoteShell.close();
        }
        if (webSocket != null) {
            webSocket.close();
        }
        if (http != null) {
            http.close();
        }

        active = false;
    }

    private void cleanupPartialActivation() {
        try {
            deactivate();
        } catch (RuntimeException ignored) {
            // Preserve the startup failure that triggered rollback.
        }
    }
}
