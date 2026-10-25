package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TimingViewModelTest {
    @Test
    void registrationScopeTitleFollowsOpenLocationAndSelectedNode() {
        TimingViewModel model = new TimingViewModel();
        assertEquals("–", model.registrationScopeLabel(false));
        assertEquals("All", model.registrationScopeLabel(true));
        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 35, "OPEN")));

        // A known OPEN node is not yet a live/synchronized view.
        assertEquals("–", model.registrationScopeLabel(false));
        model.viewState(TimingViewModel.ViewState.LIVE);
        assertEquals("24", model.registrationScopeLabel(false));
        model.selectNode("node-02");
        assertEquals("35", model.registrationScopeLabel(false));

        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 36, "OPEN")));
        assertEquals("36", model.registrationScopeLabel(false));
        assertEquals("All", model.registrationScopeLabel(true));
        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 36, "CLOSED")));
        assertEquals("–", model.registrationScopeLabel(false));
        model.viewState(TimingViewModel.ViewState.STALE);
        assertEquals("All", model.registrationScopeLabel(true));
        model.selectNode("node-01");
        assertEquals("–", model.registrationScopeLabel(false));
        model.viewState(TimingViewModel.ViewState.LIVE);
        assertEquals("24", model.registrationScopeLabel(false));
    }

    @Test
    void keepsSupportedCommandsAvailableForNegativePathTesting() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(
                node("node-01", null, "CLOSED")));
        model.applyCapabilities(capabilities(true, true));

        assertEquals("node-01", model.selectedNodeId());
        assertTrue(model.controls().open());
        assertTrue(model.controls().close());
        assertTrue(model.controls().autoReg());
        assertTrue(model.controls().manualReg());
        assertTrue(model.controls().simulation());

        model.applyStatus(status(
                node("node-01", 24, "OPEN")));
        model.viewState(TimingViewModel.ViewState.STALE);

        assertTrue(model.controls().open());
        assertTrue(model.controls().close());
        assertTrue(model.controls().autoReg());
        assertTrue(model.controls().manualReg());
        assertTrue(model.controls().simulation());

        model.applyCapabilities(capabilities(false, false));
        assertFalse(model.controls().autoReg());
        assertTrue(model.controls().manualReg());
        assertFalse(model.controls().simulation());
    }

    @Test
    void keepsSelectedNodeWhenStatusRefreshesAndClearsLogBookOnSelectionChange() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 25, "CLOSED")));
        model.selectNode("node-02");
        model.mergeCommitted(record("node-02", 1L, "N0001"));

        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 25, "OPEN")));

        assertEquals("node-02", model.selectedNodeId());
        assertEquals(1, model.records().size());

        model.selectNode("node-01");
        assertEquals("node-01", model.selectedNodeId());
        assertTrue(model.records().isEmpty());
        assertEquals(0L, model.logBookCount());
    }

    @Test
    void exposesSelectedNodeAndApplicationProblems() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(new ApiClient.StatusResult(
                List.of(
                        node("node-01", null, "ERROR"),
                        node("node-02", 25, "CLOSED")),
                List.of(
                        new ApiClient.ProblemInfo(
                                "TIMING_DATA_RECOVERY_FAILED",
                                "ERROR",
                                "node-01",
                                "recovery failed"),
                        new ApiClient.ProblemInfo(
                                "APPLICATION_WARNING",
                                "WARNING",
                                null,
                                "application warning")),
                "{}"));

        assertEquals(2, model.selectedProblems().size());
        model.selectNode("node-02");
        assertEquals(1, model.selectedProblems().size());
        assertEquals("APPLICATION_WARNING", model.selectedProblems().get(0).code());
    }

    @Test
    void deduplicatesCommittedRecordsByStableKey() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));
        model.applyLogBookInfo(new ApiClient.LogBookInfo(2L, 1L, 2L, "{}"));

        ApiClient.TimingDataInfo first = record("node-01", 1L, "N0001");
        ApiClient.TimingDataInfo second = record("node-01", 2L, "N0002");
        model.mergeLogBookPage(new ApiClient.LogBookPage(
                2L,
                null,
                List.of(first, second),
                "{}"));
        model.mergeCommitted(second);

        assertEquals(2, model.records().size());
        assertEquals(2L, model.logBookCount());
        assertEquals(2L, model.latestSequence());
    }

    @Test
    void ignoresCommittedEventForNonSelectedNode() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(
                node("node-01", 24, "OPEN"),
                node("node-02", 25, "OPEN")));

        model.mergeCommitted(record("node-02", 1L, "N0001"));

        assertTrue(model.records().isEmpty());
        assertNull(model.latestSequence());
    }

    @Test
    void projectsAutomaticAndManualRegistrationsToLocalClockTime() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:00Z",
                "N0001",
                List.of("ADD"),
                "2026-10-01T10:00:00.1Z",
                "{}"));
        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                2L,
                24,
                "MAN_REG",
                "2026-10-01T10:00:05Z",
                "N0002",
                List.of("ADD", "MAN"),
                "2026-10-01T10:00:05.1Z",
                "{}"));

        List<TimingViewModel.InterpretedRegistration> values =
                model.interpretedRegistrations(ZoneId.of("Europe/Amsterdam"));

        assertEquals(2, values.size());
        assertEquals("N0001", values.get(0).registrationId());
        assertEquals("12:00:00.00", values.get(0).displayTime());
        assertEquals("-", values.get(0).runningTime());
        assertEquals("AUTO", values.get(0).type());
        assertNull(values.get(0).teamId());
        assertEquals("", values.get(0).code());
        assertEquals("N0002", values.get(1).registrationId());
        assertEquals("12:00:05.00", values.get(1).displayTime());
        assertEquals("MAN", values.get(1).type());
        assertEquals("MAN", values.get(1).code());
    }

    @Test
    void projectsDefaultNormalTeamIdAndLeavesReserveUnresolved() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:00.00Z",
                "A0042",
                List.of("ADD"),
                "2026-10-01T10:00:00.100Z",
                "{}"));
        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                2L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:01.00Z",
                "R0001",
                List.of("ADD"),
                "2026-10-01T10:00:01.100Z",
                "{}"));

        List<TimingViewModel.InterpretedRegistration> values =
                model.interpretedRegistrations(
                        ZoneId.of("Europe/Amsterdam"));

        assertEquals("A0042", values.get(0).registrationId());
        assertEquals("0042", values.get(0).teamId());
        assertEquals("R0001", values.get(1).registrationId());
        assertNull(values.get(1).teamId());
    }

    @Test
    void manualRegistrationCanHaveManualTypeAndAutomaticTimeCode() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                24,
                "MAN_REG",
                "2026-10-01T10:00:00Z",
                "N0001",
                List.of("ADD", "AUTO"),
                "2026-10-01T10:00:00.1Z",
                "{}"));

        TimingViewModel.InterpretedRegistration value =
                model.interpretedRegistrations(ZoneId.of("Europe/Amsterdam")).get(0);

        assertEquals("MAN", value.type());
        assertEquals("AUTO", value.code());
    }

    @Test
    void marksRevokedRegistrationDeletedWithoutRemovingIt() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:00Z",
                "N0001",
                List.of("ADD"),
                "2026-10-01T10:00:00.1Z",
                "{}"));
        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                2L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:00Z",
                "N0001",
                List.of("REV"),
                "2026-10-01T10:05:00Z",
                "{}"));

        List<TimingViewModel.InterpretedRegistration> values =
                model.interpretedRegistrations(ZoneId.of("Europe/Amsterdam"));

        assertEquals(1, values.size());
        assertTrue(values.get(0).deleted());
        assertEquals("DELETED", values.get(0).state());
        assertEquals(1L, values.get(0).firstSequence());
        assertEquals(2L, values.get(0).revokeSequence());
        assertEquals(2, model.records().size());
        assertEquals(
                List.of("ADD"),
                model.registrationSource(values.get(0)).codes());
    }

    @Test
    void currentLocationProjectionRequiresOpenNodeAndCanShowAllHistory() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                23,
                "AUTO_REG",
                "2026-10-01T10:00:00Z",
                "N0001",
                List.of("ADD"),
                "2026-10-01T10:00:00.100Z",
                "{}"));
        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                2L,
                24,
                "AUTO_REG",
                "2026-10-01T10:00:01Z",
                "N0002",
                List.of("ADD"),
                "2026-10-01T10:00:01.100Z",
                "{}"));
        model.replaceRegistrationProjection(
                projectionInfo(
                        24,
                        1,
                        1,
                        2L,
                        0L),
                List.of(
                        projectionRow(
                                2L,
                                "N0002",
                                "2026-10-01T10:00:01Z",
                                true)));

        assertEquals(
                List.of("N0002"),
                model.interpretedRegistrations(
                                ZoneId.of("Europe/Amsterdam"),
                                false)
                        .stream()
                        .map(TimingViewModel.InterpretedRegistration::registrationId)
                        .toList());
        assertEquals(
                2,
                model.interpretedRegistrations(
                                ZoneId.of("Europe/Amsterdam"),
                                true)
                        .size());

        model.applyStatus(status(node("node-01", 24, "CLOSED")));
        assertTrue(
                model.interpretedRegistrations(
                                ZoneId.of("Europe/Amsterdam"),
                                false)
                        .isEmpty());
    }

    @Test
    void currentWpUsesServerValidityWhileAllKeepsLogBookHistory() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(
                status(
                        node(
                                "node-01",
                                24,
                                "OPEN")));
        model.viewState(
                TimingViewModel.ViewState.LIVE);

        model.mergeCommitted(
                new ApiClient.TimingDataInfo(
                        "node-01",
                        7L,
                        24,
                        "AUTO_REG",
                        "2026-10-01T10:00:00Z",
                        "A0007",
                        List.of("ADD"),
                        "2026-10-01T10:00:00.1Z",
                        "{}"));

        model.replaceRegistrationProjection(
                projectionInfo(
                        24,
                        1,
                        0,
                        8L,
                        8L),
                List.of(
                        projectionRow(
                                7L,
                                "A0007",
                                "2026-10-01T10:00:00Z",
                                false)));

        TimingViewModel.InterpretedRegistration current =
                model.interpretedRegistrations(
                                ZoneId.of(
                                        "Europe/Amsterdam"),
                                false)
                        .get(0);
        assertTrue(current.deleted());
        assertEquals("AUTO", current.type());
        assertEquals("0007", current.teamId());

        TimingViewModel.InterpretedRegistration historical =
                model.interpretedRegistrations(
                                ZoneId.of(
                                        "Europe/Amsterdam"),
                                true)
                        .get(0);
        assertFalse(historical.deleted());
    }

    @Test
    void projectionIsClearedWhenOpenLocationChanges() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(
                status(
                        node(
                                "node-01",
                                24,
                                "OPEN")));
        model.replaceRegistrationProjection(
                projectionInfo(
                        24,
                        1,
                        1,
                        2L,
                        0L),
                List.of(
                        projectionRow(
                                2L,
                                "A0001",
                                "2026-10-01T10:00:00Z",
                                true)));

        model.applyStatus(
                status(
                        node(
                                "node-01",
                                25,
                                "OPEN")));

        assertNull(
                model.registrationProjectionInfo());
        assertEquals(
                0,
                model.registrationProjectionRowCount());
    }

    @Test
    void nodeInfoRemainsInLogBookButNotInRegistrationProjection() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));

        model.mergeCommitted(new ApiClient.TimingDataInfo(
                "node-01",
                1L,
                24,
                "NODE_INFO",
                "2026-10-01T09:59:59Z",
                null,
                List.of("OPEN"),
                "2026-10-01T09:59:59.1Z",
                "{}"));
        model.mergeCommitted(record(
                "node-01",
                2L,
                "N0001"));

        assertEquals(2, model.records().size());
        assertEquals(
                1,
                model.interpretedRegistrations(
                        ZoneId.of("Europe/Amsterdam"))
                        .size());
    }

    @Test
    void unchangedStatusDoesNotSignalARefresh() {
        TimingViewModel model = new TimingViewModel();
        ApiClient.StatusResult initial = status(node("node-01", 24, "OPEN"));
        assertTrue(model.applyStatus(initial));
        assertFalse(model.applyStatus(new ApiClient.StatusResult(
                initial.nodes(), initial.problems(), "{ \"differentJson\": true }")));
        assertTrue(model.applyStatus(status(node("node-01", 25, "OPEN"))));
        assertFalse(model.applyStatus(status(node("node-01", 25, "OPEN"))));
        assertTrue(model.applyStatus(status(node("node-01", 25, "CLOSED"))));
    }

    @Test
    void sameCommittedRecordFromWebSocketAndHttpDoesNotSignalARefresh() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));
        ApiClient.TimingDataInfo first = record("node-01", 1L, "N0001");
        assertTrue(model.mergeCommitted(first));
        assertFalse(model.mergeCommitted(new ApiClient.TimingDataInfo(
                first.timingNodeId(), first.sequenceNumber(), first.locationId(),
                first.recordType(), first.effectiveTime(), first.registrationId(),
                first.codes(), first.recordedAt(), "{ \"http\": true }")));
        assertFalse(model.mergeLogBookPage(new ApiClient.LogBookPage(
                1, null, List.of(first), "{}")));
        assertTrue(model.mergeCommitted(record("node-01", 2L, "N0002")));
        assertFalse(model.mergeLogBookPage(new ApiClient.LogBookPage(
                1, null, List.of(first), "{}")));
        assertEquals(2L, model.logBookCount());
        assertEquals(2, model.records().size());
    }

    @Test
    void changingOneCommittedRecordUpdatesItsExistingRow() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));
        ApiClient.TimingDataInfo first = record("node-01", 1L, "N0001");
        assertTrue(model.mergeCommitted(first));
        assertTrue(model.mergeCommitted(new ApiClient.TimingDataInfo(
                first.timingNodeId(), first.sequenceNumber(), first.locationId(),
                first.recordType(), first.effectiveTime(), "N0002", first.codes(),
                first.recordedAt(), "{}")));
        assertEquals(1, model.records().size());
        assertEquals("N0002", model.records().get(0).registrationId());
    }

    @Test
    void presentsAuthoritativeRegistrationRunningTime() {
        TimingViewModel model = new TimingViewModel();
        model.applyStatus(status(node("node-01", 24, "OPEN")));
        model.replaceRegistrationProjection(
                projectionInfo(24, 2, 2, 3L, 0L),
                List.of(
                        new ApiClient.RegistrationProjectionRow(
                                2L,
                                "A0001",
                                "2026-10-01T10:00:00Z",
                                Long.valueOf(3_723_450L),
                                true),
                        new ApiClient.RegistrationProjectionRow(
                                3L,
                                "A0002",
                                "2026-10-01T10:00:01Z",
                                null,
                                true)));

        List<TimingViewModel.InterpretedRegistration> values =
                model.interpretedRegistrations(
                        ZoneId.of("Europe/Amsterdam"),
                        false);

        assertEquals("01:02:03.45", values.get(0).runningTime());
        assertEquals("-", values.get(1).runningTime());
    }

    @Test
    void formatsCanonicalCentisecondUtcTime() {
        assertEquals(
                "2026-10-01T12:00:00.12Z",
                TimingViewModel.canonicalTime(
                        Instant.parse("2026-10-01T12:00:00.123Z")));
    }

    @Test
    void convertsExplicitLocalCivilTimeUsingTheSuppliedZone() {
        assertEquals(
                "2026-10-01T10:00:00.00Z",
                TimingViewModel.canonicalTime(
                        LocalDate.parse("2026-10-01"),
                        LocalTime.parse("12:00:00"),
                        ZoneId.of("Europe/Amsterdam")));
    }

    private static ApiClient.RegistrationProjectionInfo projectionInfo(
            int locationId,
            int count,
            int uniqueCount,
            long lastMutation,
            long lastRevoke) {
        return new ApiClient.RegistrationProjectionInfo(
                locationId,
                count,
                uniqueCount,
                lastMutation,
                lastRevoke,
                "{}");
    }

    private static ApiClient.RegistrationProjectionRow projectionRow(
            long sequence,
            String registrationId,
            String time,
            boolean valid) {
        return new ApiClient.RegistrationProjectionRow(
                sequence,
                registrationId,
                time,
                null,
                valid);
    }

    private static ApiClient.StatusResult status(ApiClient.TimingNodeInfo... nodes) {
        return new ApiClient.StatusResult(List.of(nodes), List.of(), "{}");
    }

    private static ApiClient.TimingNodeInfo node(
            String id,
            Integer locationId,
            String state) {
        return new ApiClient.TimingNodeInfo(id, locationId, state);
    }

    private static ApiClient.CapabilitiesResult capabilities(
            boolean directEnabled,
            boolean simulationEnabled) {
        return new ApiClient.CapabilitiesResult(
                List.of(
                        new ApiClient.CapabilityInfo(
                                "DIRECT_REGISTRATION_SIMULATION",
                                true,
                                directEnabled),
                        new ApiClient.CapabilityInfo(
                                "TAG_SCENARIO_SIMULATION",
                                true,
                                simulationEnabled)),
                "{}");
    }

    private static ApiClient.TimingDataInfo record(
            String nodeId,
            long sequence,
            String registrationId) {
        return new ApiClient.TimingDataInfo(
                nodeId,
                sequence,
                24,
                "AUTO_REG",
                "2026-10-01T12:00:00Z",
                registrationId,
                List.of("ADD"),
                "2026-10-01T12:00:00.125Z",
                "{}");
    }
}
