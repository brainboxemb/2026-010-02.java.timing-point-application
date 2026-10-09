package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DerivedPropertyTest {

    @Test
    public void derivesOneValueFromMultipleSourceProperties() {
        SourceProperty<Object, Boolean> first =
                new SourceProperty<Object, Boolean>(new Object());
        SourceProperty<Object, Boolean> second =
                new SourceProperty<Object, Boolean>(new Object());

        first.update(Boolean.FALSE);
        second.update(Boolean.FALSE);

        DerivedProperty<Boolean> anyEnabled = new DerivedProperty<Boolean>(
                () -> Boolean.valueOf(first.currentValue() || second.currentValue()));

        assertTrue(anyEnabled.recalculate());
        assertFalse(anyEnabled.currentValue());

        second.update(Boolean.TRUE);
        assertTrue(anyEnabled.recalculate());
        assertTrue(anyEnabled.currentValue());

        assertFalse(anyEnabled.recalculate());
    }
}
