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
