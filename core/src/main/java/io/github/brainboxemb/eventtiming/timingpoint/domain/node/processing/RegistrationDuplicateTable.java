package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import java.util.Arrays;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Primitive storage for RegistrationId duplicate-suppression state.
 *
 * <p>This type owns the collection mechanics only: open-addressing lookup,
 * removal, capacity growth and rehashing. The surrounding
 * {@link RegistrationDuplicateFilter} owns policy, clocks and scheduling.</p>
 *
 * <p>The table starts with enough power-of-two capacity for the expected
 * registration count at no more than 50% occupancy. Growth replaces the two
 * backing arrays and rehashes retained entries; callers never manage that
 * storage transition themselves.</p>
 */
final class RegistrationDuplicateTable {
    private static final int EMPTY = 0;
    private static final int MAX_CAPACITY = 1 << 30;

    private final int initialCapacity;

    private int[] registrationIds;
    private long[] acceptedAtNanos;
    private int resizeThreshold;
    private int size;
    private int peakSize;

    RegistrationDuplicateTable(int expectedSize) {
        initialCapacity = tableCapacity(expectedSize);
        registrationIds = new int[initialCapacity];
        acceptedAtNanos = new long[initialCapacity];
        resizeThreshold = initialCapacity / 2;
    }

    /**
     * Reports whether retained state still suppresses this registration.
     *
     * <p>The exact duplicate-window boundary is eligible again. Encountering an
     * expired entry removes it immediately so later housekeeping has less work.</p>
     */
    boolean isDuplicate(
            int registrationId,
            long nowNanos,
            long duplicateWindowNanos) {
        int index = find(registrationId);
        if (index < 0) {
            return false;
        }

        if (nowNanos - acceptedAtNanos[index] < duplicateWindowNanos) {
            return true;
        }

        removeAt(index);
        return false;
    }

    /** Starts or refreshes suppression for one packed RegistrationId. */
    void record(
            int registrationId,
            long acceptedAt) {
        int index = find(registrationId);
        if (index >= 0) {
            acceptedAtNanos[index] = acceptedAt;
            return;
        }

        if (size >= resizeThreshold) {
            grow();
        }

        insertNew(registrationId, acceptedAt);
        if (size > peakSize) {
            peakSize = size;
        }
    }

    /** Removes one registration when retained. */
    void remove(int registrationId) {
        int index = find(registrationId);
        if (index >= 0) {
            removeAt(index);
        }
    }

    /**
     * Removes all registrations whose suppression window has reached its boundary.
     *
     * <p>Removal repairs probe clusters in place. The current index is therefore
     * rechecked after a removal because another retained entry may have moved
     * into that slot.</p>
     */
    void removeExpired(
            long nowNanos,
            long duplicateWindowNanos) {
        int index = 0;
        while (index < registrationIds.length) {
            if (registrationIds[index] == EMPTY) {
                index++;
            } else if (nowNanos - acceptedAtNanos[index] >= duplicateWindowNanos) {
                removeAt(index);
            } else {
                index++;
            }
        }
    }

    /** Clears retained registrations without releasing the allocated table storage. */
    void clear() {
        if (size == 0) {
            return;
        }

        Arrays.fill(registrationIds, EMPTY);
        size = 0;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int initialCapacity() {
        return initialCapacity;
    }

    int capacity() {
        return registrationIds.length;
    }

    int size() {
        return size;
    }

    int peakSize() {
        return peakSize;
    }

    /**
     * Removes one slot and repairs its linear-probe cluster in place.
     *
     * <p>Entries after the removed slot are reinserted into the same arrays so
     * lookup remains correct without tombstones or replacement objects.</p>
     */
    private void removeAt(int index) {
        int mask = registrationIds.length - 1;
        registrationIds[index] = EMPTY;
        size--;

        int cursor = (index + 1) & mask;
        while (registrationIds[cursor] != EMPTY) {
            int registrationId = registrationIds[cursor];
            long acceptedAt = acceptedAtNanos[cursor];
            registrationIds[cursor] = EMPTY;
            size--;
            insertNew(registrationId, acceptedAt);
            cursor = (cursor + 1) & mask;
        }
    }

    private int find(int registrationId) {
        int mask = registrationIds.length - 1;
        int index = hash(registrationId) & mask;

        while (registrationIds[index] != EMPTY) {
            if (registrationIds[index] == registrationId) {
                return index;
            }
            index = (index + 1) & mask;
        }
        return -1;
    }

    private void insertNew(
            int registrationId,
            long acceptedAt) {
        int mask = registrationIds.length - 1;
        int index = hash(registrationId) & mask;

        while (registrationIds[index] != EMPTY) {
            index = (index + 1) & mask;
        }

        registrationIds[index] = registrationId;
        acceptedAtNanos[index] = acceptedAt;
        size++;
    }

    /**
     * Replaces backing storage when the table reaches its 50% load threshold.
     *
     * <p>Growth is isolated here because it is a collection concern rather than
     * part of the duplicate-suppression algorithm.</p>
     */
    private void grow() {
        failIfInvalidArgument(
                registrationIds.length < MAX_CAPACITY,
                "duplicate table cannot grow further");

        int[] previousIds = registrationIds;
        long[] previousAcceptedAt = acceptedAtNanos;
        int nextCapacity = previousIds.length << 1;

        registrationIds = new int[nextCapacity];
        acceptedAtNanos = new long[nextCapacity];
        resizeThreshold = nextCapacity / 2;
        size = 0;

        for (int index = 0; index < previousIds.length; index++) {
            if (previousIds[index] != EMPTY) {
                insertNew(
                        previousIds[index],
                        previousAcceptedAt[index]);
            }
        }
    }

    /** Chooses a power-of-two table with at least two slots per expected registration. */
    private static int tableCapacity(int expectedSize) {
        failIfInvalidArgument(expectedSize > 0, "expectedSize must be positive");

        long required = Math.max(2L, (long) expectedSize * 2L);
        failIfInvalidArgument(
                required <= MAX_CAPACITY,
                "expectedSize is too large");

        int capacity = 2;
        while (capacity < required) {
            capacity <<= 1;
        }
        return capacity;
    }

    /** Mixes packed RegistrationId bits before applying the table mask. */
    private static int hash(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }
}
