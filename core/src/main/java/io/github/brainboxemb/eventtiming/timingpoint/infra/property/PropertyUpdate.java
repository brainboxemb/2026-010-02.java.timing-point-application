package io.github.brainboxemb.eventtiming.timingpoint.infra.property;

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
}
