package oss.relay.timing.testclient;

import oss.relay.timing.testclient.central.CentralActions;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import static org.junit.jupiter.api.Assertions.*;

class EngineeringLuaEngineTest {
    @Test void linearBatchUsesExistingPlanAndIF03Sender() {
        List<String> requests = new ArrayList<>();
        List<String> output = new ArrayList<>();
        EngineeringLuaEngine.run("timing.batch{count=3, from=17, to=25, "
                + "order='linear', intervalMs=0, profile='simple'}",
                (id, profile) -> requests.add(id + ":" + profile),
                output::add, () -> true);
        assertEquals(List.of("A0017:simple", "A0018:simple",
                "A0019:simple"), requests);
        assertTrue(output.stream().anyMatch(x -> x.startsWith("Batch complete")));
    }

    @Test void randomBatchIsDeterministic() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        String lua = "timing.batch{count=6, from=1, to=20, "
                + "order='random', seed=42, intervalMs=0}";
        EngineeringLuaEngine.run(lua, (id, p) -> a.add(id), x -> {}, () -> true);
        EngineeringLuaEngine.run(lua, (id, p) -> b.add(id), x -> {}, () -> true);
        assertEquals(a, b);
        assertEquals(6, a.stream().distinct().count());
    }

    @Test void stopsBetweenRegistrations() {
        List<String> requests = new ArrayList<>();
        assertThrows(EngineeringLuaEngine.Stopped.class, () ->
                EngineeringLuaEngine.run("timing.batch{count=20, intervalMs=0}",
                        (id, p) -> requests.add(id), x -> {},
                        () -> requests.size() < 2));
        assertEquals(2, requests.size());
    }

    @Test void stopsTightLuaLoopWithoutSimulationCalls() {
        final int[] invocations = {0};
        assertThrows(EngineeringLuaEngine.Stopped.class, () ->
                EngineeringLuaEngine.run("while true do end",
                        (id, p) -> fail("should not simulate"),
                        x -> {}, () -> ++invocations[0] < 4));
    }

    @Test void cannotReachJavaFilesystemOrPackageLibraries() {
        EngineeringLuaEngine.run(
                "assert(io == nil); assert(os == nil); assert(package == nil); "
                + "assert(luajava == nil); assert(loadfile == nil); "
                + "assert(require == nil); assert(debug == nil)",
                (id, p) -> fail("should not simulate"), x -> {}, () -> true);
    }

    @Test void localCapabilityRefusalIsNotReportedAsServerRejection() {
        LuaError ex = assertThrows(LuaError.class, () ->
                EngineeringLuaEngine.run("timing.simulate('A0001', 'simple')",
                        (id, profile) -> {
                            throw new EngineeringLuaEngine.CapabilityUnavailable(
                                    "TAG_SCENARIO_SIMULATION disabled");
                        },
                        message -> {}, () -> true));
        assertTrue(ex.getMessage().contains("Simulation not submitted:"));
        assertFalse(ex.getMessage().contains("IF-03 simulation rejected:"));
    }

    @Test
    void centralConfigurationUsesGlobalStateAndStageId() {
        List<String> calls = new ArrayList<>();
        List<String> output = new ArrayList<>();
        CentralActions actions = new CentralActions() {
            private MutationResult changed(int affected) {
                return new MutationResult(affected, 0);
            }

            @Override
            public MutationResult setTeamAssignment(String regId, String teamId) {
                calls.add("team-set:" + regId + ":" + teamId);
                return changed(1);
            }

            @Override
            public MutationResult deleteTeamAssignment(String regId) {
                calls.add("team-del:" + regId);
                return changed(1);
            }

            @Override
            public MutationResult setStageStart(
                    int stageId,
                    String regId,
                    String start) {
                calls.add("start-set:" + stageId + ":" + regId + ":" + start);
                return changed(1);
            }

            @Override
            public MutationResult deleteStageStart(
                    int stageId,
                    String regId) {
                calls.add("start-del:" + stageId + ":" + regId);
                return changed(1);
            }

            @Override
            public MutationResult setStageStartRange(
                    int stageId,
                    String prefix,
                    int from,
                    int to,
                    String start,
                    long intervalMillis) {
                calls.add(
                        "start-range:"
                                + stageId + ":" + prefix + ":" + from + ":" + to
                                + ":" + start + ":" + intervalMillis);
                return changed(to - from + 1);
            }

            @Override
            public MutationResult deleteStageStartRange(
                    int stageId,
                    String prefix,
                    int from,
                    int to) {
                calls.add(
                        "start-range-del:"
                                + stageId + ":" + prefix + ":" + from + ":" + to);
                return changed(to - from + 1);
            }

            @Override
            public long requestTimingData(String nodeId, long fromSequence) {
                calls.add("req:" + nodeId + ":" + fromSequence);
                return 7L;
            }
        };

        EngineeringLuaEngine.run(
                "central.team_set('A0001','T001'); "
                        + "central.stage_start_set(2,'A0001','10:30:00.25'); "
                        + "central.stage_start_range{stage=2,prefix='A',from=2,to=3,"
                        + "start='10:31:00',intervalMs=1000}; "
                        + "central.timing_data_req(7)",
                (id, profile) -> fail("must not simulate"),
                actions,
                null,
                "A",
                output::add,
                () -> true);

        assertEquals(
                List.of(
                        "team-set:A0001:T001",
                        "start-set:2:A0001:10:30:00.25",
                        "start-range:2:A:2:3:10:31:00:1000",
                        "req:A:7"),
                calls);
        assertTrue(
                output.stream()
                        .anyMatch(x -> x.contains("2 affected")));
        assertTrue(
                output.stream()
                        .anyMatch(x -> x.contains("DN_PACKET #7")));
    }

    @Test
    void clientLifecycleCanBeAutomatedAndWaitedFor() {
        final boolean[] events = {false};
        final boolean[] central = {false};
        final int[] resets = {0};

        EngineeringClientActions actions = new EngineeringClientActions() {
            @Override
            public void reset() {
                resets[0]++;
                events[0] = false;
                central[0] = false;
            }

            @Override
            public void connectEvents() {
                events[0] = true;
            }

            @Override
            public void disconnectEvents() {
                events[0] = false;
                central[0] = false;
            }

            @Override
            public void connectCentral() {
                events[0] = true;
                central[0] = true;
            }

            @Override
            public void disconnectCentral() {
                central[0] = false;
                events[0] = true;
            }

            @Override
            public boolean apiAvailable() {
                return true;
            }

            @Override
            public boolean eventsConnected() {
                return events[0];
            }

            @Override
            public boolean centralConnected() {
                return central[0];
            }
        };

        EngineeringLuaEngine.run(
                "client.reset(); "
                        + "assert(client.api_available()); "
                        + "client.central_connect(); "
                        + "client.wait('central', true, 100); "
                        + "local s=client.status(); "
                        + "assert(s.eventsConnected and s.centralConnected); "
                        + "client.central_disconnect(); "
                        + "client.wait('central', false, 100); "
                        + "assert(client.events_connected())",
                (id, profile) -> fail("must not simulate"),
                null,
                actions,
                null,
                x -> {},
                () -> true);

        assertEquals(1, resets[0]);
        assertTrue(events[0]);
        assertFalse(central[0]);
    }

    @Test
    void timingNowReturnsCanonicalUtcTimestamp() {
        List<String> output = new ArrayList<>();
        EngineeringLuaEngine.run(
                "print(timing.now(-600))",
                (id, profile) -> fail("must not simulate"),
                output::add,
                () -> true);

        assertEquals(1, output.size());
        assertDoesNotThrow(
                () -> java.time.Instant.parse(output.get(0)));
    }

    @Test void invalidBatchStopsBeforeApiCall() {
        assertThrows(LuaError.class, () ->
                EngineeringLuaEngine.run("timing.batch{count=10001}",
                        (id, p) -> fail("must not send"), x -> {}, () -> true));
    }
}
