package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class AntennaSettingsTest {
    @Test public void commitsOnlyChangedState() {
        AntennaSettings settings = new AntennaSettings();
        AtomicInteger events = new AtomicInteger();
        settings.changedEvent().subscribe(rev -> events.incrementAndGet());
        settings.update(s -> s.inventoryEnabled(true));
        settings.update(s -> s.inventoryEnabled(true));
        assertTrue(settings.inventoryEnabled());
        assertEquals(1L, settings.revision());
        assertEquals(1, events.get());
    }


    @Test public void nestedTransactionDoesNotCommitPartialState() {
        AntennaSettings settings = new AntennaSettings();
        try {
            settings.update(s -> {
                s.inventoryEnabled(true);
                settings.update(nested -> nested.inventoryEnabled(false));
            });
            fail("nested update should be rejected");
        } catch (IllegalStateException expected) {
            assertFalse(settings.inventoryEnabled());
            assertEquals(0L, settings.revision());
        }
        settings.update(s -> s.inventoryEnabled(true));
        assertTrue(settings.inventoryEnabled());
    }

    @Test public void concurrentTransactionsHaveConsistentPublishedRevisions() throws Exception {
        AntennaSettings settings = new AntennaSettings();
        java.util.concurrent.CountDownLatch editStarted = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread first = new Thread(() -> settings.update(s -> {
            s.inventoryEnabled(true);
            editStarted.countDown();
            try { release.await(); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
        }));
        first.start();
        try {
            assertTrue(editStarted.await(1, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse("unfinished edit must remain invisible",
                    settings.inventoryEnabled());
        } finally {
            release.countDown();
            first.join(1000L);
        }
        assertFalse(first.isAlive());
        assertTrue(settings.inventoryEnabled());
        assertEquals(1L, settings.revision());
    }

    @Test public void exceptionRollsBack() {
        AntennaSettings settings = new AntennaSettings();
        try {
            settings.update(s -> {
                s.inventoryEnabled(true);
                throw new IllegalStateException("rollback");
            });
            fail("expected failure");
        } catch (IllegalStateException expected) {
            assertFalse(settings.inventoryEnabled());
            assertEquals(0L, settings.revision());
        }
    }
}
