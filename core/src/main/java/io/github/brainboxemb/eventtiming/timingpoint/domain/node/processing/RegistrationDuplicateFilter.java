package io.github.brainboxemb.eventtiming.timingpoint.domain.node.processing;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingpoint.infra.configuration.ReadOnlyConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.platform.environment.MonotonicClock;

import java.util.Arrays;

import static io.github.brainboxemb.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Suppresses a RegistrationId for a configured elapsed-time window after selection.
 *
 * <p>The filter stores only the packed RegistrationId and the monotonic time at
 * which that registration was selected. A registration is a duplicate while
 * {@code now - acceptedAt < duplicateWindow}; reaching the boundary makes it
 * eligible again.</p>
 *
 * <p>State lives in two primitive open-addressing arrays. The initial table is
 * sized for the configured observation queue and uses at most half its slots.
 * Normal lookup, refresh, expiry and removal therefore create no entry objects,
 * boxed Long values or map nodes. The arrays grow only when that initial
 * occupancy assumption is exceeded.</p>
 *
 * <p>All calls are owned by the TagProcessor scheduler thread, so the table
 * requires no locking.</p>
 */
final class RegistrationDuplicateFilter {
    private static final int EMPTY = 0;
    private static final int MAX_CAPACITY = 1 << 30;

    private final ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration;
    private final MonotonicClock monotonicClock;
    private final TagProcessingMetrics metrics;
    private final int initialCapacity;

    private int[] registrationIds;
    private long[] acceptedAtNanos;
    private int resizeThreshold;
    private int size;
    private int peakSize;

    RegistrationDuplicateFilter(
            TagProcessingPolicy policy,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics) {
        this(ReadOnlyConfiguration.fixed(policy), monotonicClock, metrics);
    }

    /**
     * Creates the duplicate table from startup queue capacity.
     *
     * <p>The table is preallocated at no more than 50% expected occupancy so
     * the normal registration path does not need to resize it.</p>
     */
    RegistrationDuplicateFilter(
            ReadOnlyConfiguration<TagProcessingPolicy> policyConfiguration,
            MonotonicClock monotonicClock,
            TagProcessingMetrics metrics) {
        failIfInvalidArgument(policyConfiguration != null, "policyConfiguration must not be null");
        failIfInvalidArgument(monotonicClock != null, "monotonicClock must not be null");
        failIfInvalidArgument(metrics != null, "metrics must not be null");

        TagProcessingPolicy startupPolicy = policyConfiguration.startupValue();
        failIfInvalidArgument(startupPolicy != null, "policyConfiguration.startupValue must not be null");

        this.policyConfiguration = policyConfiguration;
        this.monotonicClock = monotonicClock;
        this.metrics = metrics;
        initialCapacity = tableCapacity(startupPolicy.observationQueueCapacity());
        registrationIds = new int[initialCapacity];
        acceptedAtNanos = new long[initialCapacity];
        resizeThreshold = initialCapacity / 2;
        recordTableMetrics();
    }

    /**
     * Reports whether a previously selected registration is still suppressed.
     *
     * <p>An expired entry is removed immediately. The exact duplicate-window
     * boundary is eligible: only elapsed time strictly smaller than the window
     * is considered a duplicate.</p>
     */
    boolean isDuplicate(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        int packedId = registrationId.packedValue();
        int index = find(packedId);
        if (index < 0) {
            return false;
        }

        long duplicateWindowNanos = policyConfiguration.currentValue().duplicateWindowNanos();
        if (duplicateWindowNanos != 0L
                && monotonicClock.nowNanos() - acceptedAtNanos[index] < duplicateWindowNanos) {
            return true;
        }

        removeAt(index);
        recordTableMetrics();
        return false;
    }

    /**
     * Starts or refreshes suppression from the current monotonic time.
     *
     * <p>Refreshing an existing RegistrationId only overwrites its primitive
     * timestamp. A zero duplicate window retains no state.</p>
     */
    void recordRegistration(RegistrationId registrationId) {
        requireRegistrationId(registrationId);

        int packedId = registrationId.packedValue();
        if (policyConfiguration.currentValue().duplicateWindowNanos() == 0L) {
            remove(packedId);
            return;
        }

        int index = find(packedId);
        if (index >= 0) {
            acceptedAtNanos[index] = monotonicClock.nowNanos();
            return;
        }

        if (size >= resizeThreshold) {
            grow();
        }

        insertNew(packedId, monotonicClock.nowNanos());
        if (size > peakSize) {
            peakSize = size;
        }
        recordTableMetrics();
    }

    /** Removes registrations whose elapsed duplicate window has reached its boundary. */
    void periodic() {
        removeExpired(monotonicClock.nowNanos());
    }

    /** Reconciles retained state immediately when the duplicate-window policy changes. */
    void onPolicyChanged() {
        if (policyConfiguration.currentValue().duplicateWindowNanos() == 0L) {
            clear();
        } else {
            removeExpired(monotonicClock.nowNanos());
        }
    }

    /** Reports whether one or more registrations are still retained for suppression. */
    boolean hasPendingState() {
        return size != 0;
    }

    /** Scans the primitive table and removes every entry whose suppression window has elapsed. */
    private void removeExpired(long nowNanos) {
        long duplicateWindowNanos = policyConfiguration.currentValue().duplicateWindowNanos();
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
        recordTableMetrics();
    }

    private void remove(int registrationId) {
        int index = find(registrationId);
        if (index >= 0) {
            removeAt(index);
            recordTableMetrics();
        }
    }

    /**
     * Removes one slot and repairs its linear-probe cluster in place.
     *
     * <p>Entries after the removed slot are reinserted into the same arrays so
     * lookups remain correct without tombstones or replacement allocations.</p>
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

    /** Inserts a known-new entry without capacity checks or metric publication. */
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
     * Doubles primitive storage when active duplicate state exceeds the initial load assumption.
     *
     * <p>Growth is intentionally the only duplicate-filter operation that allocates.</p>
     */
    private void grow() {
        failIfInvalidArgument(registrationIds.length < MAX_CAPACITY, "duplicate table cannot grow further");

        int[] previousIds = registrationIds;
        long[] previousAcceptedAt = acceptedAtNanos;
        int nextCapacity = previousIds.length << 1;

        registrationIds = new int[nextCapacity];
        acceptedAtNanos = new long[nextCapacity];
        resizeThreshold = nextCapacity / 2;
        size = 0;

        for (int index = 0; index < previousIds.length; index++) {
            if (previousIds[index] != EMPTY) {
                insertNew(previousIds[index], previousAcceptedAt[index]);
            }
        }
        recordTableMetrics();
    }

    /** Clears active state while retaining the already allocated primitive arrays. */
    private void clear() {
        if (size == 0) {
            return;
        }
        Arrays.fill(registrationIds, EMPTY);
        size = 0;
        recordTableMetrics();
    }

    /** Records current primitive table capacity and occupancy without allocating. */
    private void recordTableMetrics() {
        metrics.recordDuplicateTable(
                initialCapacity,
                registrationIds.length,
                size,
                peakSize);
    }

    /** Chooses a power-of-two table with at least two slots per expected active registration. */
    private static int tableCapacity(int expectedSize) {
        long required = Math.max(2L, (long) expectedSize * 2L);
        failIfInvalidArgument(required <= MAX_CAPACITY, "observationQueueCapacity is too large");

        int capacity = 2;
        while (capacity < required) {
            capacity <<= 1;
        }
        return capacity;
    }

    /** Mixes the packed type/number bits before applying the table mask. */
    private static int hash(int value) {
        int mixed = value;
        mixed ^= mixed >>> 16;
        mixed *= 0x7feb352d;
        mixed ^= mixed >>> 15;
        mixed *= 0x846ca68b;
        mixed ^= mixed >>> 16;
        return mixed;
    }

    private static void requireRegistrationId(RegistrationId registrationId) {
        failIfInvalidArgument(registrationId != null, "registrationId must not be null");
    }
}
