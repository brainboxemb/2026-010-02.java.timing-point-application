package oss.relay.eventtiming.timingpoint.infra.property;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable message describing one grouped PropertyContainer update. */
public final class PropertyUpdate {
    public final List<Property<?>> properties;

    PropertyUpdate(List<Property<?>> properties) {
        this.properties =
                Collections.unmodifiableList(
                        new ArrayList<Property<?>>(properties));
    }

    /** Returns whether this update contains the property identified by the supplied key. */
    public boolean contains(PropertyKey<?> key) {
        if (key == null) {
            throw new IllegalArgumentException("property key must not be null");
        }

        for (Property<?> property : properties) {
            if (property.key == key) {
                return true;
            }
        }
        return false;
    }
}
