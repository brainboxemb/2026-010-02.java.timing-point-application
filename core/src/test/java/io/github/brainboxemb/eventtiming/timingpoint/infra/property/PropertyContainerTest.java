package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PropertyContainerTest {

    @Test
    public void publishesOneEventForSeveralChangedValues() {
        MutableValues values = new MutableValues();
        PropertyContainer properties =
                new PropertyContainer()
                        .track("state", () -> values.state)
                        .track("locationId", () -> values.locationId);
        List<List<Property<?>>> changes = new ArrayList<List<Property<?>>>();
        properties.CHANGED.subscribe(changes::add);

        values.locationId = Integer.valueOf(24);
        values.state = "OPEN";
        properties.update();

        assertEquals(1, changes.size());
        assertEquals(2, changes.get(0).size());
        assertEquals("state", changes.get(0).get(0).name);
        assertEquals("locationId", changes.get(0).get(1).name);
    }

    @Test
    public void unchangedValuesPublishNothing() {
        MutableValues values = new MutableValues();
        PropertyContainer properties =
                new PropertyContainer()
                        .track("state", () -> values.state);
        List<List<Property<?>>> changes = new ArrayList<List<Property<?>>>();
        properties.CHANGED.subscribe(changes::add);

        properties.update();

        assertTrue(changes.isEmpty());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicatePropertyName() {
        MutableValues values = new MutableValues();
        new PropertyContainer()
                .track("state", () -> values.state)
                .track("state", () -> values.state);
    }

    private static final class MutableValues {
        private String state = "CLOSED";
        private Integer locationId;
    }
}
