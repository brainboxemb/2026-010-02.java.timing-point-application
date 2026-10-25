package oss.relay.eventtiming.timingpoint.platform.events;

import oss.relay.eventtiming.timingpoint.platform.environment.MonotonicClock;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EventWithMetricsTest {

    @Test
    public void recordsCompleteDeliveryDurationAndListenerFailures() {
        FakeMonotonicClock clock = new FakeMonotonicClock();
        EventMetrics metrics = new EventMetrics();
        EventWithMetrics<String> event =
                new EventWithMetrics<String>(
                        clock,
                        metrics);

        event.subscribe(
                value -> clock.advanceNanos(7L));
        event.subscribe(
                value -> {
                    clock.advanceNanos(3L);
                    throw new IllegalStateException("expected listener failure");
                });

        Event.DeliveryReport report = event.emit("value");
        EventMetrics.Snapshot snapshot = event.metrics().snapshot();

        assertEquals(1, report.failureCount());
        assertEquals(1L, snapshot.deliveries());
        assertEquals(1L, snapshot.listenerFailures());
        assertEquals(10L, snapshot.totalDeliveryNanos());
        assertEquals(10L, snapshot.maxDeliveryNanos());
    }

    private static final class FakeMonotonicClock implements MonotonicClock {
        private long nowNanos;

        @Override
        public long nowNanos() {
            return nowNanos;
        }

        private void advanceNanos(long nanos) {
            nowNanos += nanos;
        }
    }
}
