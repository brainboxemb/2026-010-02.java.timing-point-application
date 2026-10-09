package io.github.brainboxemb.eventtiming.timingpoint.app;

import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;
import io.github.brainboxemb.eventtiming.timingpoint.infra.EmbeddedBuildIdentityLoader;
import java.io.PrintStream;

/** Executable entry point; process startup belongs to ApplicationLauncher. */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
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

    static String startupIdentityLine(BuildIdentity identity) {
        return ApplicationLauncher.startupIdentityLine(identity);
    }
}
