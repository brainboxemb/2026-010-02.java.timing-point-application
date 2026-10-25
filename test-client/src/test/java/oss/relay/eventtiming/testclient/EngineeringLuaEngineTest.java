package oss.relay.eventtiming.testclient;

import oss.relay.eventtiming.testclient.central.CentralActions;
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
    void centralActionsUseCapturedNodeAndReturnPacketNumbers() {
        List<String> calls = new ArrayList<>();
        List<String> output = new ArrayList<>();
        CentralActions actions = new CentralActions() {
            private long next = 1L;

            @Override
            public long setTeamAssignment(String nodeId, String regId, String teamId) {
                calls.add("team-set:" + nodeId + ":" + regId + ":" + teamId);
                return next++;
            }

            @Override
            public long deleteTeamAssignment(String nodeId, String regId) {
                calls.add("team-del:" + nodeId + ":" + regId);
                return next++;
            }

            @Override
            public long setStageStart(String nodeId, String regId, String start) {
                calls.add("start-set:" + nodeId + ":" + regId + ":" + start);
                return next++;
            }

            @Override
            public long deleteStageStart(String nodeId, String regId) {
                calls.add("start-del:" + nodeId + ":" + regId);
                return next++;
            }

            @Override
            public long requestTimingData(String nodeId, long fromSequence) {
                calls.add("req:" + nodeId + ":" + fromSequence);
                return next++;
            }
        };

        EngineeringLuaEngine.run(
                "central.team_set('A0001','T001'); "
                        + "central.stage_start_set('A0001','10:30:00.25'); "
                        + "timing.sleep(0); central.timing_data_req(7)",
                (id, profile) -> fail("must not simulate"),
                actions,
                "A",
                output::add,
                () -> true);

        assertEquals(
                List.of(
                        "team-set:A:A0001:T001",
                        "start-set:A:A0001:10:30:00.25",
                        "req:A:7"),
                calls);
        assertTrue(output.stream().anyMatch(x -> x.contains("DN_PACKET #3")));
    }

    @Test void invalidBatchStopsBeforeApiCall() {
        assertThrows(LuaError.class, () ->
                EngineeringLuaEngine.run("timing.batch{count=10001}",
                        (id, p) -> fail("must not send"), x -> {}, () -> true));
    }
}
