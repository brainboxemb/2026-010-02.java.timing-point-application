package io.github.brainboxemb.eventtiming.systemtest;

import org.junit.Test;

/**
 * VC-ST1-007: one TimingSystem SID-9 with TimingNodes A and B.
 *
 * <p>The one-system-two-nodes.yml fixture is run as a packaged application.
 * Through IF-03, prove independent operations, two physical LogBooks with
 * isolated source sequences, and recovery after process restart.
 * The shared verifier holds detailed steps and collected evidence.</p>
 */
public final class VcSt1_007Test {
    @Test
    public void verifiesTwoNodesAndLogBookIsolation() throws Exception {
        MultiTopologyLogBookVerification.verify(
                "VC-ST1-007",
                TestApplicationConfigFactory.Topology.ONE_SYSTEM_TWO_NODES);
    }
}
