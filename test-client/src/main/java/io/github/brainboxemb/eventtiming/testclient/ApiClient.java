package io.github.brainboxemb.eventtiming.testclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Independent IF-03 API HTTP client.
 *
 * <p>This project deliberately has no dependency on SI-01 implementation classes.</p>
 */
public final class ApiClient {
    private static final int TIMING_DATA_VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final URI endpoint;
    private final HttpClient httpClient;

    public ApiClient(URI endpoint) {
        if (endpoint == null || endpoint.getScheme() == null || endpoint.getHost() == null) {
            throw new IllegalArgumentException("endpoint must be an absolute HTTP URI");
        }
        if (!"http".equalsIgnoreCase(endpoint.getScheme())
                && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("endpoint scheme must be http or https");
        }
        this.endpoint = endpoint;
        this.httpClient = ClientHttpTransport.shared();
    }

    public VersionResult getVersion() throws IOException, InterruptedException {
        String rawJson = request("GET", "/api/v1/version", null);
        JsonNode root = JSON.readTree(rawJson);
        return new VersionResult(readBuild(root), rawJson);
    }

    public StatusResult getStatus() throws IOException, InterruptedException {
        return parseStatus(request("GET", "/api/v1/status", null));
    }

    public CapabilitiesResult getCapabilities() throws IOException, InterruptedException {
        return parseCapabilities(request("GET", "/api/v1/capabilities", null));
    }

    public OperationResult open(String nodeId, int locationId)
            throws IOException, InterruptedException {
        ObjectNode body = JSON.createObjectNode();
        body.put("locationId", locationId);
        return parseOperation(request(
                "POST",
                nodePath(nodeId, "/open"),
                JSON.writeValueAsString(body)));
    }

    public OperationResult close(String nodeId) throws IOException, InterruptedException {
        return parseOperation(request("POST", nodePath(nodeId, "/close"), ""));
    }

    public AutoRegResult autoReg(String nodeId, String registrationId, String time)
            throws IOException, InterruptedException {
        if (registrationId == null || registrationId.trim().isEmpty()) {
            throw new IllegalArgumentException("registrationId must not be blank");
        }
        if (time == null || time.trim().isEmpty()) {
            throw new IllegalArgumentException("time must not be blank");
        }
        ObjectNode body = JSON.createObjectNode();
        body.put("id", registrationId);
        body.put("time", time);
        String rawJson = request(
                "POST",
                "/api/v1/dev/node/" + pathSegment(nodeId) + "/auto-reg",
                JSON.writeValueAsString(body));
        JsonNode root = JSON.readTree(rawJson);
        return new AutoRegResult(requiredLong(root, "seq"), rawJson);
    }

    public OperationResult simulateRegistration(
            String nodeId,
            String registrationId,
            String profile)
            throws IOException, InterruptedException {
        if (registrationId == null
                || registrationId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "registrationId must not be blank");
        }
        if (profile == null
                || profile.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "profile must not be blank");
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("regId", registrationId);
        body.put("profile", profile);

        return parseOperation(
                request(
                        "POST",
                        "/api/v1/dev/node/"
                                + pathSegment(nodeId)
                                + "/simulation/registration",
                        JSON.writeValueAsString(body)));
    }

    public CommitResult manualRegistration(
            String nodeId,
            String registrationId,
            String time,
            String timeSource)
            throws IOException, InterruptedException {
        if (registrationId == null
                || registrationId.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "registrationId must not be blank");
        }
        if (time == null || time.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "time must not be blank");
        }
        if (!"AUTO".equals(timeSource)
                && !"MAN".equals(timeSource)) {
            throw new IllegalArgumentException(
                    "timeSource must be AUTO or MAN");
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("regId", registrationId);
        body.put("time", time);
        body.put("timeSource", timeSource);

        String rawJson = request(
                "POST",
                nodePath(
                        nodeId,
                        "/registration/manual"),
                JSON.writeValueAsString(body));
        JsonNode root = JSON.readTree(rawJson);
        return new CommitResult(
                requiredLong(root, "seq"),
                rawJson);
    }

    public CommitResult revokeRegistration(
            String nodeId,
            TimingDataInfo original)
            throws IOException, InterruptedException {
        if (original == null) {
            throw new IllegalArgumentException(
                    "original registration must not be null");
        }
        if (!"AUTO_REG".equals(original.recordType())
                && !"MAN_REG".equals(original.recordType())) {
            throw new IllegalArgumentException(
                    "Only registration records can be revoked");
        }
        if (original.registrationId() == null
                || original.registrationId().trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "original registrationId must not be blank");
        }
        if (!original.codes().contains("ADD")) {
            throw new IllegalArgumentException(
                    "revoke source must be an ADD registration record");
        }

        ObjectNode body = JSON.createObjectNode();
        body.put("recordType", original.recordType());
        body.put("locationId", original.locationId());
        body.put("regId", original.registrationId());
        body.put("time", original.effectiveTime());

        if ("MAN_REG".equals(original.recordType())) {
            if (original.codes().contains("MAN")) {
                body.put("timeSource", "MAN");
            } else if (original.codes().contains("AUTO")) {
                body.put("timeSource", "AUTO");
            } else {
                throw new IllegalArgumentException(
                        "MAN_REG source must carry AUTO or MAN code");
            }
        }

        String rawJson = request(
                "POST",
                nodePath(
                        nodeId,
                        "/registration/revoke"),
                JSON.writeValueAsString(body));
        JsonNode root = JSON.readTree(rawJson);
        return new CommitResult(
                requiredLong(root, "seq"),
                rawJson);
    }

    public LogBookInfo getLogBookInfo(String nodeId)
            throws IOException, InterruptedException {
        String rawJson = request("GET", nodePath(nodeId, "/logbook"), null);
        JsonNode root = JSON.readTree(rawJson);
        return new LogBookInfo(
                requiredLong(root, "count"),
                optionalLong(root, "first"),
                optionalLong(root, "last"),
                rawJson);
    }

    public LogBookPage getLogBookFrom(String nodeId, long from, int limit)
            throws IOException, InterruptedException {
        if (from < 1L) {
            throw new IllegalArgumentException("from must be >= 1");
        }
        requireLimit(limit);
        return parseLogBookPage(request(
                "GET",
                nodePath(nodeId, "/logbook")
                        + "?from=" + from + "&limit=" + limit,
                null));
    }

    public LogBookPage getLogBookLast(String nodeId, int last)
            throws IOException, InterruptedException {
        requireLimit(last);
        return parseLogBookPage(request(
                "GET",
                nodePath(nodeId, "/logbook") + "?last=" + last,
                null));
    }

    static StatusResult parseStatus(String rawJson) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        List<TimingNodeInfo> nodes = new ArrayList<>();
        JsonNode nodeArray = required(root, "nodes");
        if (!nodeArray.isArray()) {
            throw new IllegalArgumentException("IF-03 field is not an array: nodes");
        }
        for (JsonNode node : nodeArray) {
            nodes.add(new TimingNodeInfo(
                    requiredText(node, "id"),
                    optionalInt(node, "locationId"),
                    requiredText(node, "state")));
        }

        List<ProblemInfo> problems = new ArrayList<>();
        JsonNode problemArray = required(root, "problems");
        if (!problemArray.isArray()) {
            throw new IllegalArgumentException("IF-03 field is not an array: problems");
        }
        for (JsonNode problem : problemArray) {
            problems.add(new ProblemInfo(
                    requiredText(problem, "code"),
                    requiredText(problem, "severity"),
                    optionalText(problem, "nodeId"),
                    requiredText(problem, "message")));
        }

        return new StatusResult(
                List.copyOf(nodes),
                List.copyOf(problems),
                rawJson);
    }

    static TimingDataInfo parseTimingData(JsonNode root) {
        int version = requiredInt(root, "v");
        if (version != TIMING_DATA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported IF-05 TimingData version: " + version);
        }
        return new TimingDataInfo(
                requiredText(root, "nodeId"),
                requiredLong(root, "seqNr"),
                requiredInt(root, "locId"),
                requiredText(root, "recType"),
                requiredText(root, "time"),
                optionalText(root, "regId"),
                requiredTextArray(root, "code"),
                requiredText(root, "recTime"),
                root.toString());
    }

    private static CapabilitiesResult parseCapabilities(String rawJson) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        JsonNode values = required(root, "capabilities");
        if (!values.isArray()) {
            throw new IllegalArgumentException("IF-03 field is not an array: capabilities");
        }
        List<CapabilityInfo> capabilities = new ArrayList<>();
        for (JsonNode value : values) {
            capabilities.add(new CapabilityInfo(
                    requiredText(value, "id"),
                    requiredBoolean(value, "supported"),
                    requiredBoolean(value, "enabled")));
        }
        return new CapabilitiesResult(List.copyOf(capabilities), rawJson);
    }

    private static OperationResult parseOperation(String rawJson) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        return new OperationResult(requiredText(root, "result"), rawJson);
    }

    private static LogBookPage parseLogBookPage(String rawJson) throws IOException {
        JsonNode root = JSON.readTree(rawJson);
        JsonNode values = required(root, "records");
        if (!values.isArray()) {
            throw new IllegalArgumentException("IF-03 field is not an array: records");
        }
        List<TimingDataInfo> records = new ArrayList<>();
        for (JsonNode value : values) {
            records.add(parseTimingData(value));
        }
        return new LogBookPage(
                requiredLong(root, "count"),
                optionalLong(root, "next"),
                List.copyOf(records),
                rawJson);
    }

    private String request(String method, String path, String body)
            throws IOException, InterruptedException {
        URI uri = endpoint.resolve(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Accept", "application/json");

        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        }

        HttpResponse<String> response =
                httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        }
        throw parseApiException(response.statusCode(), response.body(), uri);
    }

    private static ApiException parseApiException(int statusCode, String rawJson, URI uri) {
        try {
            JsonNode root = JSON.readTree(rawJson);
            JsonNode error = required(root, "error");
            return new ApiException(
                    statusCode,
                    requiredText(error, "code"),
                    requiredText(error, "message"),
                    rawJson);
        } catch (Exception ex) {
            return new ApiException(
                    statusCode,
                    "HTTP_" + statusCode,
                    "HTTP " + statusCode + " from " + uri,
                    rawJson);
        }
    }

    private static String nodePath(String nodeId, String suffix) {
        return "/api/v1/node/" + pathSegment(nodeId) + suffix;
    }

    private static String pathSegment(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void requireLimit(int value) {
        if (value < 1 || value > 1000) {
            throw new IllegalArgumentException("LogBook limit must be between 1 and 1000");
        }
    }

    private static BuildInfo readBuild(JsonNode root) {
        return new BuildInfo(
                requiredText(root, "application"),
                requiredText(root, "version"),
                requiredText(root, "revision"),
                requiredText(root, "sourceRef"),
                requiredText(root, "buildOrigin"),
                requiredBoolean(root, "dirty"),
                requiredText(root, "apiVersion"));
    }

    static JsonNode required(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing IF-03 field: " + field);
        }
        return value;
    }

    static String requiredText(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IF-03 field is not text: " + field);
        }
        return value.asText();
    }

    private static List<String> requiredTextArray(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isArray()) {
            throw new IllegalArgumentException("IF-03 field is not an array: " + field);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode item : value) {
            if (!item.isTextual()) {
                throw new IllegalArgumentException(
                        "IF-03 array contains non-text value: " + field);
            }
            result.add(item.asText());
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("IF-03 array is empty: " + field);
        }
        return List.copyOf(result);
    }

    private static boolean requiredBoolean(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isBoolean()) {
            throw new IllegalArgumentException("IF-03 field is not boolean: " + field);
        }
        return value.asBoolean();
    }

    private static int requiredInt(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("IF-03 field is not an integer: " + field);
        }
        return value.intValue();
    }

    private static long requiredLong(JsonNode root, String field) {
        JsonNode value = required(root, field);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("IF-03 field is not an integer: " + field);
        }
        return value.longValue();
    }

    private static Integer optionalInt(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("IF-03 field is not an integer: " + field);
        }
        return Integer.valueOf(value.intValue());
    }

    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("IF-03 field is not text: " + field);
        }
        return value.asText();
    }

    private static Long optionalLong(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("IF-03 field is not an integer: " + field);
        }
        return Long.valueOf(value.longValue());
    }

    public record BuildInfo(
            String application,
            String version,
            String revision,
            String sourceRef,
            String buildOrigin,
            boolean dirty,
            String apiVersion) {
    }

    public record VersionResult(BuildInfo build, String rawJson) {
    }

    public record TimingNodeInfo(String id, Integer locationId, String state) {
    }

    public record ProblemInfo(
            String code,
            String severity,
            String nodeId,
            String message) {
    }

    public record StatusResult(
            List<TimingNodeInfo> nodes,
            List<ProblemInfo> problems,
            String rawJson) {
    }

    public record CapabilityInfo(String id, boolean supported, boolean enabled) {
    }

    public record CapabilitiesResult(
            List<CapabilityInfo> capabilities,
            String rawJson) {
        public boolean enabled(String id) {
            for (CapabilityInfo capability : capabilities) {
                if (capability.id().equals(id)) {
                    return capability.supported() && capability.enabled();
                }
            }
            return false;
        }
    }

    public record OperationResult(String result, String rawJson) {
    }

    public record AutoRegResult(long seq, String rawJson) {
    }

    public record CommitResult(long seq, String rawJson) {
    }

    public record LogBookInfo(long count, Long first, Long last, String rawJson) {
    }

    public record TimingDataKey(String timingNodeId, long sequenceNumber) {
    }

    public record TimingDataInfo(
            String timingNodeId,
            long sequenceNumber,
            int locationId,
            String recordType,
            String effectiveTime,
            String registrationId,
            List<String> codes,
            String recordedAt,
            String rawJson) {
        public TimingDataKey key() {
            return new TimingDataKey(timingNodeId, sequenceNumber);
        }
    }

    public record LogBookPage(
            long count,
            Long next,
            List<TimingDataInfo> records,
            String rawJson) {
    }

    public static final class ApiException extends IOException {
        private final int statusCode;
        private final String code;
        private final String rawJson;

        private ApiException(
                int statusCode,
                String code,
                String message,
                String rawJson) {
            super(message);
            this.statusCode = statusCode;
            this.code = code;
            this.rawJson = rawJson;
        }

        public int statusCode() {
            return statusCode;
        }

        public String code() {
            return code;
        }

        public String rawJson() {
            return rawJson;
        }
    }
}
