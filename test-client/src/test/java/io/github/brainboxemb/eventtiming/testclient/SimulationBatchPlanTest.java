package io.github.brainboxemb.eventtiming.testclient;

import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulationBatchPlanTest {

    @Test
    void createsAscendingRegistrationSelection() {
        assertEquals(
                List.of(
                        "RT-A-0010",
                        "RT-A-0011",
                        "RT-A-0012"),
                SimulationBatchPlan.registrationIds(
                        3,
                        10,
                        20,
                        SimulationBatchPlan.Order.ASCENDING,
                        1L));
    }

    @Test
    void randomSelectionIsSeededUniqueAndRepeatable() {
        List<String> first =
                SimulationBatchPlan.registrationIds(
                        10,
                        1,
                        100,
                        SimulationBatchPlan.Order.RANDOM,
                        42L);
        List<String> repeated =
                SimulationBatchPlan.registrationIds(
                        10,
                        1,
                        100,
                        SimulationBatchPlan.Order.RANDOM,
                        42L);
        List<String> otherSeed =
                SimulationBatchPlan.registrationIds(
                        10,
                        1,
                        100,
                        SimulationBatchPlan.Order.RANDOM,
                        43L);

        assertEquals(
                first,
                repeated);
        assertNotEquals(
                first,
                otherSeed);
        assertEquals(
                first.size(),
                new HashSet<String>(
                        first)
                        .size());
        for (String registrationId : first) {
            int number =
                    Integer.parseInt(
                            registrationId.substring(
                                    "RT-A-".length()));
            assertTrue(
                    number >= 1
                            && number <= 100);
        }
    }

    @Test
    void rejectsCountLargerThanRange() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SimulationBatchPlan.registrationIds(
                        4,
                        10,
                        12,
                        SimulationBatchPlan.Order.ASCENDING,
                        1L));
    }
}
