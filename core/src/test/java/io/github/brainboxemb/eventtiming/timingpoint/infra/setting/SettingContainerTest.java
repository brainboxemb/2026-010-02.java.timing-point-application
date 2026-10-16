package io.github.brainboxemb.eventtiming.timingpoint.infra.setting;

import java.util.ArrayList;
import java.util.List;
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

public class SettingContainerTest {
    private static final SettingKey<Boolean> ENABLED =
            SettingKey.of("enabled");
    private static final SettingKey<Integer> LEVEL =
            SettingKey.of("level");

    @Test
    public void commitsOneGroupedUpdateForChangedSettings() {
        TestSettings settings = new TestSettings();
        List<SettingUpdate> updates = new ArrayList<SettingUpdate>();
        settings.CHANGED.subscribe(updates::add);

        settings.update(edit -> {
            edit.set(ENABLED, Boolean.TRUE);
            edit.set(LEVEL, Integer.valueOf(2));
        });

        assertEquals(Boolean.TRUE, settings.requestedValue(ENABLED));
        assertEquals(Integer.valueOf(2), settings.requestedValue(LEVEL));
        assertEquals(1L, settings.revision());
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).contains(ENABLED));
        assertTrue(updates.get(0).contains(LEVEL));
        assertEquals(1L, updates.get(0).revision);
    }

    @Test
    public void unchangedSettingsPublishNothing() {
        TestSettings settings = new TestSettings();
        AtomicInteger events = new AtomicInteger();
        settings.CHANGED.subscribe(update -> events.incrementAndGet());

        settings.update(edit -> edit.set(ENABLED, Boolean.FALSE));

        assertEquals(0L, settings.revision());
        assertEquals(0, events.get());
    }

    @Test
    public void exceptionRollsBackEntireUpdate() {
        TestSettings settings = new TestSettings();

        try {
            settings.update(edit -> {
                edit.set(ENABLED, Boolean.TRUE);
                edit.set(LEVEL, Integer.valueOf(2));
                throw new IllegalStateException("rollback");
            });
            fail("expected transaction failure");
        } catch (IllegalStateException expected) {
            assertEquals(Boolean.FALSE, settings.requestedValue(ENABLED));
            assertEquals(Integer.valueOf(0), settings.requestedValue(LEVEL));
            assertEquals(0L, settings.revision());
        }
    }

    @Test
    public void nestedUpdateIsRejectedWithoutPartialCommit() {
        TestSettings settings = new TestSettings();

        try {
            settings.update(edit -> {
                edit.set(ENABLED, Boolean.TRUE);
                settings.update(nested -> nested.set(LEVEL, Integer.valueOf(3)));
            });
            fail("nested update must be rejected");
        } catch (IllegalStateException expected) {
            assertEquals(Boolean.FALSE, settings.requestedValue(ENABLED));
            assertEquals(Integer.valueOf(0), settings.requestedValue(LEVEL));
            assertEquals(0L, settings.revision());
        }
    }

    @Test
    public void readersOnlySeeCommittedRequestedValues() throws Exception {
        TestSettings settings = new TestSettings();
        CountDownLatch editStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Thread writer = new Thread(() -> settings.update(edit -> {
            edit.set(ENABLED, Boolean.TRUE);
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
                return settings.requestedValue(ENABLED);
            });
            assertTrue(readerStarted.await(1L, TimeUnit.SECONDS));
            assertFalse("read cannot complete during an uncommitted update", published.isDone());
            release.countDown();
            assertTrue(published.get(1L, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            writer.join(1000L);
            readerWorker.shutdownNow();
        }

        assertFalse(writer.isAlive());
        assertEquals(1L, settings.revision());
    }

    private static final class TestSettings extends SettingContainer {
        private TestSettings() {
            setting(ENABLED, Boolean.FALSE);
            setting(LEVEL, Integer.valueOf(0));
        }
    }
}
