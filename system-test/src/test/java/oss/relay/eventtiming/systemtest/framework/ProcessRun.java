package oss.relay.eventtiming.systemtest.framework;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** One external application process plus captured console output. */
public final class ProcessRun {
    private final Process process;
    private final OutputCollector collector;
    private final Thread collectorThread;

    private ProcessRun(
            Process process,
            OutputCollector collector,
            Thread collectorThread) {
        this.process = process;
        this.collector = collector;
        this.collectorThread = collectorThread;
    }

    public static ProcessRun startJar(
            File appJar,
            File configFile,
            File workingDirectory,
            String collectorName)
            throws IOException {
        Process process = new ProcessBuilder(
                javaExecutable(),
                "-jar",
                appJar.getAbsolutePath(),
                configFile.getAbsolutePath())
                .directory(workingDirectory)
                .redirectErrorStream(true)
                .start();

        OutputCollector collector = new OutputCollector(process.getInputStream());
        Thread collectorThread = new Thread(collector, collectorName);
        collectorThread.setDaemon(true);
        collectorThread.start();
        return new ProcessRun(process, collector, collectorThread);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    public String output() {
        return collector.snapshot();
    }

    public void awaitSuccessfulExit(long timeoutMillis) throws Exception {
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw new AssertionError(
                    "Application did not exit in time. Output:\n" + output());
        }
        collectorThread.join(1000L);
        if (process.exitValue() != 0) {
            throw new AssertionError(
                    "Application exited with "
                            + process.exitValue()
                            + ". Output:\n"
                            + output());
        }
    }

    public void cleanup() throws InterruptedException {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(2000L, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(2000L, TimeUnit.MILLISECONDS);
            }
        }
        collectorThread.join(1000L);
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name", "")
                .toLowerCase()
                .contains("win") ? "java.exe" : "java";
        return new File(
                new File(System.getProperty("java.home"), "bin"),
                executable)
                .getAbsolutePath();
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream input;
        private final StringBuilder text = new StringBuilder();

        private OutputCollector(InputStream input) {
            this.input = input;
        }

        @Override
        public void run() {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                input,
                                StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    synchronized (text) {
                        text.append(line)
                                .append(System.lineSeparator());
                    }
                }
            } catch (IOException ex) {
                synchronized (text) {
                    text.append("[output collector failed: ")
                            .append(ex.getMessage())
                            .append(']')
                            .append(System.lineSeparator());
                }
            }
        }

        private String snapshot() {
            synchronized (text) {
                return text.toString();
            }
        }
    }
}
