package oss.relay.timing.timingpoint.domain.node.registration;

import java.util.Arrays;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Reusable primitive competition-ranking index.
 *
 * <p>Rank is one plus the number of strictly faster elapsed times. Equal elapsed times therefore
 * share the same rank. The class knows nothing about registrations, validity, stage starts or
 * projection lifecycle; callers decide which elapsed times participate.</p>
 *
 * <p>One primitive scratch buffer is retained and reused. A ranking rebuild allocates no temporary
 * collections; capacity grows only when a larger population is presented.</p>
 */
final class CompetitionRanking {
    private long[] elapsedMillis = new long[0];
    private int count;
    private boolean ready;

    /** Starts one new ranking rebuild and reserves reusable capacity for its maximum population. */
    void begin(int expectedCount) {

        failIfInvalidArgument(expectedCount >= 0, "expectedCount must not be negative");
        ensureCapacity(expectedCount);
        count = 0;
        ready = false;
    }

    /** Adds one elapsed time that participates in the current competition ranking. */
    void add(long elapsedMillis) {

        failIfInvalidArgument(elapsedMillis >= 0L, "elapsedMillis must not be negative");
        failIfIllegalState(count < this.elapsedMillis.length, "ranking capacity exceeded");
        this.elapsedMillis[count++] = elapsedMillis;
    }

    /** Sorts the current population and makes rankOf available. */
    void complete() {

        Arrays.sort(elapsedMillis, 0, count);
        ready = true;
    }

    /** Returns competition rank for one elapsed time in the completed population. */
    int rankOf(long elapsedMillis) {

        failIfIllegalState(ready, "ranking rebuild is not complete");
        failIfInvalidArgument(elapsedMillis >= 0L, "elapsedMillis must not be negative");
        return firstIndexOf(
                this.elapsedMillis,
                count,
                elapsedMillis)
                + 1;
    }

    /** Grows the reusable primitive scratch buffer only when population size grows. */
    private void ensureCapacity(int required) {

        if (elapsedMillis.length >= required) {
            return;
        }

        int grown =
                Math.max(
                        required,
                        Math.max(
                                16,
                                elapsedMillis.length * 2));
        elapsedMillis =
                Arrays.copyOf(
                        elapsedMillis,
                        grown);
    }

    /** Returns the first index containing value in one sorted prefix. */
    private static int firstIndexOf(
            long[] values,
            int length,
            long value) {

        int low = 0;
        int high = length;
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values[middle] < value) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        return low;
    }
}
