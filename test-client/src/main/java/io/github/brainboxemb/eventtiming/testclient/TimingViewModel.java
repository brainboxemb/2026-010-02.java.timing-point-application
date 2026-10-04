package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Small presentation model for the Step-4 Timing view.
 *
 * <p>It owns client-side view/cache state only. SI-01 remains authoritative for
 * TimingNode state and committed LogBook content.</p>
 */
public final class TimingViewModel {
    public enum ViewState {
        DISCONNECTED,
        SYNCING,
        STALE,
        LIVE
    }

    public record Controls(
            boolean open,
            boolean close,
            boolean autoReg) {
    }

    /** One user-facing registration projected from immutable committed TimingData. */
    public record InterpretedRegistration(
            String registrationId,
            String displayTime,
            String source,
            boolean deleted,
            long firstSequence,
            Long revokeSequence) {
        public String state() {
            return deleted ? "DELETED" : "";
        }
    }

    private static final DateTimeFormatter CANONICAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private ViewState viewState = ViewState.DISCONNECTED;
    private List<ApiClient.TimingNodeInfo> nodes = List.of();
    private List<ApiClient.ProblemInfo> problems = List.of();
    private String selectedNodeId;
    private boolean autoRegEnabled;
    private long logBookCount;
    private final Map<ApiClient.TimingDataKey, ApiClient.TimingDataInfo> records =
            new LinkedHashMap<>();

    public ViewState viewState() {
        return viewState;
    }

    public void viewState(ViewState value) {
        if (value == null) {
            throw new IllegalArgumentException("viewState must not be null");
        }
        viewState = value;
    }

    public List<ApiClient.TimingNodeInfo> nodes() {
        return nodes;
    }

    public void applyStatus(ApiClient.StatusResult status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        nodes = status.nodes();
        problems = status.problems();

        if (selectedNodeId != null && findNode(selectedNodeId) != null) {
            return;
        }
        selectNode(nodes.isEmpty() ? null : nodes.get(0).id());
    }

    public String selectedNodeId() {
        return selectedNodeId;
    }

    public ApiClient.TimingNodeInfo selectedNode() {
        return selectedNodeId == null ? null : findNode(selectedNodeId);
    }

    public void selectNode(String nodeId) {
        if (nodeId != null && findNode(nodeId) == null) {
            throw new IllegalArgumentException("Unknown TimingNode: " + nodeId);
        }
        if (java.util.Objects.equals(selectedNodeId, nodeId)) {
            return;
        }
        selectedNodeId = nodeId;
        clearLogBook();
    }

    public void applyCapabilities(ApiClient.CapabilitiesResult capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        autoRegEnabled = capabilities.enabled("DIRECT_REGISTRATION_SIMULATION");
    }

    public boolean autoRegEnabled() {
        return autoRegEnabled;
    }

    public List<ApiClient.ProblemInfo> selectedProblems() {
        if (selectedNodeId == null) {
            return List.of();
        }
        List<ApiClient.ProblemInfo> selected = new ArrayList<>();
        for (ApiClient.ProblemInfo problem : problems) {
            if (problem.nodeId() == null || selectedNodeId.equals(problem.nodeId())) {
                selected.add(problem);
            }
        }
        return List.copyOf(selected);
    }

    public Controls controls() {
        if (selectedNode() == null) {
            return new Controls(false, false, false);
        }

        /*
         * The Development Client deliberately does not reimplement TimingNode
         * acceptance rules. Supported requests remain available so developers
         * can exercise and inspect negative-path domain results from SI-01.
         */
        return new Controls(true, true, autoRegEnabled);
    }

    public void applyLogBookInfo(ApiClient.LogBookInfo info) {
        if (info == null) {
            throw new IllegalArgumentException("info must not be null");
        }
        logBookCount = info.count();
    }

    public void mergeLogBookPage(ApiClient.LogBookPage page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        logBookCount = page.count();
        for (ApiClient.TimingDataInfo record : page.records()) {
            mergeCommitted(record);
        }
    }

    public void mergeCommitted(ApiClient.TimingDataInfo record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (selectedNodeId == null || !selectedNodeId.equals(record.timingNodeId())) {
            return;
        }
        records.put(record.key(), record);
        if (record.sequenceNumber() > logBookCount) {
            logBookCount = record.sequenceNumber();
        }
    }

    public long logBookCount() {
        return logBookCount;
    }

    public List<ApiClient.TimingDataInfo> records() {
        List<ApiClient.TimingDataInfo> values = new ArrayList<>(records.values());
        values.sort(java.util.Comparator.comparingLong(ApiClient.TimingDataInfo::sequenceNumber));
        return List.copyOf(values);
    }


    /**
     * Projects immutable registration records into the user-facing registration view.
     *
     * <p>The default/reference profile keeps absolute UTC time in TimingData. This view
     * converts that value to the supplied civil-time zone. A future profile that exposes
     * another time representation is not rewritten here; unparseable profile text is
     * retained verbatim for diagnosis until that profile provides its own presentation
     * mapping.</p>
     */
    public List<InterpretedRegistration> interpretedRegistrations(ZoneId zone) {
        if (zone == null) {
            throw new IllegalArgumentException("zone must not be null");
        }

        Map<RegistrationProjectionKey, InterpretedRegistration> projected =
                new LinkedHashMap<>();

        for (ApiClient.TimingDataInfo record : records()) {
            String source = registrationSource(record);
            if (source == null) {
                continue;
            }

            RegistrationProjectionKey key = new RegistrationProjectionKey(
                    record.recordType(),
                    record.registrationId(),
                    record.effectiveTime());
            boolean revoke = record.codes().contains("REV");
            InterpretedRegistration previous = projected.get(key);

            if (previous == null) {
                projected.put(
                        key,
                        new InterpretedRegistration(
                                record.registrationId(),
                                displayTime(record.effectiveTime(), zone),
                                source,
                                revoke,
                                record.sequenceNumber(),
                                revoke ? Long.valueOf(record.sequenceNumber()) : null));
                continue;
            }

            projected.put(
                    key,
                    new InterpretedRegistration(
                            previous.registrationId(),
                            previous.displayTime(),
                            previous.source(),
                            previous.deleted() || revoke,
                            previous.firstSequence(),
                            revoke
                                    ? Long.valueOf(record.sequenceNumber())
                                    : previous.revokeSequence()));
        }

        return List.copyOf(projected.values());
    }

    private static String registrationSource(ApiClient.TimingDataInfo record) {
        if ("AUTO_REG".equals(record.recordType())) {
            return "A";
        }
        if ("MAN_REG".equals(record.recordType())) {
            return "M";
        }
        return null;
    }

    private static String displayTime(String value, ZoneId zone) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(Instant.parse(value).atZone(zone));
        } catch (DateTimeParseException ex) {
            return value;
        }
    }

    private record RegistrationProjectionKey(
            String recordType,
            String registrationId,
            String effectiveTime) {
        private RegistrationProjectionKey {
            Objects.requireNonNull(recordType, "recordType");
            Objects.requireNonNull(registrationId, "registrationId");
            Objects.requireNonNull(effectiveTime, "effectiveTime");
        }
    }

    public Long latestSequence() {
        Long latest = null;
        for (ApiClient.TimingDataInfo record : records.values()) {
            if (latest == null || record.sequenceNumber() > latest.longValue()) {
                latest = Long.valueOf(record.sequenceNumber());
            }
        }
        return latest;
    }

    public void clearLogBook() {
        records.clear();
        logBookCount = 0L;
    }

    public static String canonicalTime(Instant instant) {
        if (instant == null) {
            throw new IllegalArgumentException("instant must not be null");
        }
        return CANONICAL_TIME.format(instant);
    }

    public static String canonicalTime(
            LocalDate date,
            LocalTime time,
            ZoneId zone) {
        if (date == null || time == null || zone == null) {
            throw new IllegalArgumentException("date, time and zone must not be null");
        }
        return canonicalTime(date.atTime(time).atZone(zone).toInstant());
    }

    private ApiClient.TimingNodeInfo findNode(String nodeId) {
        for (ApiClient.TimingNodeInfo node : nodes) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }
}
