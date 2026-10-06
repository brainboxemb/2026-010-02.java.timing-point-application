package io.github.brainboxemb.eventtiming.timingpoint.domain.eventdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable event-specific reference data used by timing-domain processing.
 *
 * <p>EventData owns source/reference relationships. TimingData remains the
 * committed timing-record model and receives an already resolved
 * RegistrationId.</p>
 */
public final class EventData {

    private static final EventData EMPTY =
            new EventData(
                    Collections.<TagId, RegistrationId>emptyMap());

    private final Map<TagId, RegistrationId> registrationByTag;
    private final Map<RegistrationId, List<TagId>> tagsByRegistration;

    public EventData(
            Map<TagId, RegistrationId> registrationByTag) {
        if (registrationByTag == null) {
            throw new IllegalArgumentException(
                    "registrationByTag must not be null");
        }

        Map<TagId, RegistrationId> byTag =
                new LinkedHashMap<TagId, RegistrationId>();
        Map<RegistrationId, List<TagId>> byRegistration =
                new LinkedHashMap<RegistrationId, List<TagId>>();

        for (Map.Entry<TagId, RegistrationId> entry
                : registrationByTag.entrySet()) {
            TagId tagId = entry.getKey();
            RegistrationId registrationId = entry.getValue();
            if (tagId == null || registrationId == null) {
                throw new IllegalArgumentException(
                        "EventData tag registrations must not contain null");
            }
            byTag.put(
                    tagId,
                    registrationId);

            List<TagId> tags =
                    byRegistration.get(
                            registrationId);
            if (tags == null) {
                tags = new ArrayList<TagId>();
                byRegistration.put(
                        registrationId,
                        tags);
            }
            tags.add(
                    tagId);
        }

        registrationByTag =
                Collections.unmodifiableMap(
                        byTag);

        Map<RegistrationId, List<TagId>> immutableByRegistration =
                new LinkedHashMap<RegistrationId, List<TagId>>();
        for (Map.Entry<RegistrationId, List<TagId>> entry
                : byRegistration.entrySet()) {
            immutableByRegistration.put(
                    entry.getKey(),
                    Collections.unmodifiableList(
                            new ArrayList<TagId>(
                                    entry.getValue())));
        }
        tagsByRegistration =
                Collections.unmodifiableMap(
                        immutableByRegistration);
    }

    public static EventData empty() {
        return EMPTY;
    }

    /**
     * Resolves one semantic tag identity to its canonical registration identity.
     *
     * @return the RegistrationId, or {@code null} when this event does not map
     *         the supplied tag
     */
    public RegistrationId registrationIdFor(
            TagId tagId) {
        if (tagId == null) {
            throw new IllegalArgumentException(
                    "tagId must not be null");
        }
        return registrationByTag.get(
                tagId);
    }

    /**
     * Returns all configured tags for one registration.
     */
    public List<TagId> tagIdsFor(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        List<TagId> tags =
                tagsByRegistration.get(
                        registrationId);
        return tags == null
                ? Collections.<TagId>emptyList()
                : tags;
    }

    public boolean isEmpty() {
        return registrationByTag.isEmpty();
    }
}
