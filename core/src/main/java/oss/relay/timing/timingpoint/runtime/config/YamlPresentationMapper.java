package oss.relay.timing.timingpoint.runtime.config;

import java.util.Map;

/** Maps the presentation section of the application YAML schema. */
final class YamlPresentationMapper {
    private static final String PRESENTATION = "presentation";
    private static final String REMOTE_SHELL = "remoteShell";
    private static final String API = "api";
    private static final String HTTP = "http";
    private static final String WEB_SOCKET = "webSocket";
    private static final String BIND_ADDRESS = "bindAddress";
    private static final String PORT = "port";

    private YamlPresentationMapper() {
    }

    /** Maps the optional presentation section to its effective runtime value. */
    static Presentation map(Object rawPresentation) {
        if (rawPresentation == null) {
            return new Presentation(null, null);
        }

        Map<?, ?> presentation =
                YamlValues.requireMapping(
                        rawPresentation,
                        PRESENTATION);
        YamlValues.rejectUnknownFields(
                presentation,
                PRESENTATION,
                REMOTE_SHELL,
                API);

        return new Presentation(
                mapRemoteShell(
                        presentation.get(
                                REMOTE_SHELL)),
                mapApi(
                        presentation.get(
                                API)));
    }

    /** Maps the optional remote-shell endpoint. */
    private static Presentation.RemoteShell mapRemoteShell(Object raw) {
        if (raw == null) {
            return null;
        }

        String field =
                PRESENTATION
                        + "."
                        + REMOTE_SHELL;
        Map<?, ?> values =
                YamlValues.endpointMapping(
                        raw,
                        field);
        return new Presentation.RemoteShell(
                YamlValues.requireString(
                        values.get(
                                BIND_ADDRESS),
                        field
                                + "."
                                + BIND_ADDRESS),
                YamlValues.requirePort(
                        values.get(
                                PORT),
                        field
                                + "."
                                + PORT));
    }

    /** Maps the optional IF-03 API endpoint group. */
    private static Api mapApi(Object raw) {
        if (raw == null) {
            return null;
        }

        String field =
                PRESENTATION
                        + "."
                        + API;
        Map<?, ?> values =
                YamlValues.requireMapping(
                        raw,
                        field);
        YamlValues.rejectUnknownFields(
                values,
                field,
                HTTP,
                WEB_SOCKET);
        return new Api(
                mapApiHttp(
                        values.get(
                                HTTP)),
                mapApiWebSocket(
                        values.get(
                                WEB_SOCKET)));
    }

    /** Maps the optional HTTP API endpoint. */
    private static Api.Http mapApiHttp(Object raw) {
        if (raw == null) {
            return null;
        }

        String field =
                PRESENTATION
                        + "."
                        + API
                        + "."
                        + HTTP;
        Map<?, ?> values =
                YamlValues.endpointMapping(
                        raw,
                        field);
        return new Api.Http(
                YamlValues.requireString(
                        values.get(
                                BIND_ADDRESS),
                        field
                                + "."
                                + BIND_ADDRESS),
                YamlValues.requirePort(
                        values.get(
                                PORT),
                        field
                                + "."
                                + PORT));
    }

    /** Maps the optional WebSocket API endpoint. */
    private static Api.WebSocket mapApiWebSocket(Object raw) {
        if (raw == null) {
            return null;
        }

        String field =
                PRESENTATION
                        + "."
                        + API
                        + "."
                        + WEB_SOCKET;
        Map<?, ?> values =
                YamlValues.endpointMapping(
                        raw,
                        field);
        return new Api.WebSocket(
                YamlValues.requireString(
                        values.get(
                                BIND_ADDRESS),
                        field
                                + "."
                                + BIND_ADDRESS),
                YamlValues.requirePort(
                        values.get(
                                PORT),
                        field
                                + "."
                                + PORT));
    }
}
