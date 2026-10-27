package oss.relay.timing.timingpoint.domain.system.upstream.node;

import oss.relay.timing.timingpoint.domain.node.service.NodeService;
import oss.relay.timing.timingpoint.platform.execution.tasks.TaskStep;
import oss.relay.timing.timingpoint.platform.execution.tasks.YieldingTask;
import oss.relay.timing.upstreamdata.UpstreamData;

import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Applies one new TEAM_ASSIGNMENTS message through NodeService before confirming its DN_PACKET. */
final class TeamAssignmentsTask implements YieldingTask {
    private final BooleanSupplier available;
    private final DownstreamDelivery delivery;
    private final NodeService nodeService;
    private final UpstreamData packet;
    private final UpstreamData.TeamAssignments assignments;

    private CompletableFuture<Void> applyFuture;

    TeamAssignmentsTask(
            BooleanSupplier available,
            DownstreamDelivery delivery,
            NodeService nodeService,
            UpstreamData packet) {

        failIfInvalidArgument(available != null, "available must not be null");
        failIfInvalidArgument(delivery != null, "delivery must not be null");
        failIfInvalidArgument(nodeService != null, "nodeService must not be null");
        failIfInvalidArgument(packet != null, "packet must not be null");
        failIfInvalidArgument(
                packet.family() == UpstreamData.Family.TEAM_ASSIGNMENTS,
                "packet must carry TEAM_ASSIGNMENTS");
        this.available = available;
        this.delivery = delivery;
        this.nodeService = nodeService;
        this.packet = packet;
        this.assignments = (UpstreamData.TeamAssignments) packet.message();
    }

    @Override
    public TaskStep runStep() {

        if (applyFuture == null) {
            if (!available.getAsBoolean()) {
                delivery.cancelPending();
                return TaskStep.done();
            }

            applyFuture = nodeService.applyTeamAssignments(assignments);
            if (applyFuture == null) {
                delivery.cancelPending();
                throw new IllegalStateException(
                        "NodeService returned no TEAM_ASSIGNMENTS completion");
            }
            return TaskStep.await(applyFuture);
        }

        applyFuture.join();
        delivery.complete(packet);
        return TaskStep.done();
    }
}
