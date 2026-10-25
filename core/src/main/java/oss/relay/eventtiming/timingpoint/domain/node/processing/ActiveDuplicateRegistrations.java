package oss.relay.eventtiming.timingpoint.domain.node.processing;

import java.util.Arrays;

/**
 * Compact primitive state for registrations that are still inside the duplicate window.
 *
 * <p>This is intentionally not a general-purpose collection. Duplicate suppression
 * normally retains only a short-lived working set, so a dense linear array keeps
 * storage small and the implementation simple. The active set is independent of
 * the observation queue capacity; queue depth is not a bound or sizing signal for
 * duplicate state.</p>
 *
 * <p>The initial capacity is only an allocation batch, not a functional limit.
 * When more active registrations are observed, both primitive arrays are replaced
 * with larger copies. Expired registrations are removed lazily during lookup and
 * periodically by {@link RegistrationDuplicateFilter}, so retained state tracks
 * the duplicate window rather than event history.</p>
 *
 * <p>Linear lookup is a deliberate trade-off for this expected small active set.
 * Capacity, current size and peak size remain observable through processing
 * metrics so the storage strategy can be revisited if characterization shows a
 * sustained large working set.</p>
 */
final class ActiveDuplicateRegistrations {
    static final int INITIAL_CAPACITY = 16;

    private int[] registrationIds = new int[INITIAL_CAPACITY];
    private long[] acceptedAtNanos = new long[INITIAL_CAPACITY];
    private int size;
    private int peakSize;

    /**
     * Reports whether retained state still suppresses this registration.
     *
     * <p>The exact duplicate-window boundary is eligible again. An expired entry
     * is removed immediately instead of waiting for the periodic cleanup.</p>
     */
    boolean isDuplicate(
            int registrationId,
            long nowNanos,
            long duplicateWindowNanos) {
        int index = indexOf(registrationId);
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
        int index = indexOf(registrationId);
        if (index >= 0) {
            acceptedAtNanos[index] = acceptedAt;
            return;
        }

        ensureCapacity(size + 1);
        registrationIds[size] = registrationId;
        acceptedAtNanos[size] = acceptedAt;
        size++;
        if (size > peakSize) {
            peakSize = size;
        }
    }

    /** Removes one registration when retained. */
    void remove(int registrationId) {
        int index = indexOf(registrationId);
        if (index >= 0) {
            removeAt(index);
        }
    }

    /**
     * Removes every registration whose duplicate window has reached its boundary.
     *
     * <p>Removal replaces the removed slot with the final active entry. Ordering
     * has no domain meaning, so cleanup stays compact without shifting the tail.</p>
     */
    void removeExpired(
            long nowNanos,
            long duplicateWindowNanos) {
        int index = 0;
        while (index < size) {
            if (nowNanos - acceptedAtNanos[index] >= duplicateWindowNanos) {
                removeAt(index);
            } else {
                index++;
            }
        }
    }

    /** Clears logical state while retaining allocated primitive storage for reuse. */
    void clear() {
        size = 0;
    }

    boolean isEmpty() {
        return size == 0;
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
     * Scans newest storage positions first because duplicates commonly follow shortly after acceptance.
     *
     * <p>New registrations are appended at the end of the active set. Removal may
     * move the final entry into an earlier slot, so this is a locality heuristic
     * rather than a chronological ordering guarantee.</p>
     */
    private int indexOf(int registrationId) {
        for (int index = size - 1; index >= 0; index--) {
            if (registrationIds[index] == registrationId) {
                return index;
            }
        }
        return -1;
    }

    /** Removes one entry in constant time because active-state ordering is irrelevant. */
    private void removeAt(int index) {
        int lastIndex = size - 1;
        if (index != lastIndex) {
            registrationIds[index] = registrationIds[lastIndex];
            acceptedAtNanos[index] = acceptedAtNanos[lastIndex];
        }
        size = lastIndex;
    }

    /**
     * Expands primitive storage only when the current active set no longer fits.
     *
     * <p>{@link Arrays#copyOf(int[], int)} performs the ordinary Java backing-array
     * replacement; no collection-style hashing, probing or rehashing is involved.</p>
     */
    private void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= registrationIds.length) {
            return;
        }

        int nextCapacity = registrationIds.length << 1;
        registrationIds = Arrays.copyOf(registrationIds, nextCapacity);
        acceptedAtNanos = Arrays.copyOf(acceptedAtNanos, nextCapacity);
    }
}
