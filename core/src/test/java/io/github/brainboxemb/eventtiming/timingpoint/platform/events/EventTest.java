package io.github.brainboxemb.eventtiming.timingpoint.platform.events;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EventTest {

    @Test
    public void emitsInSubscriptionOrderAndAvoidsDuplicateSubscription() {
        Event<String> event = new Event<>();
        List<String> delivered = new ArrayList<>();

        Consumer<String> first = value -> delivered.add("first:" + value);
        Consumer<String> second = value -> delivered.add("second:" + value);

        assertTrue(event.subscribe(first));
        assertFalse(event.subscribe(first));
        assertTrue(event.subscribe(second));

        Event.DeliveryReport report = event.emit("value");

        assertTrue(report.successful());
        assertEquals(2, report.attemptedListeners());
        assertEquals(
                Arrays.asList("first:value", "second:value"),
                delivered);
    }

    @Test
    public void transitionsFromZeroToOneToManyAndBack()
            throws Exception {
        Event<String> event =
                new Event<String>();
        List<String> delivered =
                new ArrayList<String>();

        Consumer<String> first =
                value -> delivered.add(
                        "first:" + value);
        Consumer<String> second =
                value -> delivered.add(
                        "second:" + value);
        Consumer<String> third =
                value -> delivered.add(
                        "third:" + value);

        assertEquals(
                0,
                event.emit("zero")
                        .attemptedListeners());

        assertTrue(event.subscribe(first));
        assertEquals(
                1,
                event.emit("one")
                        .attemptedListeners());

        assertTrue(event.subscribe(second));
        assertTrue(event.subscribe(third));
        assertEquals(
                3,
                event.emit("many")
                        .attemptedListeners());

        assertTrue(event.unsubscribe(second));
        assertEquals(
                2,
                event.emit("two")
                        .attemptedListeners());

        assertTrue(event.unsubscribe(first));
        assertEquals(
                1,
                event.emit("back-to-one")
                        .attemptedListeners());

        assertTrue(event.unsubscribe(third));
        assertEquals(
                0,
                event.emit("back-to-zero")
                        .attemptedListeners());

        assertEquals(
                Arrays.asList(
                        "first:one",
                        "first:many",
                        "second:many",
                        "third:many",
                        "first:two",
                        "third:two",
                        "third:back-to-one"),
                delivered);
    }

    @Test
    public void emitUsesStableSnapshotWhileSubscriptionChanges()
            throws Exception {
        Event<String> event =
                new Event<String>();
        List<String> delivered =
                java.util.Collections.synchronizedList(
                        new ArrayList<String>());
        CountDownLatch firstEntered =
                new CountDownLatch(1);
        CountDownLatch releaseFirst =
                new CountDownLatch(1);

        Consumer<String> first =
                value -> {
                    delivered.add(
                            "first:" + value);
                    firstEntered.countDown();
                    try {
                        releaseFirst.await();
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(
                                ex);
                    }
                };
        Consumer<String> second =
                value -> delivered.add(
                        "second:" + value);
        Consumer<String> later =
                value -> delivered.add(
                        "later:" + value);

        event.subscribe(first);
        event.subscribe(second);

        Thread emit =
                new Thread(
                        () -> event.emit(
                                "initial"),
                        "event-snapshot-test");
        emit.start();

        assertTrue(
                firstEntered.await(
                        1,
                        TimeUnit.SECONDS));

        /*
         * The in-flight emission already owns its immutable snapshot. Removing
         * second and adding later therefore affect only the next emission.
         */
        assertTrue(event.unsubscribe(second));
        assertTrue(event.subscribe(later));

        releaseFirst.countDown();
        emit.join(1000L);

        assertFalse(emit.isAlive());
        assertEquals(
                Arrays.asList(
                        "first:initial",
                        "second:initial"),
                delivered);

        delivered.clear();
        event.emit(
                "next");

        assertEquals(
                Arrays.asList(
                        "first:next",
                        "later:next"),
                delivered);
    }

    @Test
    public void unsubscribeStopsLaterDelivery() {
        Event<String> event = new Event<>();
        List<String> delivered = new ArrayList<>();
        Consumer<String> listener = delivered::add;

        event.subscribe(listener);
        assertTrue(event.unsubscribe(listener));
        assertFalse(event.unsubscribe(listener));

        Event.DeliveryReport report = event.emit("value");

        assertEquals(0, report.attemptedListeners());
        assertTrue(delivered.isEmpty());
    }

    @Test
    public void runtimeFailureIsReportedAndDoesNotBlockLaterListener() {
        Event<String> event = new Event<>();
        List<String> delivered = new ArrayList<>();
        RuntimeException failure = new IllegalStateException("expected listener failure");

        event.subscribe(value -> {
            throw failure;
        });
        event.subscribe(delivered::add);

        Event.DeliveryReport report = event.emit("value");

        assertFalse(report.successful());
        assertEquals(2, report.attemptedListeners());
        assertEquals(1, report.failureCount());
        assertEquals(failure, report.failures().get(0));
        assertEquals(Arrays.asList("value"), delivered);
    }

    @Test
    public void subscriptionOnlyViewUsesSameThreadSafeRegistry() {
        Event<String> event = new Event<>();
        EventSource<String> source = event;
        List<String> delivered = new ArrayList<>();

        assertTrue(source.subscribe(delivered::add));
        event.emit("value");

        assertEquals(Arrays.asList("value"), delivered);
    }

    @Test
    public void concurrentEmitsAreNotSerializedByEvent() throws Exception {
        Event<String> event = new Event<>();
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        event.subscribe(value -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        });

        Thread first = new Thread(() -> event.emit("first"), "event-test-first");
        Thread second = new Thread(() -> event.emit("second"), "event-test-second");
        first.start();
        second.start();

        try {
            assertTrue(
                    "both emits should enter the listener concurrently",
                    entered.await(1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            first.join(1000);
            second.join(1000);
        }

        assertFalse(first.isAlive());
        assertFalse(second.isAlive());
    }

    @Test
    public void fatalErrorIsNotSwallowed() {
        Event<String> event = new Event<>();
        AssertionError fatal = new AssertionError("expected fatal");
        event.subscribe(value -> {
            throw fatal;
        });

        try {
            event.emit("value");
            fail("expected fatal Error");
        } catch (AssertionError expected) {
            assertEquals(fatal, expected);
        }
    }
}
