package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;

/**
 * Default/reference profile TimingData factory.
 *
 * <p>The concrete default values are private implementation details. Consumers
 * depend on the grouped public semantic contracts under {@link TimingData}.</p>
 */
public final class DefaultTimingDataFactory implements TimingDataFactory {

    @Override
    public TimingData.AutomaticRegistration createAutomaticRegistration(
            Context context,
            RegistrationId registrationId) {
        return new AutomaticRegistration(context, registrationId);
    }

    @Override
    public TimingData.ManualRegistration createManualRegistration(
            Context context,
            RegistrationId registrationId,
            TimingData.ManualTimeSource timeSource) {
        return new ManualRegistration(context, registrationId, timeSource);
    }

    @Override
    public TimingData.NodeOpen createNodeOpen(Context context) {
        return new NodeOpen(context);
    }

    @Override
    public TimingData.NodeClose createNodeClose(Context context) {
        return new NodeClose(context);
    }

    /**
     * Shared immutable implementation of the registration-shaped TimingData
     * envelope used by the default profile.
     *
     * <p>This is deliberately a private implementation detail. The public model
     * remains the two semantic contracts under {@link TimingData}.</p>
     */
    private abstract static class Registration {
        private final Context context;
        private final RegistrationId registrationId;

        private Registration(
                Context context,
                RegistrationId registrationId) {
            this.context =
                    requireContext(
                            context);
            this.registrationId =
                    requireRegistrationId(
                            registrationId);
        }

        public final NodeId timingNodeId() {
            return context.timingNodeId();
        }

        public final long sequenceNumber() {
            return context.sequenceNumber();
        }

        public final LocationId locationId() {
            return context.locationId();
        }

        public final TimingTimestamp effectiveTime() {
            return context.effectiveTime();
        }

        public final TimingTimestamp recordedAt() {
            return context.recordedAt();
        }

        public final RegistrationId registrationId() {
            return registrationId;
        }
    }

    private static final class AutomaticRegistration
            extends Registration
            implements TimingData.AutomaticRegistration {

        private AutomaticRegistration(
                Context context,
                RegistrationId registrationId) {
            super(
                    context,
                    registrationId);
        }
    }

    private abstract static class Lifecycle implements TimingData {
        private final Context context;

        private Lifecycle(Context context) {
            this.context = requireContext(context);
        }

        public final NodeId timingNodeId() {
            return context.timingNodeId();
        }

        public final long sequenceNumber() {
            return context.sequenceNumber();
        }

        public final LocationId locationId() {
            return context.locationId();
        }

        public final TimingTimestamp effectiveTime() {
            return context.effectiveTime();
        }

        public final TimingTimestamp recordedAt() {
            return context.recordedAt();
        }
    }

    private static final class NodeOpen
            extends Lifecycle
            implements TimingData.NodeOpen {
        private NodeOpen(Context context) {
            super(context);
        }
    }

    private static final class NodeClose
            extends Lifecycle
            implements TimingData.NodeClose {
        private NodeClose(Context context) {
            super(context);
        }
    }

    private static final class ManualRegistration
            extends Registration
            implements TimingData.ManualRegistration {

        private final TimingData.ManualTimeSource timeSource;

        private ManualRegistration(
                Context context,
                RegistrationId registrationId,
                TimingData.ManualTimeSource timeSource) {
            super(
                    context,
                    registrationId);

            if (timeSource == null) {
                throw new IllegalArgumentException(
                        "timeSource must not be null");
            }
            this.timeSource = timeSource;
        }

        @Override
        public TimingData.ManualTimeSource timeSource() {
            return timeSource;
        }
    }

    private static Context requireContext(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context must not be null");
        }
        return context;
    }

    private static RegistrationId requireRegistrationId(RegistrationId registrationId) {
        if (registrationId == null) {
            throw new IllegalArgumentException("registrationId must not be null");
        }
        return registrationId;
    }
}
