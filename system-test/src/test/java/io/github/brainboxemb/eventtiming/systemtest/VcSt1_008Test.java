package io.github.brainboxemb.eventtiming.systemtest;

import org.junit.Test;

/**
 * VC-ST1-008: two TimingSystems, SID-A owning A and SID-B owning B.
 *
 * <p>The two-systems-one-node-each.yml fixture is run as a packaged application.
 * Through IF-03, prove independent operations, physical LogBooks identified
 * by system and node IDs, and recovery after process restart.
 * The shared verifier holds detailed steps and collected evidence.</p>
 */
public final class VcSt1_008Test {
    @Test
    public void verifiesTwoTimingSystemsAndLogBookIsolation() throws Exception {
        MultiTopologyLogBookVerification.verify(
                "VC-ST1-008",
                TestApplicationConfigFactory.Topology.TWO_SYSTEMS_ONE_NODE_EACH);
    }
}
