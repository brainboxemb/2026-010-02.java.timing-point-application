package oss.relay.timing.timingpoint.runtime.config;

/** Effective configuration for the IF-03 API presentation interface. */
public final class Api {
    private final Http http;
    private final WebSocket webSocket;

    public Api(Http http, WebSocket webSocket) {
        if (http == null && webSocket == null) {
            throw new IllegalArgumentException(
                    "api must configure at least one transport");
        }
        this.http = http;
        this.webSocket = webSocket;
    }

    public Http http() {
        return http;
    }

    public WebSocket webSocket() {
        return webSocket;
    }

    public static final class Http extends Endpoint {
        public Http(String bindAddress, int port) {
            super(bindAddress, port, "api.http");
        }
    }

    public static final class WebSocket extends Endpoint {
        public WebSocket(String bindAddress, int port) {
            super(bindAddress, port, "api.webSocket");
        }
    }

    public abstract static class Endpoint {
        private final String bindAddress;
        private final int port;

        private Endpoint(String bindAddress, int port, String field) {
            if (bindAddress == null || bindAddress.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        field + " bindAddress must not be blank");
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException(
                        field + " port must be between 1 and 65535");
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
