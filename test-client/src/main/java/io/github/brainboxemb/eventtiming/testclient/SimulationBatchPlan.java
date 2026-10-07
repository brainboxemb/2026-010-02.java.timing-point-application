package io.github.brainboxemb.eventtiming.testclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/** Pure client-side selection plan for one simulated registration batch. */
final class SimulationBatchPlan {

    enum Order {
        ASCENDING,
        RANDOM
    }

    private SimulationBatchPlan() {
    }

    static List<String> registrationIds(
            int count,
            int from,
            int to,
            Order order,
            long seed) {
        if (count < 1) {
            throw new IllegalArgumentException(
                    "Count must be positive");
        }
        if (from < 1 || to < from) {
            throw new IllegalArgumentException(
                    "Registration range must satisfy 1 <= from <= to");
        }
        if (order == null) {
            throw new IllegalArgumentException(
                    "Order must not be null");
        }

        int available =
                to - from + 1;
        if (count > available) {
            throw new IllegalArgumentException(
                    "Count must not exceed the registration range size");
        }

        List<Integer> numbers =
                new ArrayList<Integer>(
                        available);
        for (int number = from;
                number <= to;
                number++) {
            numbers.add(
                    Integer.valueOf(
                            number));
        }

        if (order == Order.RANDOM) {
            Collections.shuffle(
                    numbers,
                    new Random(
                            seed));
        }

        List<String> result =
                new ArrayList<String>(
                        count);
        for (int index = 0;
                index < count;
                index++) {
            result.add(
                    String.format(
                            "N%04d",
                            numbers.get(index)));
        }
        return Collections.unmodifiableList(
                result);
    }
}
