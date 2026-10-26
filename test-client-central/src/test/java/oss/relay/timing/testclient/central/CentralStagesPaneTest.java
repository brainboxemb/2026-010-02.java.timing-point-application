package oss.relay.timing.testclient.central;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CentralStagesPaneTest {
    @Test
    void selectedStageShowsOnlyItsStartTimes() {
        CentralSystemSimulator.StageStartView stage1Start =
                new CentralSystemSimulator.StageStartView(
                        "A0001",
                        "19:00:00");
        CentralSystemSimulator.StageStartView stage2Start =
                new CentralSystemSimulator.StageStartView(
                        "A0001",
                        "19:10:00");
        List<CentralSystemSimulator.StageView> stages =
                List.of(
                        new CentralSystemSimulator.StageView(
                                1,
                                List.of(stage1Start)),
                        new CentralSystemSimulator.StageView(
                                2,
                                List.of(stage2Start)));

        assertEquals(
                List.of(1, 2),
                CentralStagesPane.stageIds(stages));
        assertEquals(
                List.of(stage2Start),
                CentralStagesPane.startTimesForStage(
                        stages,
                        Integer.valueOf(2)));
        assertTrue(
                CentralStagesPane.startTimesForStage(
                                stages,
                                Integer.valueOf(3))
                        .isEmpty());
    }
}
