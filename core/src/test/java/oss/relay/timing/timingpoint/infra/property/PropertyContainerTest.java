package oss.relay.timing.timingpoint.infra.property;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PropertyContainerTest {

    @Test
    public void publishesOneEventForSeveralChangedProperties() {
        PropertyContainer properties = new PropertyContainer();
        Property<String> state =
                properties.property("state", "CLOSED");
        Property<Integer> location =
                properties.property("locationId", null);
        List<PropertyUpdate> changes =
                new ArrayList<PropertyUpdate>();
        properties.CHANGED.subscribe(changes::add);

        location.set(Integer.valueOf(24));
        state.set("OPEN");
        properties.update();

        assertEquals("OPEN", state.get());
        assertEquals(Integer.valueOf(24), location.get());
        assertEquals(1, changes.size());
        assertEquals(2, changes.get(0).properties.size());
        assertEquals("state", changes.get(0).properties.get(0).name);
        assertEquals("locationId", changes.get(0).properties.get(1).name);
    }

    @Test
    public void propertyUpdateContainsTypedChangedKey() {
        PropertyContainer properties = new PropertyContainer();
        PropertyKey<String> stateKey = PropertyKey.of("state");
        PropertyKey<Integer> locationKey = PropertyKey.of("locationId");
        Property<String> state = properties.property(stateKey, "CLOSED");
        properties.property(locationKey, null);
        List<PropertyUpdate> changes = new ArrayList<PropertyUpdate>();
        properties.CHANGED.subscribe(changes::add);

        state.set("OPEN");
        properties.update();

        assertEquals(1, changes.size());
        assertTrue(changes.get(0).contains(stateKey));
        assertTrue(!changes.get(0).contains(locationKey));
    }

    @Test
    public void unchangedPropertiesPublishNothing() {
        PropertyContainer properties = new PropertyContainer();
        properties.property("state", "CLOSED");
        List<PropertyUpdate> changes =
                new ArrayList<PropertyUpdate>();
        properties.CHANGED.subscribe(changes::add);

        properties.update();

        assertTrue(changes.isEmpty());
    }

    @Test
    public void syncAcceptsCurrentValuesWithoutPublishing() {
        PropertyContainer properties = new PropertyContainer();
        Property<String> state =
                properties.property("state", "CLOSED");
        List<PropertyUpdate> changes =
                new ArrayList<PropertyUpdate>();
        properties.CHANGED.subscribe(changes::add);

        state.set("OPEN");
        properties.sync();
        properties.update();

        assertTrue(changes.isEmpty());
        assertEquals("OPEN", state.get());
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsDuplicatePropertyName() {
        PropertyContainer properties = new PropertyContainer();
        properties.property("state", "CLOSED");
        properties.property("state", "OPEN");
    }
}
