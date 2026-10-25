package oss.relay.timing.timingpoint.runtime;

import oss.relay.timing.timingpoint.application.TimingNodeProxyComponents;
import oss.relay.timing.timingpoint.domain.system.TimingSystem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static oss.relay.timing.timingpoint.infra.validation.Checks.failIfInvalidArgument;

/** Runtime-only result containing one TimingSystem plus its application-facing node seams. */
final class TimingSystemComposition {
    private final TimingSystem timingSystem;
    private final List<TimingNodeProxyComponents> presentationNodes;

    TimingSystemComposition(
            TimingSystem timingSystem,
            List<TimingNodeProxyComponents> presentationNodes) {

        failIfInvalidArgument(timingSystem != null, "timingSystem must not be null");
        failIfInvalidArgument(
                presentationNodes != null && !presentationNodes.isEmpty(),
                "presentationNodes must not be empty");
        this.timingSystem = timingSystem;
        this.presentationNodes =
                Collections.unmodifiableList(
                        new ArrayList<TimingNodeProxyComponents>(
                                presentationNodes));
    }

    TimingSystem timingSystem() {
        return timingSystem;
    }

    List<TimingNodeProxyComponents> presentationNodes() {
        return presentationNodes;
    }
}
