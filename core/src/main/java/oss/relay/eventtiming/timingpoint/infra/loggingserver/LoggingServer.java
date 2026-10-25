package oss.relay.eventtiming.timingpoint.infra.loggingserver;

import oss.relay.eventtiming.timingpoint.infra.logging.Logging;
import oss.relay.eventtiming.timingpoint.infra.logging.LoggingLevel;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Optional best-effort engineering log listener.
 *
 * <p>The client initiates the connection. Log publishers only offer records to a bounded queue,
 * so a slow/disconnected client cannot block normal application execution.</p>
 */
public final class LoggingServer implements AutoCloseable {
    private static final int QUEUE_CAPACITY = 512;

    private final String bindAddress;
    private final int port;
    private final Logging logging;
    private final Logger rootLogger = Logger.getLogger("");
    private final LiveLogHandler liveHandler;
    private final MessageFormatter messageFormatter = new MessageFormatter();
    private final BlockingQueue<String> outbound =
            new ArrayBlockingQueue<String>(QUEUE_CAPACITY);

    private volatile boolean closed;
    private volatile ServerSocket serverSocket;
    private volatile Socket activeClient;
    private Thread acceptThread;

    public LoggingServer(LoggingServerConfig config, Logging logging) {
        if (config == null) {
            throw new IllegalArgumentException("logging server config must not be null");
        }
        if (logging == null) {
            throw new IllegalArgumentException("logging must not be null");
        }
        this.bindAddress = config.bindAddress();
        this.port = config.port();
        this.logging = logging;
        this.liveHandler = new LiveLogHandler(this);
    }

    public synchronized void start() throws IOException {
        if (serverSocket != null) {
            throw new IllegalStateException("logging server is already started");
        }
        ServerSocket socket = new ServerSocket();
        socket.bind(new InetSocketAddress(InetAddress.getByName(bindAddress), port), 1);
        serverSocket = socket;
        rootLogger.addHandler(liveHandler);

        Thread thread = new Thread(this::acceptLoop, "tp-inf-live-log");
        thread.setDaemon(true);
        acceptThread = thread;
        thread.start();
    }

    public int boundPort() {
        ServerSocket socket = serverSocket;
        if (socket == null) {
            throw new IllegalStateException("logging server is not started");
        }
        return socket.getLocalPort();
    }

    void publish(LogRecord record) {
        if (activeClient == null || record == null) {
            return;
        }
        offer(logLine(record));
    }

    private void offer(String line) {
        if (!outbound.offer(line)) {
            outbound.poll();
            outbound.offer(line);
        }
    }

    private void acceptLoop() {
        while (!closed) {
            Socket client = null;
            try {
                client = serverSocket.accept();
                client.setTcpNoDelay(true);
                activeClient = client;
                outbound.clear();
                runSession(client);
            } catch (SocketException ex) {
                if (!closed) {
                    // Connection failure is deliberately isolated from normal logging.
                }
            } catch (IOException ex) {
                if (!closed) {
                    // Best-effort diagnostics: keep the listener alive when possible.
                }
            } finally {
                if (activeClient == client) {
                    activeClient = null;
                }
                closeSocket(client);
                outbound.clear();
            }
        }
    }

    private void runSession(Socket client) throws IOException {
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(client.getOutputStream(), StandardCharsets.UTF_8));

        Thread writerThread = new Thread(
                () -> writeLoop(client, writer),
                "tp-inf-live-log-writer");
        writerThread.setDaemon(true);
        writerThread.start();

        offer(levelLine(logging.level()));

        try {
            String command;
            while (!closed && activeClient == client && (command = reader.readLine()) != null) {
                handleCommand(command);
            }
        } finally {
            writerThread.interrupt();
        }
    }

    private void handleCommand(String command) {
        String value = command == null ? "" : command.trim();
        if ("GET_LEVEL".equalsIgnoreCase(value)) {
            offer(levelLine(logging.level()));
        } else if (value.regionMatches(true, 0, "SET_LEVEL ", 0, 10)) {
            String requested = value.substring(10).trim().toUpperCase();
            try {
                LoggingLevel level = LoggingLevel.valueOf(requested);
                logging.setLevel(level);
                offer(levelLine(level));
            } catch (IllegalArgumentException ex) {
                offer(errorLine("Unsupported level: " + requested));
            }
        } else {
            offer(errorLine("Unsupported command"));
        }
    }

    private void writeLoop(Socket client, BufferedWriter writer) {
        try {
            while (!closed && activeClient == client && !Thread.currentThread().isInterrupted()) {
                String line = outbound.poll(250, TimeUnit.MILLISECONDS);
                if (line != null) {
                    writer.write(line);
                    writer.newLine();
                    writer.flush();
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException ex) {
            closeSocket(client);
        }
    }

    private String logLine(LogRecord record) {
        String thrown = record.getThrown() == null ? null : record.getThrown().toString();
        return "{\"type\":\"log\",\"occurredAt\":\""
                + escape(Instant.ofEpochMilli(record.getMillis()).toString())
                + "\",\"level\":\""
                + semanticLevel(record.getLevel())
                + "\",\"logger\":\""
                + escape(record.getLoggerName() == null ? "" : record.getLoggerName())
                + "\",\"source\":\""
                + escape(source(record))
                + "\",\"message\":\""
                + escape(messageFormatter.format(record))
                + "\",\"formatted\":\""
                + escape(logging.format(record))
                + "\""
                + (thrown == null ? "" : ",\"thrown\":\"" + escape(thrown) + "\"")
                + "}";
    }

    private static String semanticLevel(java.util.logging.Level level) {
        int value = level.intValue();
        if (value >= java.util.logging.Level.SEVERE.intValue()) {
            return "ERROR";
        }
        if (value >= java.util.logging.Level.WARNING.intValue()) {
            return "WARN";
        }
        if (value >= java.util.logging.Level.INFO.intValue()) {
            return "INFO";
        }
        if (value >= java.util.logging.Level.FINE.intValue()) {
            return "DEBUG";
        }
        return "TRACE";
    }

    private static String source(LogRecord record) {
        String className = record.getSourceClassName();
        String methodName = record.getSourceMethodName();
        if (className == null || className.trim().isEmpty()) {
            className = record.getLoggerName();
        }
        if (className == null || className.trim().isEmpty()) {
            className = "unknown";
        }
        if (methodName == null || methodName.trim().isEmpty()) {
            return className;
        }
        return className + "." + methodName;
    }

    private static String levelLine(LoggingLevel level) {
        return "{\"type\":\"level\",\"level\":\"" + level.name() + "\"}";
    }

    private static String errorLine(String message) {
        return "{\"type\":\"error\",\"message\":\"" + escape(message) + "\"}";
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
            }
        }
        return escaped.toString();
    }

    @Override
    public synchronized void close() {
        closed = true;
        rootLogger.removeHandler(liveHandler);
        closeSocket(activeClient);
        activeClient = null;

        ServerSocket socket = serverSocket;
        if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Best-effort diagnostics cleanup.
            }
            serverSocket = null;
        }

        Thread thread = acceptThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        acceptThread = null;
        outbound.clear();
    }

    private static void closeSocket(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Best-effort diagnostics cleanup.
        }
    }

    private static final class MessageFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            return formatMessage(record);
        }
    }
}
