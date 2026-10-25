package oss.relay.timing.timingpoint.runtime.config;

/** Effective presentation configuration used by runtime composition. */
public final class Presentation {
    private final RemoteShell remoteShell;
    private final Api api;

    public Presentation(RemoteShell remoteShell, Api api) {
        this.remoteShell = remoteShell;
        this.api = api;
    }

    public RemoteShell remoteShell() {
        return remoteShell;
    }

    public Api api() {
        return api;
    }

    public static final class RemoteShell {
        private final String bindAddress;
        private final int port;

        public RemoteShell(String bindAddress, int port) {
            if (bindAddress == null || bindAddress.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "remote shell bindAddress must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        "remote shell port must be between 1 and 65535");
            }
            this.bindAddress = bindAddress;
            this.port = port;
        }

        public String bindAddress() {
            return bindAddress;
        }

        public int port() {
            return port;
        }
    }
}
