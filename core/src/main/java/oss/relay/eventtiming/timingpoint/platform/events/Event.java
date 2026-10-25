package oss.relay.eventtiming.timingpoint.platform.events;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Small typed local event for post-fact notifications.
 *
 * <p>Event deliberately has no hidden executor, topic router or retry queue.
 * {@link #emit(Object)} invokes matching current listeners synchronously, in
 * subscription order, on the emitting thread. A listener that needs slow I/O,
 * retry or network delivery must hand the immutable value to its own bounded
 * execution/delivery mechanism and return quickly.</p>
 *
 * <p>Listeners are wired during application composition, before components are
 * activated. Runtime rewiring is deliberately unsupported: subscribe is not a
 * concurrent runtime operation. Once activation begins, the listener
 * representation is read-only and {@code emit(...)} may safely use it without
 * subscription locks.</p>
 *
 * <p>The internal registry is optimized for the common 0/1-subscriber case:
 * no container is allocated for zero subscriptions, one subscription is stored
 * directly, and an immutable Subscription array is used only when two or more
 * subscriptions are present. This is an implementation detail; EventSource
 * semantics remain 0..N subscribers and callers never select a cardinality mode.</p>
 *
 * <p>Event deliberately does <strong>not</strong> serialize concurrent emits.
 * If two threads call {@code emit(...)} at the same time, the same listener may
 * be invoked concurrently on those two emitting threads. An event owner that
 * requires strict ordering/non-overlap must emit from its own serial execution
 * boundary. Listener implementations must therefore be thread-safe whenever
 * their owning event can be emitted concurrently.</p>
 *
 * <p>Ordinary filter/listener {@link RuntimeException}s are isolated per
 * subscription: later subscriptions are still evaluated, infrastructure logs
 * the failure, and the failures remain available in the returned
 * {@link DeliveryReport}. Fatal {@link Error}s are not swallowed.</p>
 *
 * @param <T> immutable/read-only value delivered to listeners
 */
public final class Event<T> implements EventSource<T> {
    private static final Logger LOG = LoggerFactory.getLogger(Event.class);

    /*
     * Compact composition-built subscription representation:
     *
     * null                -> no subscriptions
     * Subscription<T>     -> exactly one subscription
     * Subscription<?>[]   -> two or more subscriptions in order
     *
     * subscribe replaces this reference only during single-threaded composition.
     * Runtime emit reads the finished representation and never invokes user code
     * under a subscription lock.
     */
    private Object subscriptions;

    /** Subscribes one listener for every emitted value. */
    @Override
    public boolean subscribe(Consumer<T> listener) {
        requireListener(listener);
        return subscribeInternal(null, listener);
    }

    /** Subscribes one listener only for values accepted by the supplied filter. */
    @Override
    public boolean subscribe(
            Predicate<T> filter,
            Consumer<T> listener) {

        requireFilter(filter);
        requireListener(listener);
        return subscribeInternal(filter, listener);
    }

    private boolean subscribeInternal(
            Predicate<T> filter,
            Consumer<T> listener) {

        Subscription<T> subscription =
                new Subscription<T>(
                        filter,
                        listener);

        Object current = subscriptions;
        if (current == null) {
            subscriptions = subscription;
            return true;
        }

        if (current instanceof Subscription) {
            Subscription<T> existing =
                    singleSubscription(
                            current);
            if (existing.sameAs(subscription)) {
                return false;
            }

            subscriptions =
                    new Subscription<?>[] {
                            existing,
                            subscription
                    };
            return true;
        }

        Subscription<?>[] existing =
                subscriptionArray(
                        current);
        for (Subscription<?> candidate : existing) {
            if (candidate.sameAs(subscription)) {
                return false;
            }
        }

        Subscription<?>[] expanded =
                new Subscription<?>[
                        existing.length + 1];
        System.arraycopy(
                existing,
                0,
                expanded,
                0,
                existing.length);
        expanded[existing.length] =
                subscription;
        subscriptions = expanded;
        return true;
    }

    /**
     * Delivers one value to subscriptions that accept it.
     *
     * <p>This method does not throw ordinary filter/listener RuntimeExceptions.
     * They are collected into the returned report so one subscriber cannot
     * prevent later subscribers from seeing the fact. Fatal Errors propagate.</p>
     */
    public DeliveryReport emit(T value) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null");
        }

        Object snapshot = subscriptions;
        if (snapshot == null) {
            return DeliveryReport.noListeners();
        }

        if (snapshot instanceof Subscription) {
            return deliverSingle(
                    singleSubscription(
                            snapshot),
                    value);
        }

        return deliverMany(
                subscriptionArray(
                        snapshot),
                value);
    }

    private DeliveryReport deliverSingle(
            Subscription<T> subscription,
            T value) {

        try {
            if (!subscription.accepts(value)) {
                return DeliveryReport.noListeners();
            }
        } catch (RuntimeException ex) {
            logListenerFailure(subscription.listener, ex);
            return DeliveryReport.failure(1, ex);
        }

        try {
            subscription.listener.accept(value);
            return DeliveryReport.success(1);
        } catch (RuntimeException ex) {
            logListenerFailure(subscription.listener, ex);
            return DeliveryReport.failure(1, ex);
        }
    }

    private DeliveryReport deliverMany(
            Subscription<?>[] snapshot,
            T value) {

        List<RuntimeException> failures = null;
        int attemptedListeners = 0;

        for (Subscription<?> rawSubscription : snapshot) {
            Subscription<T> subscription =
                    typedSubscription(
                            rawSubscription);

            try {
                if (!subscription.accepts(value)) {
                    continue;
                }
            } catch (RuntimeException ex) {
                attemptedListeners++;
                logListenerFailure(subscription.listener, ex);
                if (failures == null) {
                    failures =
                            new ArrayList<RuntimeException>();
                }
                failures.add(ex);
                continue;
            }

            attemptedListeners++;
            try {
                subscription.listener.accept(value);
            } catch (RuntimeException ex) {
                logListenerFailure(subscription.listener, ex);
                if (failures == null) {
                    failures =
                            new ArrayList<RuntimeException>();
                }
                failures.add(ex);
            }
        }

        return failures == null
                ? DeliveryReport.success(attemptedListeners)
                : new DeliveryReport(
                        attemptedListeners,
                        failures);
    }

    /** Reports an unexpected subscriber failure without involving the event producer. */
    private static void logListenerFailure(
            Consumer<?> listener,
            RuntimeException failure) {

        LOG.warn(
                "Event listener {} failed during synchronous delivery",
                listener.getClass().getName(),
                failure);
    }

    private static void requireListener(Consumer<?> listener) {
        if (listener == null) {
            throw new IllegalArgumentException(
                    "listener must not be null");
        }
    }

    private static void requireFilter(Predicate<?> filter) {
        if (filter == null) {
            throw new IllegalArgumentException(
                    "filter must not be null");
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> Subscription<T> singleSubscription(Object value) {
        return (Subscription<T>) value;
    }

    private static Subscription<?>[] subscriptionArray(Object value) {
        return (Subscription<?>[]) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> Subscription<T> typedSubscription(Subscription<?> subscription) {
        return (Subscription<T>) subscription;
    }

    private static final class Subscription<T> {
        private final Predicate<T> filter;
        private final Consumer<T> listener;

        private Subscription(
                Predicate<T> filter,
                Consumer<T> listener) {
            this.filter = filter;
            this.listener = listener;
        }

        private boolean accepts(T value) {
            return filter == null || filter.test(value);
        }

        private boolean sameAs(Subscription<?> other) {
            if (!listener.equals(other.listener)) {
                return false;
            }
            if (filter == null) {
                return other.filter == null;
            }
            return filter.equals(other.filter);
        }
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

        private static DeliveryReport success(int attemptedListeners) {
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

        /** Number of listeners whose filter accepted the value, including filter failures. */
        public int attemptedListeners() {
            return attemptedListeners;
        }

        /** Number of filters/listeners that threw a RuntimeException. */
        public int failureCount() {
            return failures.size();
        }

        /** Returns true when every evaluated/delivered subscription returned normally. */
        public boolean successful() {
            return failures.isEmpty();
        }

        /** Subscription failures in evaluation/invocation order. */
        public List<RuntimeException> failures() {
            return failures;
        }
    }
}
