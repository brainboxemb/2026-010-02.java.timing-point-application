package io.github.brainboxemb.eventtiming.timingdata;

import io.github.brainboxemb.eventtiming.timingdata.defaultprofile.DefaultTimingDataFactory;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

public class TimingDataFactoryTest {
    private static final TimingTimestamp EFFECTIVE =
            TimingTimestamp.parse("2026-09-30T20:01:39.123000000Z");
    private static final TimingTimestamp RECORDED =
            TimingTimestamp.parse("2026-09-30T20:01:40.000000000Z");

    @Test
    public void contextCarriesCommonTimingDataConstructionValues() {
        TimingDataFactory.Context context = context();

        assertEquals(new TimingDataTypes.NodeId("A"), context.timingNodeId());
        assertEquals(7L, context.sequenceNumber());
        assertEquals(new TimingDataTypes.LocationId(12), context.locationId());
        assertSame(EFFECTIVE, context.effectiveTime());
        assertSame(RECORDED, context.recordedAt());
    }

    @Test(expected = IllegalArgumentException.class)
    public void contextRejectsZeroSequence() {
        new TimingDataFactory.Context(new TimingDataTypes.NodeId("A"), 0L, new TimingDataTypes.LocationId(12), EFFECTIVE, RECORDED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void contextRejectsMissingLocationId() {
        new TimingDataFactory.Context(new TimingDataTypes.NodeId("A"), 1L, null, EFFECTIVE, RECORDED);
    }

    @Test(expected = IllegalArgumentException.class)
    public void contextRejectsSequenceAboveJsonSafeRange() {
        new TimingDataFactory.Context(
                new TimingDataTypes.NodeId("A"),
                TimingData.MAX_SEQUENCE_NUMBER + 1L,
                new TimingDataTypes.LocationId(12),
                EFFECTIVE,
                RECORDED);
    }

    @Test
    public void defaultFactoryReturnsTypedAutomaticRegistration() {
        TimingDataFactory.Context context = context();
        TimingDataTypes.RegistrationId registrationId = new TimingDataTypes.RegistrationId("registration-0042");

        TimingData.AutomaticRegistration automatic =
                new DefaultTimingDataFactory()
                        .createAutomaticRegistration(context, registrationId);

        assertCommonFields(automatic);
        assertSame(registrationId, automatic.registrationId());
    }

    @Test
    public void defaultFactoryReturnsTypedManualRegistration() {
        TimingDataFactory.Context context = context();
        TimingDataTypes.RegistrationId registrationId = new TimingDataTypes.RegistrationId("registration-0042");

        TimingData.ManualRegistration manual =
                new DefaultTimingDataFactory().createManualRegistration(
                        context,
                        registrationId,
                        TimingData.ManualTimeSource.OPERATOR_ENTERED);

        assertCommonFields(manual);
        assertSame(registrationId, manual.registrationId());
        assertSame(TimingData.ManualTimeSource.OPERATOR_ENTERED, manual.timeSource());
    }

    @Test
    public void alternateProfileCanReturnDifferentConcreteClassesThroughSameTypedApi() {
        TimingDataFactory.Context context = context();
        TimingDataTypes.RegistrationId registrationId = new TimingDataTypes.RegistrationId("registration-0042");
        TimingDataFactory defaultFactory = new DefaultTimingDataFactory();
        TimingDataFactory dummyFactory = new DummyEventTimingDataFactory();

        TimingData.AutomaticRegistration defaultAutomatic =
                defaultFactory.createAutomaticRegistration(context, registrationId);
        TimingData.AutomaticRegistration dummyAutomatic =
                dummyFactory.createAutomaticRegistration(context, registrationId);

        assertNotEquals(defaultAutomatic.getClass(), dummyAutomatic.getClass());
        assertEquals(defaultAutomatic.timingNodeId(), dummyAutomatic.timingNodeId());
        assertEquals(defaultAutomatic.sequenceNumber(), dummyAutomatic.sequenceNumber());
        assertSame(registrationId, dummyAutomatic.registrationId());
    }

    @Test(expected = IllegalArgumentException.class)
    public void defaultFactoryRejectsMissingManualTimeSource() {
        new DefaultTimingDataFactory().createManualRegistration(
                context(),
                new TimingDataTypes.RegistrationId("registration-0042"),
                null);
    }

    private static TimingDataFactory.Context context() {
        return new TimingDataFactory.Context(
                new TimingDataTypes.NodeId("A"),
                7L,
                new TimingDataTypes.LocationId(12),
                EFFECTIVE,
                RECORDED);
    }

    private static void assertCommonFields(TimingData data) {
        assertEquals(new TimingDataTypes.NodeId("A"), data.timingNodeId());
        assertEquals(7L, data.sequenceNumber());
        assertEquals(new TimingDataTypes.LocationId(12), data.locationId());
        assertSame(EFFECTIVE, data.effectiveTime());
        assertSame(RECORDED, data.recordedAt());
    }

    private static final class DummyEventTimingDataFactory implements TimingDataFactory {
        @Override
        public TimingData.AutomaticRegistration createAutomaticRegistration(
                Context context,
                TimingDataTypes.RegistrationId registrationId) {
            return new DummyAutomaticRegistration(context, registrationId);
        }

        @Override
        public TimingData.ManualRegistration createManualRegistration(
                Context context,
                TimingDataTypes.RegistrationId registrationId,
                TimingData.ManualTimeSource timeSource) {
            return new DummyManualRegistration(context, registrationId, timeSource);
        }
    }

    private static final class DummyAutomaticRegistration
            implements TimingData.AutomaticRegistration {
        private final TimingDataFactory.Context context;
        private final TimingDataTypes.RegistrationId registrationId;

        private DummyAutomaticRegistration(
                TimingDataFactory.Context context,
                TimingDataTypes.RegistrationId registrationId) {
            this.context = context;
            this.registrationId = registrationId;
        }

        @Override
        public TimingDataTypes.NodeId timingNodeId() {
            return context.timingNodeId();
        }

        @Override
        public long sequenceNumber() {
            return context.sequenceNumber();
        }

        @Override
        public TimingDataTypes.LocationId locationId() {
            return context.locationId();
        }

        @Override
        public TimingTimestamp effectiveTime() {
            return context.effectiveTime();
        }

        @Override
        public TimingTimestamp recordedAt() {
            return context.recordedAt();
        }

        @Override
        public TimingDataTypes.RegistrationId registrationId() {
            return registrationId;
        }
    }

    private static final class DummyManualRegistration
            implements TimingData.ManualRegistration {
        private final TimingDataFactory.Context context;
        private final TimingDataTypes.RegistrationId registrationId;
        private final TimingData.ManualTimeSource timeSource;

        private DummyManualRegistration(
                TimingDataFactory.Context context,
                TimingDataTypes.RegistrationId registrationId,
                TimingData.ManualTimeSource timeSource) {
            this.context = context;
            this.registrationId = registrationId;
            this.timeSource = timeSource;
        }

        @Override
        public TimingDataTypes.NodeId timingNodeId() {
            return context.timingNodeId();
        }

        @Override
        public long sequenceNumber() {
            return context.sequenceNumber();
        }

        @Override
        public TimingDataTypes.LocationId locationId() {
            return context.locationId();
        }

        @Override
        public TimingTimestamp effectiveTime() {
            return context.effectiveTime();
        }

        @Override
        public TimingTimestamp recordedAt() {
            return context.recordedAt();
        }

        @Override
        public TimingDataTypes.RegistrationId registrationId() {
            return registrationId;
        }

        @Override
        public TimingData.ManualTimeSource timeSource() {
            return timeSource;
        }
    }
}
