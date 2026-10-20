package io.github.brainboxemb.eventtiming.timingpoint.domain.node.registration;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Verifies the compact chronological projection of one active WP. */
public class ActiveRegistrationsTest {
    private static final NodeId NODE_ID = new NodeId("A");
    private static final DefaultTimingDataFactory FACTORY = new DefaultTimingDataFactory();

    @Test
    public void activeWpIgnoresOtherLocationsAndRevokeKeepsRowOrder() {
        ActiveRegistrations registrations = activeAt(1);

        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));
        registrations.apply(registration(2L, 1, TimingData.RegistrationAction.ADD));
        assertNull(registrations.apply(registration(3L, 2, TimingData.RegistrationAction.ADD)));
        ActiveRegistrations.Change change =
                registrations.apply(registration(4L, 1, TimingData.RegistrationAction.REV));

        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        assertEquals(2, registrations.visit(10, rows::add));
        assertEquals(1L, rows.get(0).sequenceNumber());
        assertEquals(2L, rows.get(1).sequenceNumber());
        assertTrue(rows.get(0).isValid());
        assertFalse(rows.get(1).isValid());
        assertEquals(new RegistrationId("A0001"), change.registrationId());
    }

    @Test
    public void displayCanPageByOriginalSequenceAfterRevoke() {
        ActiveRegistrations registrations = activeAt(1);
        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));
        registrations.apply(registration(2L, 2, TimingData.RegistrationAction.ADD));
        registrations.apply(registration(3L, 1, TimingData.RegistrationAction.ADD));

        List<ActiveRegistrations.Registration> firstPage = new ArrayList<>();
        assertEquals(1, registrations.visitAfter(0L, 1, firstPage::add));
        assertEquals(1L, firstPage.get(0).sequenceNumber());

        List<ActiveRegistrations.Registration> secondPage = new ArrayList<>();
        assertEquals(1, registrations.visitAfter(1L, 1, secondPage::add));
        assertEquals(3L, secondPage.get(0).sequenceNumber());

        registrations.apply(registration(4L, 1, TimingData.RegistrationAction.REV));
        assertFalse(secondPage.get(0).isValid());
        assertEquals(0, registrations.visitAfter(3L, 1, ignored -> {}));
    }

    @Test
    public void activatingAnotherWpStartsWithEmptyRows() {
        ActiveRegistrations registrations = activeAt(1);
        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));
        assertEquals(1, registrations.visit(10, ignored -> {}));

        registrations.activate(new LocationId(2));
        assertEquals(0, registrations.visit(10, ignored -> {}));
        registrations.apply(registration(2L, 1, TimingData.RegistrationAction.ADD));
        assertEquals(0, registrations.visit(10, ignored -> {}));
        registrations.apply(registration(3L, 2, TimingData.RegistrationAction.ADD));
        assertEquals(1, registrations.visit(10, ignored -> {}));
    }

    @Test
    public void retainedRegistrationUsesOnlyPrimitiveFields() {
        for (Field field : ActiveRegistrations.Registration.class.getDeclaredFields()) {
            assertTrue(
                    "retained field must be primitive: " + field.getName(),
                    field.getType().isPrimitive());
        }
    }

    @Test
    public void semanticValuesAreReconstructedAtReadBoundary() {
        ActiveRegistrations registrations = activeAt(1);
        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));

        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        registrations.visit(1, rows::add);
        ActiveRegistrations.Registration row = rows.get(0);

        assertEquals(1L, row.sequenceNumber());
        assertEquals(new RegistrationId("A0001"), row.registrationId());
        assertEquals(
                Instant.parse("2026-10-13T12:00:00Z"),
                row.effectiveTime().instant());
        assertTrue(row.isValid());
    }

    @Test
    public void unmatchedRevokeDoesNotChangeEarlierEntry() {
        ActiveRegistrations registrations = activeAt(1);
        registrations.apply(registration(1L, 1, TimingData.RegistrationAction.ADD));

        TimingTimestamp different = new TimingTimestamp(Instant.parse("2026-10-13T12:05:00Z"));
        TimingData unmatched = FACTORY.createAutomaticRegistration(
                new TimingDataFactory.Context(NODE_ID, 2L, new LocationId(1), different, different),
                new RegistrationId("A0001"), TimingData.RegistrationAction.REV);

        assertNull(registrations.apply(unmatched));
        List<ActiveRegistrations.Registration> rows = new ArrayList<>();
        registrations.visit(10, rows::add);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).isValid());
    }

    private static ActiveRegistrations activeAt(int location) {

        ActiveRegistrations registrations = new ActiveRegistrations();
        registrations.activate(new LocationId(location));
        return registrations;
    }

    private static TimingData registration(long sequence, int location, TimingData.RegistrationAction action) {
        return FACTORY.createAutomaticRegistration(
                context(sequence, location), new RegistrationId("A0001"), action);
    }

    private static TimingDataFactory.Context context(long sequence, int location) {
        TimingTimestamp time = new TimingTimestamp(Instant.parse("2026-10-13T12:00:00Z"));
        return new TimingDataFactory.Context(NODE_ID, sequence, new LocationId(location), time, time);
    }
}
