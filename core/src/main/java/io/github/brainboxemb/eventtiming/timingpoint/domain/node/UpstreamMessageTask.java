package io.github.brainboxemb.eventtiming.timingpoint.domain.node;

import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * One inbound upstream message admitted to the owning TimingNode serial lane.
 *
 * <p>The task owns no executor, queue or thread. TimingNode supplies execution
 * and ordering; UpstreamMessagePort supplies semantic handling.</p>
 */
final class UpstreamMessageTask
        implements Callable<List<UpstreamData>> {
    private final UpstreamMessagePort port;
    private final UpstreamData.NodeScoped message;

    UpstreamMessageTask(
            UpstreamMessagePort port,
            UpstreamData.NodeScoped message) {
        if (port == null) {
            throw new IllegalArgumentException(
                    "port must not be null");
        }
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null");
        }
        this.port = port;
        this.message = message;
    }

    @Override
    public List<UpstreamData> call() {
        return port.handleOnNodeLane(
                message);
    }
}
