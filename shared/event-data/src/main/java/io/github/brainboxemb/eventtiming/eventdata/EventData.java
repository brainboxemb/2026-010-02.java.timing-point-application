package io.github.brainboxemb.eventtiming.eventdata;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable event-specific reference data used by timing-domain processing.
 *
 * <p>EventData owns stable source/reference relationships. TimingData remains
 * the committed timing-record model and receives an already resolved
 * RegistrationId.</p>
 *
 * <p>The map-backed constructors support event-specific loaded reference data.
 * A profile with deterministic identity rules may subclass this type and
 * override the resolution methods without materialising lookup entries.</p>
 */
public class EventData {

    private static final EventData EMPTY =
            new EventData(
                    Collections.<TagId, RegistrationId>emptyMap(),
                    Collections.<RegistrationId, TeamId>emptyMap());

    private final Map<TagId, RegistrationId> registrationByTag;
    private final Map<RegistrationId, List<TagId>> tagsByRegistration;
    private final Map<RegistrationId, TeamId> teamByRegistration;
    private final Map<TeamId, RegistrationId> registrationByTeam;

    /**
     * Creates an empty base for a deterministic profile implementation.
     */
    protected EventData() {
        this(
                Collections.<TagId, RegistrationId>emptyMap(),
                Collections.<RegistrationId, TeamId>emptyMap());
    }

    /**
     * Creates map-backed EventData without RegistrationId/TeamId relationships.
     */
    public EventData(
            Map<TagId, RegistrationId> registrationByTag) {
        this(
                registrationByTag,
                Collections.<RegistrationId, TeamId>emptyMap());
    }

    /**
     * Creates map-backed EventData with stable tag and team relationships.
     */
    public EventData(
            Map<TagId, RegistrationId> registrationByTag,
            Map<RegistrationId, TeamId> teamByRegistration) {
        if (registrationByTag == null) {
            throw new IllegalArgumentException(
                    "registrationByTag must not be null");
        }
        if (teamByRegistration == null) {
            throw new IllegalArgumentException(
                    "teamByRegistration must not be null");
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

        this.registrationByTag =
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

        Map<RegistrationId, TeamId> byRegistrationTeam =
                new LinkedHashMap<RegistrationId, TeamId>();
        Map<TeamId, RegistrationId> byTeam =
                new LinkedHashMap<TeamId, RegistrationId>();
        for (Map.Entry<RegistrationId, TeamId> entry
                : teamByRegistration.entrySet()) {
            RegistrationId registrationId = entry.getKey();
            TeamId teamId = entry.getValue();
            if (registrationId == null || teamId == null) {
                throw new IllegalArgumentException(
                        "EventData team registrations must not contain null");
            }

            RegistrationId existing =
                    byTeam.put(
                            teamId,
                            registrationId);
            if (existing != null
                    && !existing.equals(registrationId)) {
                throw new IllegalArgumentException(
                        "TeamId "
                                + teamId
                                + " maps to multiple RegistrationIds");
            }
            byRegistrationTeam.put(
                    registrationId,
                    teamId);
        }

        this.teamByRegistration =
                Collections.unmodifiableMap(
                        byRegistrationTeam);
        this.registrationByTeam =
                Collections.unmodifiableMap(
                        byTeam);
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
     * Returns all configured physical tags for one logical registration.
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

    /**
     * Resolves a stable RegistrationId-to-TeamId relationship.
     *
     * @return the TeamId, or {@code null} when stable EventData has no mapping
     */
    public TeamId teamIdFor(
            RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException(
                    "registrationId must not be null");
        }
        return teamByRegistration.get(
                registrationId);
    }

    /**
     * Resolves a stable TeamId-to-RegistrationId relationship.
     *
     * @return the RegistrationId, or {@code null} when stable EventData has no
     *         mapping
     */
    public RegistrationId registrationIdFor(
            TeamId teamId) {
        if (teamId == null) {
            throw new IllegalArgumentException(
                    "teamId must not be null");
        }
        return registrationByTeam.get(
                teamId);
    }

    public boolean isEmpty() {
        return registrationByTag.isEmpty()
                && teamByRegistration.isEmpty();
    }
}
