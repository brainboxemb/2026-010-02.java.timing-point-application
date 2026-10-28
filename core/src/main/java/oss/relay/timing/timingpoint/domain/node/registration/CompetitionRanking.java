package oss.relay.timing.timingpoint.domain.node.registration;

import java.util.Arrays;
import java.util.List;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Calculates competition ranking for one active registration projection.
 *
 * <p>Rank is one plus the number of strictly faster valid passages. Equal elapsed times therefore
 * share the same rank. Invalid rows and rows without an unambiguous elapsed time receive rank zero.</p>
 *
 * <p>The class owns one reusable primitive scratch buffer. Recalculation allocates no temporary
 * collections; the buffer grows only when the retained projection exceeds its previous capacity.</p>
 */
final class CompetitionRanking {
    private long[] elapsedMillis = new long[0];

    /** Recalculates rank for all retained rows in one projection. */
    void recalculate(
            List<ActiveRegistrations.Registration> registrations) {

        failIfInvalidArgument(registrations != null, "registrations must not be null");
        ensureCapacity(registrations.size());

        int rankableCount = 0;
        for (ActiveRegistrations.Registration registration : registrations) {
            registration.setRank(0);
            long elapsed = registration.elapsedMillisOrMissing();
            if (registration.isValid()
                    && elapsed != StageTiming.NO_ELAPSED_MILLIS) {
                elapsedMillis[rankableCount++] = elapsed;
            }
        }

        Arrays.sort(elapsedMillis, 0, rankableCount);

        for (ActiveRegistrations.Registration registration : registrations) {
            long elapsed = registration.elapsedMillisOrMissing();
            if (registration.isValid()
                    && elapsed != StageTiming.NO_ELAPSED_MILLIS) {
                registration.setRank(
                        firstIndexOf(
                                elapsedMillis,
                                rankableCount,
                                elapsed)
                                + 1);
            }
        }
    }

    /** Grows the reusable primitive scratch buffer only when projection size grows. */
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
