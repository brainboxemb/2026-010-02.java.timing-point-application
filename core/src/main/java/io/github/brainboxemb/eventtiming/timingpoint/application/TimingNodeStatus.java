package io.github.brainboxemb.eventtiming.timingpoint.application;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.State;
import io.github.brainboxemb.eventtiming.timingpoint.domain.node.TimingNodeTypes.Problem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Transport-independent current TimingNode status used by presentation adapters. */
public final class TimingNodeStatus {
    private final NodeId timingNodeId;
    private final State state;
    private final LocationId locationId;
    private final List<Problem> problems;

    public TimingNodeStatus(
            NodeId timingNodeId,
            State state) {
        this(
                timingNodeId,
                state,
                null,
                Collections.<Problem>emptyList());
    }

    public TimingNodeStatus(
            NodeId timingNodeId,
            State state,
            LocationId locationId) {
        this(
                timingNodeId,
                state,
                locationId,
                Collections.<Problem>emptyList());
    }

    public TimingNodeStatus(
            NodeId timingNodeId,
            State state,
            LocationId locationId,
            List<Problem> problems) {
        if (timingNodeId == null) {
            throw new IllegalArgumentException("timingNodeId must not be null");
        }
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
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
        this.state = state;
        this.locationId = locationId;
        this.problems = Collections.unmodifiableList(
                new ArrayList<Problem>(problems));
    }

    public NodeId timingNodeId() {
        return timingNodeId;
    }

    public State state() {
        return state;
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
