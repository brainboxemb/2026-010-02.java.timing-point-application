package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Small typed local event for post-fact notifications.
 *
 * <p>Event deliberately has no hidden executor, topic router or retry queue.
 * {@link #emit(Object)} invokes the current listeners synchronously, in
 * subscription order, on the emitting thread. A listener that needs slow I/O,
 * retry or network delivery must hand the immutable value to its own bounded
 * execution/delivery mechanism and return quickly.</p>
 *
 * <p>Listeners are wired during application composition, before components are
 * activated. Runtime rewiring is deliberately unsupported: {@link #subscribe(Consumer)}
 * is not a concurrent runtime operation. Once activation begins, the listener
 * representation is read-only and {@code emit(...)} may safely use it without
 * subscription locks.</p>
 *
 * <p>The internal registry is optimized for the common 0/1-subscriber case:
 * no container is allocated for zero listeners, one listener is stored directly,
 * and an immutable Consumer array is used only when two or more listeners are
 * present. This is an implementation detail; EventSource semantics remain 0..N
 * subscribers and callers never select a cardinality mode.</p>
 *
 * <p>Event deliberately does <strong>not</strong> serialize concurrent emits.
 * If two threads call {@code emit(...)} at the same time, the same listener may
 * be invoked concurrently on those two emitting threads. An event owner that
 * requires strict ordering/non-overlap must emit from its own serial execution
 * boundary. Listener implementations must therefore be thread-safe whenever
 * their owning event can be emitted concurrently.</p>
 *
 * <p>Ordinary listener {@link RuntimeException}s are isolated per listener:
 * later listeners are still invoked and the failures are returned in a
 * {@link DeliveryReport}. Fatal {@link Error}s are not swallowed.</p>
 *
 * @param <T> immutable/read-only value delivered to listeners
 */
public final class Event<T> implements EventSource<T> {

    /*
     * Compact composition-built listener representation:
     *
     * null          -> no listeners
     * Consumer<T>   -> exactly one listener
     * Consumer<?>[] -> two or more listeners in subscription order
     *
     * subscribe replaces this reference only during single-threaded composition.
     * Runtime emit reads the finished representation and never invokes user code
     * under a subscription lock.
     */
    private Object listeners;

    /**
     * Subscribes one listener.
     *
     * <p>The same listener is stored at most once according to equals().
     * Subscription belongs to composition and must finish before activation.</p>
     *
     * @return {@code true} when the listener was newly added
     */
    @Override
    public boolean subscribe(
            Consumer<T> listener) {
        requireListener(
                listener);

        Object current =
                listeners;

        if (current == null) {
            listeners = listener;
            return true;
        }

        if (current instanceof Consumer) {
            Consumer<T> existing =
                    singleListener(
                            current);
            if (existing.equals(
                    listener)) {
                return false;
            }

            listeners =
                    new Consumer<?>[] {
                            existing,
                            listener
                    };
            return true;
        }

        Consumer<?>[] existing =
                listenerArray(
                        current);
        for (Consumer<?> candidate : existing) {
            if (candidate.equals(
                    listener)) {
                return false;
            }
        }

        Consumer<?>[] expanded =
                new Consumer<?>[
                        existing.length + 1];
        System.arraycopy(
                existing,
                0,
                expanded,
                0,
                existing.length);
        expanded[existing.length] =
                listener;
        listeners = expanded;
        return true;
    }

    /**
     * Delivers one value to the listener snapshot that exists at emit start.
     *
     * <p>This method does not throw ordinary listener RuntimeExceptions. They are
     * collected into the returned report so one listener cannot prevent delivery
     * to later listeners. Fatal Errors still propagate to the owner thread.</p>
     */
    public DeliveryReport emit(
            T value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null");
        }

        Object snapshot =
                listeners;

        if (snapshot == null) {
            return DeliveryReport.noListeners();
        }

        if (snapshot instanceof Consumer) {
            return deliverSingle(
                    singleListener(
                            snapshot),
                    value);
        }

        return deliverMany(
                listenerArray(
                        snapshot),
                value);
    }

    private DeliveryReport deliverSingle(
            Consumer<T> listener,
            T value) {
        try {
            listener.accept(
                    value);
            return DeliveryReport.success(
                    1);
        } catch (RuntimeException ex) {
            return DeliveryReport.failure(
                    1,
                    ex);
        }
    }

    private DeliveryReport deliverMany(
            Consumer<?>[] snapshot,
            T value) {
        List<RuntimeException> failures = null;

        for (Consumer<?> rawListener : snapshot) {
            Consumer<T> listener =
                    typedListener(
                            rawListener);
            try {
                listener.accept(
                        value);
            } catch (RuntimeException ex) {
                if (failures == null) {
                    failures =
                            new ArrayList<RuntimeException>();
                }
                failures.add(
                        ex);
            }
        }

        return failures == null
                ? DeliveryReport.success(
                        snapshot.length)
                : new DeliveryReport(
                        snapshot.length,
                        failures);
    }

    private static void requireListener(
            Consumer<?> listener) {
        if (listener == null) {
            throw new IllegalArgumentException(
                    "listener must not be null");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Consumer<T> singleListener(
            Object value) {
        return (Consumer<T>) value;
    }

    private static Consumer<?>[] listenerArray(
            Object value) {
        return (Consumer<?>[]) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> Consumer<T> typedListener(
            Consumer<?> listener) {
        return (Consumer<T>) listener;
    }

    /** Immutable outcome of one synchronous emission. */
    public static final class DeliveryReport {
        private static final DeliveryReport NO_LISTENERS =
                new DeliveryReport(
                        0,
                        Collections.<RuntimeException>emptyList());

        private final int attemptedListeners;
        private final List<RuntimeException> failures;

        private DeliveryReport(
                int attemptedListeners,
                List<RuntimeException> failures) {
            this.attemptedListeners =
                    attemptedListeners;
            this.failures =
                    failures.isEmpty()
                            ? Collections.<RuntimeException>emptyList()
                            : Collections.unmodifiableList(
                                    new ArrayList<RuntimeException>(
                                            failures));
        }

        private static DeliveryReport noListeners() {
            return NO_LISTENERS;
        }

        private static DeliveryReport success(
                int attemptedListeners) {
            return new DeliveryReport(
                    attemptedListeners,
                    Collections.<RuntimeException>emptyList());
        }

        private static DeliveryReport failure(
                int attemptedListeners,
                RuntimeException failure) {
            return new DeliveryReport(
                    attemptedListeners,
                    Collections.singletonList(
                            failure));
        }

        /** Number of listeners that were invoked for this emission. */
        public int attemptedListeners() {
            return attemptedListeners;
        }

        /** Number of listeners that threw a RuntimeException. */
        public int failureCount() {
            return failures.size();
        }

        /** Returns true when every invoked listener returned normally. */
        public boolean successful() {
            return failures.isEmpty();
        }

        /** Listener failures in subscription/invocation order. */
        public List<RuntimeException> failures() {
            return failures;
        }
    }
}
