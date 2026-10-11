package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.EmbeddedBuildIdentityLoader;
import java.io.PrintStream;

/** Executable entry point; process startup belongs to ApplicationLauncher. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        /*
         * Maven exec:java owns the bootstrap thread and gives it a generated
         * <mainClass>.main() name. Hand long-lived application execution to an
         * application-owned non-daemon thread so launch tooling can terminate
         * its bootstrap thread and runtime diagnostics show tp-main.
         */
        Thread runtimeThread = runtimeThread(
                args,
                EmbeddedBuildIdentityLoader.load(),
                System.out,
                System.err);
        runtimeThread.start();
    }

    static Thread runtimeThread(
            String[] args,
            BuildIdentity identity,
            PrintStream out,
            PrintStream err) {
        Thread thread = new Thread(
                () -> {
                    ApplicationLauncher.nameRuntimeThread();
                    int exitCode = run(
                            args,
                            identity,
                            out,
                            err);
                    if (exitCode != 0) {
                        System.exit(exitCode);
                    }
                },
                "tp-main");
        thread.setDaemon(false);
        return thread;
    }

    static int run(String[] args, BuildIdentity identity, PrintStream out, PrintStream err) {
        return ApplicationLauncher.run(args, identity, out, err);
    }

    static String startupIdentityLine(BuildIdentity identity) {
        return ApplicationLauncher.startupIdentityLine(identity);
    }
}
