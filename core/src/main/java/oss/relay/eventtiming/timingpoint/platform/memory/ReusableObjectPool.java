package oss.relay.eventtiming.timingpoint.platform.memory;

import java.util.ArrayDeque;
import java.util.function.Supplier;

import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfIllegalState;
import static oss.relay.eventtiming.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/**
 * Small reusable-object pool for caller-owned single-threaded state.
 *
 * <p>The constructor creates the complete initial stock up front. acquire()
 * reuses one available object and creates a new object only when that stock is
 * exhausted. release() returns the object to the pool for later reuse.</p>
 *
 * <p>This class deliberately provides no locking. A component with one owning
 * execution lane can therefore reuse objects without synchronization overhead.
 * Callers that share a pool between threads must provide their own
 * synchronization.</p>
 */
public final class ReusableObjectPool<T> {
    private final Supplier<T> factory;
    private final ArrayDeque<T> available;
    private final int initialSize;

    private int size;
    private int inUse;
    private int peakInUse;

    /** Creates and retains the requested initial number of reusable objects. */
    public ReusableObjectPool(
            int initialSize,
            Supplier<T> factory) {
        failIfInvalidArgument(initialSize > 0, "initialSize must be positive");
        failIfInvalidArgument(factory != null, "factory must not be null");

        this.initialSize = initialSize;
        this.factory = factory;
        available = new ArrayDeque<T>(initialSize);

        for (int index = 0; index < initialSize; index++) {
            available.addLast(
                    create());
        }
    }

    /** Returns one reusable object, growing the pool only when none are available. */
    public T acquire() {
        T value = available.pollLast();
        if (value == null) {
            value = create();
        }

        inUse++;
        if (inUse > peakInUse) {
            peakInUse = inUse;
        }
        return value;
    }

    /** Returns one previously acquired object to the reusable stock. */
    public void release(T value) {
        failIfInvalidArgument(value != null, "value must not be null");
        failIfIllegalState(inUse > 0, "pool has no acquired object to release");

        inUse--;
        available.addLast(value);
    }

    /** Returns the number of objects created during pool construction. */
    public int initialSize() {
        return initialSize;
    }

    /** Returns the total number of objects the pool has created so far. */
    public int size() {
        return size;
    }

    /** Returns the number of objects currently acquired by callers. */
    public int inUse() {
        return inUse;
    }

    /** Returns the highest simultaneous acquired-object count observed so far. */
    public int peakInUse() {
        return peakInUse;
    }

    /** Returns the number of objects currently available for immediate reuse. */
    public int available() {
        return available.size();
    }

    /** Creates one non-null pooled value and records pool growth. */
    private T create() {
        T value = factory.get();
        failIfIllegalState(value != null, "pool factory returned null");
        size++;
        return value;
    }
}
