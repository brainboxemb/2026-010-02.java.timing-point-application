package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PropertyContainerTest {

    @Test
    public void commitsSeveralPropertiesBeforePublishingOneChange() {
        PropertyContainer properties = new PropertyContainer();
        Property<String> state = properties.property("state", "CLOSED");
        Property<Integer> location = properties.property("locationId", null);
        List<PropertyContainer.Change> changes = new ArrayList<PropertyContainer.Change>();
        properties.subscribe(changes::add);

        PropertyContainer.Update update = properties.update();
        update.set(location, Integer.valueOf(24));
        update.set(state, "OPEN");

        assertEquals("CLOSED", state.value());
        assertEquals(null, location.value());

        update.commit();

        assertEquals("OPEN", state.value());
        assertEquals(Integer.valueOf(24), location.value());
        assertTrue(changes.isEmpty());

        update.publish();

        assertEquals(1, changes.size());
        assertEquals(2, changes.get(0).properties.size());
        assertTrue(changes.get(0).contains("state"));
        assertTrue(changes.get(0).contains("locationId"));
    }

    @Test
    public void unchangedValuesEmitNothing() {
        PropertyContainer properties = new PropertyContainer();
        Property<String> state = properties.property("state", "CLOSED");
        List<PropertyContainer.Change> changes = new ArrayList<PropertyContainer.Change>();
        properties.subscribe(changes::add);

        PropertyContainer.Update update = properties.update();
        update.set(state, "CLOSED");
        update.commit();
        update.publish();

        assertTrue(changes.isEmpty());
    }

    @Test
    public void finalStagedValueWinsForOneProperty() {
        PropertyContainer properties = new PropertyContainer();
        Property<String> state = properties.property("state", "CLOSED");

        PropertyContainer.Update update = properties.update();
        update.set(state, "OPEN");
        update.set(state, "CLOSED");
        update.commit();
        update.publish();

        assertEquals("CLOSED", state.value());
    }

    @Test
    public void rejectsForeignProperty() {
        PropertyContainer first = new PropertyContainer();
        PropertyContainer second = new PropertyContainer();
        Property<String> state = first.property("state", "CLOSED");

        try {
            second.update().set(state, "OPEN");
            fail("foreign property must be rejected");
        } catch (IllegalArgumentException expected) {
            assertFalse(expected.getMessage().isEmpty());
        }
    }
}
