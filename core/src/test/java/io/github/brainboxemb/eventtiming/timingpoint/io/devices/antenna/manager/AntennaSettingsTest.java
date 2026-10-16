package io.github.brainboxemb.eventtiming.timingpoint.io.devices.antenna.manager;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AntennaSettingsTest {
    @Test
    public void commitsOnlyChangedState() {
        AntennaSettings settings = new AntennaSettings();
        AtomicInteger events = new AtomicInteger();
        settings.CHANGED.subscribe(revision -> events.incrementAndGet());

        settings.update(edit -> edit.inventoryEnabled(true));
        settings.update(edit -> edit.inventoryEnabled(true));

        assertTrue(settings.inventoryEnabled());
        assertEquals(1L, settings.revision());
        assertEquals(1, events.get());
    }

    @Test
    public void exceptionRollsBack() {
        AntennaSettings settings = new AntennaSettings();
        try {
            settings.update(edit -> {
                edit.inventoryEnabled(true);
                throw new IllegalStateException("rollback");
            });
            fail("expected transaction failure");
        } catch (IllegalStateException expected) {
            assertFalse(settings.inventoryEnabled());
            assertEquals(0L, settings.revision());
        }
    }

    @Test
    public void nestedTransactionCannotCommitPartialState() {
        AntennaSettings settings = new AntennaSettings();

        try {
            settings.update(edit -> {
                edit.inventoryEnabled(true);
                settings.update(nested -> nested.inventoryEnabled(false));
            });
            fail("nested update must be rejected");
        } catch (IllegalStateException expected) {
            assertFalse(settings.inventoryEnabled());
            assertEquals(0L, settings.revision());
        }

        settings.update(edit -> edit.inventoryEnabled(true));
        assertTrue(settings.inventoryEnabled());
    }

    @Test
    public void concurrentReadersOnlySeeCommittedState() throws Exception {
        AntennaSettings settings = new AntennaSettings();
        CountDownLatch editStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread writer = new Thread(() -> settings.update(edit -> {
            edit.inventoryEnabled(true);
            editStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }));

        ExecutorService readerWorker = Executors.newSingleThreadExecutor();
        writer.start();
        try {
            assertTrue(editStarted.await(1L, TimeUnit.SECONDS));
            CountDownLatch readerStarted = new CountDownLatch(1);
            Future<Boolean> published = readerWorker.submit(() -> {
                readerStarted.countDown();
                return settings.inventoryEnabled();
            });
            assertTrue(readerStarted.await(1L, TimeUnit.SECONDS));
            assertFalse("read cannot complete during an uncommitted transaction",
                    published.isDone());
            release.countDown();
            assertTrue(published.get(1L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            writer.join(1000L);
            readerWorker.shutdownNow();
        }

        assertFalse(writer.isAlive());
        assertTrue(settings.inventoryEnabled());
        assertEquals(1L, settings.revision());
    }
}
