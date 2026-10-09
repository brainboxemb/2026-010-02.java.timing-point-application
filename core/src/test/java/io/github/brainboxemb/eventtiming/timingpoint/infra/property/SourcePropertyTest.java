package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SourcePropertyTest {

    @Test
    public void storesSourceAndOnlyCurrentValueState() {
        Object source = new Object();
        SourceProperty<Object, String> property = new SourceProperty<Object, String>(source);

        assertSame(source, property.source());
        assertFalse(property.initialized());

        try {
            property.currentValue();
            fail("expected missing current value");
        } catch (IllegalStateException expected) {
            // Expected before the owner supplies the first authoritative value.
        }

        assertTrue(property.update("A"));
        assertTrue(property.initialized());
        assertEquals("A", property.currentValue());

        assertFalse(property.update("A"));
        assertTrue(property.update("B"));
        assertEquals("B", property.currentValue());
    }
}
