package io.github.brainboxemb.eventtiming.systemtest;

import org.junit.Test;

/** VC-ST1-007 — two TimingNodes in one TimingSystem with independent LogBooks. */
public final class VcSt1_007Test {
    @Test
    public void verifiesTwoNodesAndLogBookIsolation()
            throws Exception {
        MultiTopologyLogBookVerification.verify("VC-ST1-007", false);
    }
}
