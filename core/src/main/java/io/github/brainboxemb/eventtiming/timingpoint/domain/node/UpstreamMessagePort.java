package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

/**
 * TimingNode-owned semantic upstream entry point.
 *
 * <p>The port preserves the TimingNode serialization boundary. Application
 * routing may select this port by NodeId, but it does not call TimingNodeLogic
 * directly.</p>
 */
public final class UpstreamMessagePort {
    private final TimingNode timingNode;

    UpstreamMessagePort(TimingNode timingNode) {
        if (timingNode == null) {
            throw new IllegalArgumentException(
                    "timingNode must not be null");
        }
        this.timingNode = timingNode;
    }

    public TimingNodeTypes.RaceDataApplyResult applyRaceData(
            UpstreamData.RaceData raceData) {
        if (raceData == null) {
            throw new IllegalArgumentException(
                    "raceData must not be null");
        }
        return timingNode.invoke(
                TimingNodeCommands.applyRaceData(
                        raceData));
    }

    public long raceDataAppliedSequence() {
        return timingNode.query(
                TimingNodeQueries.raceDataAppliedSequence());
    }
}
