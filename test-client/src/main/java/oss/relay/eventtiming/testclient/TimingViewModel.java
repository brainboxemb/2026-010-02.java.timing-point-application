package oss.relay.eventtiming.testclient;

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
import java.util.Locale;
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
            boolean autoReg,
            boolean manualReg,
            boolean simulation) {
    }

    /** One user-facing registration projected from immutable committed TimingData. */
    public record InterpretedRegistration(
            String registrationId,
            String teamId,
            String displayTime,
            String runningTime,
            String type,
            String code,
            int locationId,
            boolean deleted,
            long firstSequence,
            Long revokeSequence) {
        public String state() {
            return deleted ? "DELETED" : "";
        }
    }

    private static final DateTimeFormatter CANONICAL_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SS'Z'")
                    .withZone(ZoneOffset.UTC);

    private ViewState viewState = ViewState.DISCONNECTED;
    private List<ApiClient.TimingNodeInfo> nodes = List.of();
    private List<ApiClient.ProblemInfo> problems = List.of();
    private String selectedNodeId;
    private boolean autoRegEnabled;
    private boolean simulationEnabled;
    private long logBookCount;
    private final Map<ApiClient.TimingDataKey, ApiClient.TimingDataInfo> records =
            new LinkedHashMap<>();
    private ApiClient.RegistrationProjectionInfo registrationProjectionInfo;
    private final Map<Long, ApiClient.RegistrationProjectionRow> registrationProjectionRows =
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

    /** @return true only when the presented node/problem state changes. */
    public boolean applyStatus(ApiClient.StatusResult status) {
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        // WebSocket and HTTP status responses may carry different raw JSON.
        boolean changed = !nodes.equals(status.nodes())
                || !problems.equals(status.problems());
        nodes = status.nodes();
        problems = status.problems();

        ApiClient.TimingNodeInfo selected = selectedNode();
        if (selected == null
                || !"OPEN".equals(selected.state())
                || selected.locationId() == null
                || registrationProjectionInfo != null
                        && registrationProjectionInfo.locationId()
                                != selected.locationId().intValue()) {
            clearRegistrationProjection();
        }

        if (selectedNodeId == null || findNode(selectedNodeId) == null) {
            String previous = selectedNodeId;
            selectNode(nodes.isEmpty() ? null : nodes.get(0).id());
            changed |= !Objects.equals(previous, selectedNodeId);
        }
        return changed;
    }

    public String selectedNodeId() {
        return selectedNodeId;
    }

    public ApiClient.TimingNodeInfo selectedNode() {
        return selectedNodeId == null ? null : findNode(selectedNodeId);
    }

    /**
     * The interpreted view defaults to the selected node's OPEN location.
     * Do not show a stale location when the view is disconnected or the node
     * is closed. The explicit All scope is independent of node state.
     */
    public String registrationScopeLabel(boolean allLocations) {
        if (allLocations) return "All";
        ApiClient.TimingNodeInfo selected = selectedNode();
        if (viewState != ViewState.LIVE || selected == null
                || !"OPEN".equals(selected.state())
                || selected.locationId() == null) {
            return "–";
        }
        return Integer.toString(selected.locationId());
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
        clearRegistrationProjection();
    }

    public void applyCapabilities(ApiClient.CapabilitiesResult capabilities) {
        if (capabilities == null) {
            throw new IllegalArgumentException("capabilities must not be null");
        }
        autoRegEnabled =
                capabilities.enabled(
                        "DIRECT_REGISTRATION_SIMULATION");
        simulationEnabled =
                capabilities.enabled(
                        "TAG_SCENARIO_SIMULATION");
    }

    public boolean autoRegEnabled() {
        return autoRegEnabled;
    }

    public boolean simulationEnabled() {
        return simulationEnabled;
    }

    public List<ApiClient.ProblemInfo> selectedProblems() {
        return problemsForNode(selectedNodeId);
    }

    public List<ApiClient.ProblemInfo> problemsForNode(String nodeId) {
        if (nodeId == null) return List.of();
        List<ApiClient.ProblemInfo> matching = new ArrayList<>();
        for (ApiClient.ProblemInfo problem : problems) {
            if (problem.nodeId() == null || nodeId.equals(problem.nodeId())) {
                matching.add(problem);
            }
        }
        return List.copyOf(matching);
    }

    public Controls controls() {
        if (selectedNode() == null) {
            return new Controls(false, false, false, false, false);
        }

        /*
         * The Development Client deliberately does not reimplement TimingNode
         * acceptance rules. Supported requests remain available so developers
         * can exercise and inspect negative-path domain results from SI-01.
         */
        return new Controls(
                true,
                true,
                autoRegEnabled,
                true,
                simulationEnabled);
    }

    public void applyLogBookInfo(ApiClient.LogBookInfo info) {
        if (info == null) {
            throw new IllegalArgumentException("info must not be null");
        }
        logBookCount = info.count();
    }

    /** @return true if the presented record set or count changes. */
    public boolean mergeLogBookPage(ApiClient.LogBookPage page) {
        if (page == null) {
            throw new IllegalArgumentException("page must not be null");
        }
        // A newer live event can outrun an older HTTP page.
        long nextCount = Math.max(logBookCount, page.count());
        boolean changed = nextCount != logBookCount;
        logBookCount = nextCount;
        for (ApiClient.TimingDataInfo record : page.records()) {
            changed |= mergeCommitted(record);
        }
        return changed;
    }

    /** @return false for an unchanged/replayed or non-selected record. */
    public boolean mergeCommitted(ApiClient.TimingDataInfo record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        if (selectedNodeId == null || !selectedNodeId.equals(record.timingNodeId())) {
            return false;
        }
        boolean recordChanged = !equivalent(records.get(record.key()), record);
        if (recordChanged) {
            records.put(record.key(), record);
        }
        long nextCount = Math.max(logBookCount, record.sequenceNumber());
        boolean countChanged = nextCount != logBookCount;
        logBookCount = nextCount;
        return recordChanged || countChanged;
    }

    private static boolean equivalent(
            ApiClient.TimingDataInfo a, ApiClient.TimingDataInfo b) {
        if (a == null || b == null) return a == b;
        // Protocol wrappers may format rawJson differently for the same
        // committed record. Only compare the actual domain/view fields.
        return Objects.equals(a.key(), b.key())
                && a.locationId() == b.locationId()
                && Objects.equals(a.recordType(), b.recordType())
                && Objects.equals(a.effectiveTime(), b.effectiveTime())
                && Objects.equals(a.registrationId(), b.registrationId())
                && Objects.equals(a.codes(), b.codes())
                && Objects.equals(a.recordedAt(), b.recordedAt());
    }

    public ApiClient.RegistrationProjectionInfo registrationProjectionInfo() {
        return registrationProjectionInfo;
    }

    public int registrationProjectionRowCount() {
        return registrationProjectionRows.size();
    }

    public long latestRegistrationProjectionSequence() {
        long latest = 0L;
        for (Long sequence : registrationProjectionRows.keySet()) {
            if (sequence.longValue() > latest) {
                latest = sequence.longValue();
            }
        }
        return latest;
    }

    /** Replaces the active-WP projection with one complete server snapshot. */
    public boolean replaceRegistrationProjection(
            ApiClient.RegistrationProjectionInfo info,
            List<ApiClient.RegistrationProjectionRow> rows) {

        if (info == null || rows == null) {
            throw new IllegalArgumentException(
                    "registration projection info/rows must not be null");
        }

        Map<Long, ApiClient.RegistrationProjectionRow> replacement =
                new LinkedHashMap<>();
        for (ApiClient.RegistrationProjectionRow row : rows) {
            if (row == null) {
                throw new IllegalArgumentException(
                        "registration projection rows must not contain null");
            }
            replacement.put(
                    Long.valueOf(row.sequenceNumber()),
                    row);
        }

        boolean changed =
                !Objects.equals(registrationProjectionInfo, info)
                        || !registrationProjectionRows.equals(replacement);
        registrationProjectionInfo = info;
        registrationProjectionRows.clear();
        registrationProjectionRows.putAll(replacement);
        return changed;
    }

    /** Appends server rows when metadata proves no earlier row was revoked. */
    public boolean appendRegistrationProjection(
            ApiClient.RegistrationProjectionInfo info,
            List<ApiClient.RegistrationProjectionRow> rows) {

        if (info == null || rows == null) {
            throw new IllegalArgumentException(
                    "registration projection info/rows must not be null");
        }

        boolean changed =
                !Objects.equals(registrationProjectionInfo, info);
        registrationProjectionInfo = info;
        for (ApiClient.RegistrationProjectionRow row : rows) {
            ApiClient.RegistrationProjectionRow previous =
                    registrationProjectionRows.put(
                            Long.valueOf(row.sequenceNumber()),
                            row);
            changed |= !Objects.equals(previous, row);
        }
        return changed;
    }

    public void clearRegistrationProjection() {
        registrationProjectionInfo = null;
        registrationProjectionRows.clear();
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
        return interpretedRegistrations(
                zone,
                true);
    }

    public List<InterpretedRegistration> interpretedRegistrations(
            ZoneId zone,
            boolean allLocations) {
        if (zone == null) {
            throw new IllegalArgumentException("zone must not be null");
        }

        List<InterpretedRegistration> local =
                localInterpretedRegistrations(zone);
        if (allLocations) {
            return local;
        }

        ApiClient.TimingNodeInfo selected =
                selectedNode();
        if (selected == null
                || !"OPEN".equals(selected.state())
                || selected.locationId() == null
                || registrationProjectionInfo == null
                || registrationProjectionInfo.locationId()
                        != selected.locationId().intValue()) {
            return List.of();
        }

        Map<Long, InterpretedRegistration> localBySequence =
                new LinkedHashMap<>();
        for (InterpretedRegistration registration : local) {
            localBySequence.put(
                    Long.valueOf(registration.firstSequence()),
                    registration);
        }

        List<InterpretedRegistration> projected =
                new ArrayList<>();
        for (ApiClient.RegistrationProjectionRow row
                : registrationProjectionRows.values()) {
            InterpretedRegistration detail =
                    localBySequence.get(
                            Long.valueOf(row.sequenceNumber()));
            projected.add(
                    new InterpretedRegistration(
                            row.registrationId(),
                            detail == null
                                    ? defaultTeamId(row.registrationId())
                                    : detail.teamId(),
                            displayTime(row.effectiveTime(), zone),
                            displayRunningTime(row.elapsedMillis()),
                            detail == null ? "" : detail.type(),
                            detail == null ? "" : detail.code(),
                            registrationProjectionInfo.locationId(),
                            !row.valid(),
                            row.sequenceNumber(),
                            detail == null
                                    ? null
                                    : detail.revokeSequence()));
        }
        return List.copyOf(projected);
    }

    private List<InterpretedRegistration> localInterpretedRegistrations(
            ZoneId zone) {

        Map<RegistrationProjectionKey, InterpretedRegistration> projected =
                new LinkedHashMap<>();

        for (ApiClient.TimingDataInfo record : records()) {
            String type = registrationType(record);
            if (type == null) {
                continue;
            }

            RegistrationProjectionKey key = new RegistrationProjectionKey(
                    record.recordType(),
                    record.registrationId(),
                    record.effectiveTime(),
                    record.locationId());
            boolean revoke = record.codes().contains("REV");
            InterpretedRegistration previous = projected.get(key);
            String code = registrationCode(record, previous);

            if (previous == null) {
                projected.put(
                        key,
                        new InterpretedRegistration(
                                record.registrationId(),
                                defaultTeamId(record.registrationId()),
                                displayTime(record.effectiveTime(), zone),
                                "-",
                                type,
                                code,
                                record.locationId(),
                                revoke,
                                record.sequenceNumber(),
                                revoke ? Long.valueOf(record.sequenceNumber()) : null));
                continue;
            }

            projected.put(
                    key,
                    new InterpretedRegistration(
                            previous.registrationId(),
                            previous.teamId(),
                            previous.displayTime(),
                            previous.runningTime(),
                            previous.type(),
                            previous.code(),
                            previous.locationId(),
                            previous.deleted() || revoke,
                            previous.firstSequence(),
                            revoke
                                    ? Long.valueOf(record.sequenceNumber())
                                    : previous.revokeSequence()));
        }

        return List.copyOf(projected.values());
    }

    private static String registrationType(ApiClient.TimingDataInfo record) {
        if ("AUTO_REG".equals(record.recordType())) {
            return "AUTO";
        }
        if ("MAN_REG".equals(record.recordType())) {
            return "MAN";
        }
        return null;
    }

    private static String registrationCode(
            ApiClient.TimingDataInfo record,
            InterpretedRegistration previous) {
        if ("AUTO_REG".equals(record.recordType())) {
            return "";
        }
        if (!"MAN_REG".equals(record.recordType())) {
            return "";
        }
        if (record.codes().contains("MAN")) {
            return "MAN";
        }
        if (record.codes().contains("AUTO")) {
            return "AUTO";
        }
        return previous == null ? "" : previous.code();
    }

    private static String defaultTeamId(
            String registrationId) {
        if (registrationId == null
                || !registrationId.matches(
                        "A\\d{4}")) {
            return null;
        }

        String number =
                registrationId.substring(1);
        return "0000".equals(number)
                ? null
                : number;
    }

    static String displayRunningTime(Long elapsedMillis) {
        if (elapsedMillis == null) {
            return "-";
        }

        long value = elapsedMillis.longValue();
        boolean negative = value < 0L;
        long absoluteMillis = negative ? -value : value;
        long totalHundredths = absoluteMillis / 10L;
        long hours = totalHundredths / 360000L;
        long minutes = totalHundredths / 6000L % 60L;
        long seconds = totalHundredths / 100L % 60L;
        long hundredths = totalHundredths % 100L;

        return (negative ? "-" : "")
                + String.format(
                        Locale.ROOT,
                        "%02d:%02d:%02d.%02d",
                        hours,
                        minutes,
                        seconds,
                        hundredths);
    }

    private static String displayTime(String value, ZoneId zone) {
        try {
            return DateTimeFormatter.ofPattern("HH:mm:ss.SS")
                    .format(Instant.parse(value).atZone(zone));
        } catch (DateTimeParseException ex) {
            return value;
        }
    }

    private record RegistrationProjectionKey(
            String recordType,
            String registrationId,
            String effectiveTime,
            int locationId) {
        private RegistrationProjectionKey {
            Objects.requireNonNull(recordType, "recordType");
            Objects.requireNonNull(registrationId, "registrationId");
            Objects.requireNonNull(effectiveTime, "effectiveTime");
        }
    }

    /**
     * Returns the original committed ADD record behind one interpreted row.
     *
     * <p>The Development Client owns this projection/business interpretation;
     * SI-01 TimingNode bookkeeping does not search ADD/REV history.</p>
     */
    public ApiClient.TimingDataInfo registrationSource(
            InterpretedRegistration registration) {
        if (registration == null) {
            throw new IllegalArgumentException(
                    "registration must not be null");
        }
        if (selectedNodeId == null) {
            return null;
        }
        return records.get(
                new ApiClient.TimingDataKey(
                        selectedNodeId,
                        registration.firstSequence()));
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
