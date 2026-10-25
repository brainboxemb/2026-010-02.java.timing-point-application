package oss.relay.eventtiming.systemtest.framework;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal HTTP/JSON client used by separate-process system tests. */
public final class HttpTestClient {
    private static final String LOOPBACK = "127.0.0.1";

    private final int port;

    public HttpTestClient(int port) {
        this.port = port;
    }

    public Response get(String path) throws IOException {
        return request("GET", path, null);
    }

    public Response post(String path, String json) throws IOException {
        return request("POST", path, json);
    }

    public Response put(String path, String json) throws IOException {
        return request("PUT", path, json);
    }

    public Response request(String method, String path, String json)
            throws IOException {
        HttpURLConnection connection =
                (HttpURLConnection) new URL(
                        "http://" + LOOPBACK + ":" + port + path)
                        .openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(1000);
        connection.setReadTimeout(3000);
        connection.setRequestProperty("Accept", "application/json");

        if (json != null) {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8");
            connection.setFixedLengthStreamingMode(payload.length);
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
            } finally {
                output.close();
            }
        }

        try {
            int status = connection.getResponseCode();
            InputStream input = status >= 400
                    ? connection.getErrorStream()
                    : connection.getInputStream();
            return new Response(
                    status,
                    input == null ? "" : readFully(input));
        } finally {
            connection.disconnect();
        }
    }

    private static String readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    /** Immutable HTTP response exposed to a verification case. */
    public static final class Response {
        private final int status;
        private final String body;

        private Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }
    }
}
