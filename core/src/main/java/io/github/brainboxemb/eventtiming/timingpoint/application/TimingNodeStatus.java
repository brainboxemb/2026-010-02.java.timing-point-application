package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Lifecycle;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Problem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transport-independent current TimingNode status used by presentation adapters. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final Lifecycle lifecycle;
    private final LocationId locationId;
    private final List<Problem> problems;

    public TimingNodeStatus(
            NodeId timingNodeId,
            Lifecycle lifecycle) {
        this(
                timingNodeId,
                lifecycle,
                null,
                Collections.<Problem>emptyList());
    }

    public TimingNodeStatus(
            NodeId timingNodeId,
            Lifecycle lifecycle,
            LocationId locationId) {
        this(
                timingNodeId,
                lifecycle,
                locationId,
                Collections.<Problem>emptyList());
    }

    public TimingNodeStatus(
            NodeId timingNodeId,
            Lifecycle lifecycle,
            LocationId locationId,
            List<Problem> problems) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        if (lifecycle == null) {
            throw new IllegalArgumentException("lifecycle must not be null");
        }
        if (problems == null) {
            throw new IllegalArgumentException("problems must not be null");
        }
        for (Problem problem : problems) {
            if (problem == null) {
                throw new IllegalArgumentException("problem must not be null");
            }
        }
        this.timingNodeId = timingNodeId;
        this.lifecycle = lifecycle;
        this.locationId = locationId;
        this.problems = Collections.unmodifiableList(
                new ArrayList<Problem>(problems));
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public boolean hasLocation() {
        return locationId != null;
    }

    public LocationId locationId() {
        return locationId;
    }

    public List<Problem> problems() {
        return problems;
    }

    public boolean hasProblems() {
        return !problems.isEmpty();
    }
}
