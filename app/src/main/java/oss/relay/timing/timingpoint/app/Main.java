package oss.relay.timing.timingpoint.app;

import oss.relay.timing.timingpoint.infra.BuildIdentity;
import oss.relay.timing.timingpoint.infra.EmbeddedBuildIdentityLoader;
import java.io.PrintStream;

/** Executable entry point; process startup belongs to ApplicationLauncher. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        Thread.currentThread().setName("tp-main");

        int exitCode = run(
                args,
                EmbeddedBuildIdentityLoader.load(),
                System.out,
                System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] args, BuildIdentity identity, PrintStream out, PrintStream err) {
        return ApplicationLauncher.run(args, identity, out, err);
    }

}
