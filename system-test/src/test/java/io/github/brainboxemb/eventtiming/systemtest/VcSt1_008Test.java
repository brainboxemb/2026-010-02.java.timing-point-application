package io.github.brainboxemb.eventtiming.systemtest;

import org.junit.Test;

/** VC-ST1-008 — two TimingSystems with independent LogBooks. */
public final class VcSt1_008Test {
    @Test
    public void verifiesTwoTimingSystemsAndLogBookIsolation()
            throws Exception {
        MultiTopologyLogBookVerification.verify("VC-ST1-008", true);
    }
}
