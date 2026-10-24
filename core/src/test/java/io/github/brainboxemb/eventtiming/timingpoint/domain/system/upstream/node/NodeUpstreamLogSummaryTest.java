package io.github.brainboxemb.eventtiming.timingpoint.domain.system.upstream.node;

import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile.DefaultUpstreamData;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NodeUpstreamLogSummaryTest {
    private static final NodeId NODE_A = new NodeId("A");

    @Test
    public void singleAutomaticRegistrationShowsConcreteTimingDataSemantics() {
        TimingRecord record =
                TimingRecord.createAutomaticRegistration(
                        'A',
                        1190L,
                        2,
                        1_793_013_600_000L,
                        1_793_013_600_100L,
                        new RegistrationId("A0001").packedValue(),
                        TimingRecord.Action.ADD,
                        TimingRecord.TagSource.API,
                        TimingRecord.AutomaticTimeSource.API);
        UpstreamData packet =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        80L,
                        DefaultUpstreamData.createTimingData(
                                Collections.singletonList(record)));

        assertEquals(
                "node=A, packetNr=80, type=TIMING_DATA, seq=1190, "
                        + "recType=AUTO_REG, loc=2, time=2026-10-26T11:20:00Z, "
                        + "regId=A0001, code=ADD, recTime=2026-10-26T11:20:00.1Z",
                NodeUpstream.logSummary(packet));
    }

    @Test
    public void singleManualRegistrationKeepsPersistedManualTimeSourceCode() {
        TimingRecord record =
                TimingRecord.createManualRegistration(
                        'A',
                        1191L,
                        2,
                        1_793_013_600_000L,
                        1_793_013_600_100L,
                        new RegistrationId("A0002").packedValue(),
                        TimingRecord.Action.REV,
                        TimingRecord.ManualTimeSource.OPERATOR_ENTERED);
        UpstreamData packet =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        81L,
                        DefaultUpstreamData.createTimingData(
                                Collections.singletonList(record)));

        assertEquals(
                "node=A, packetNr=81, type=TIMING_DATA, seq=1191, "
                        + "recType=MAN_REG, loc=2, time=2026-10-26T11:20:00Z, "
                        + "regId=A0002, code=REV,MAN, recTime=2026-10-26T11:20:00.1Z",
                NodeUpstream.logSummary(packet));
    }

    @Test
    public void timingDataBatchStaysCompact() {
        TimingRecord first =
                TimingRecord.createNodeOpened(
                        'A', 1192L, 2, 1_793_013_600_000L, 1_793_013_600_100L);
        TimingRecord second =
                TimingRecord.createNodeClosed(
                        'A', 1193L, 2, 1_793_013_601_000L, 1_793_013_601_100L);
        UpstreamData packet =
                DefaultUpstreamData.createUpPacket(
                        NODE_A,
                        82L,
                        DefaultUpstreamData.createTimingData(
                                Arrays.asList(first, second)));

        assertEquals(
                "node=A, packetNr=82, type=TIMING_DATA, seq=1192..1193, records=2",
                NodeUpstream.logSummary(packet));
    }
}
