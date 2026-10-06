package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.shell;

import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.infra.logging.LoggingLevelControl;
import io.github.brainboxemb.eventtiming.timingpoint.presentation.common.terminal.TerminalSession;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small line-oriented TCP remote terminal for development and service use.
 *
 * <p>This is intentionally not an SSH or Telnet protocol implementation. One client session is
 * served at a time; after disconnect the listener accepts the next connection.</p>
 */
public final class RemoteShellServer implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(RemoteShellServer.class);
    private static final String READY_MESSAGE =
            "Remote terminal ready. Type 'help' for commands.";

    private final String bindAddress;
    private final int port;
    private final TerminalSession session;

    private volatile boolean closed;
    private volatile ServerSocket serverSocket;
    private volatile Socket activeClient;
    private Thread acceptThread;

    public RemoteShellServer(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway,
            Runnable shutdown) {
        this(
                bindAddress,
                port,
                presentationGateway,
                null,
                shutdown);
    }

    public RemoteShellServer(
            String bindAddress,
            int port,
            PresentationGateway presentationGateway,
            LoggingLevelControl loggingLevelControl,
            Runnable shutdown) {
        if (bindAddress == null || bindAddress.trim().isEmpty()) {
            throw new IllegalArgumentException("bindAddress must not be blank");
        }
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port must be between 0 and 65535");
        }
        this.bindAddress = bindAddress.trim();
        this.port = port;
        this.session = new TerminalSession(
                presentationGateway,
                loggingLevelControl,
                shutdown);
    }

    public synchronized void start() throws IOException {
        if (closed) {
            throw new IllegalStateException("Remote shell server is closed");
        }
        if (serverSocket != null) {
            throw new IllegalStateException("Remote shell server is already started");
        }

        InetAddress address = InetAddress.getByName(bindAddress);
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(address, port), 1);
        serverSocket = socket;

        Thread thread = new Thread(this::acceptLoop, "tp-prl-remote-shell");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();

        LOG.info("Remote terminal listening on {}:{}", bindAddress, socket.getLocalPort());
    }

    public int boundPort() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("Remote shell server is not started");
        }
        return socket.getLocalPort();
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                activeClient = client;
                client.setTcpNoDelay(true);
                session.run(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8),
                        new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8),
                        READY_MESSAGE);
            } catch (SocketException ex) {
                if (!closed) {
                    LOG.warn("Remote terminal socket failed", ex);
                }
            } catch (IOException ex) {
                if (!closed) {
                    LOG.warn("Remote terminal session failed", ex);
                }
            } finally {
                activeClient = null;
                closeSocket(client);
            }
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeSocket(activeClient);
        activeClient = null;

        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ex) {
                LOG.debug("Remote terminal listener was already closed", ex);
            }
        }

        Thread thread = acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Session cleanup is best-effort; listener/application shutdown remains authoritative.
        }
    }
}
