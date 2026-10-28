package oss.relay.timing.timingpoint.domain.node.registration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies competition ranking without active-WP lifecycle or LogBook replay. */
public class CompetitionRankingTest {

    @Test
    public void equalElapsedTimesShareCompetitionRank() {
        CompetitionRanking ranking = new CompetitionRanking();
        long start =
                StageTiming.compactUtc(
                        Instant.parse("2026-10-13T12:00:00Z"));
        List<ActiveRegistrations.Registration> rows =
                new ArrayList<ActiveRegistrations.Registration>();
        rows.add(registration(1L, "2026-10-13T12:00:20Z", start));
        rows.add(registration(2L, "2026-10-13T12:00:10Z", start));
        rows.add(registration(3L, "2026-10-13T12:00:10Z", start));
        rows.add(registration(4L, "2026-10-13T12:00:30Z", start));

        ranking.recalculate(rows);

        assertEquals(3, rows.get(0).rank());
        assertEquals(1, rows.get(1).rank());
        assertEquals(1, rows.get(2).rank());
        assertEquals(4, rows.get(3).rank());
    }

    @Test
    public void invalidRowsDoNotParticipateAndReceiveRankZero() {
        CompetitionRanking ranking = new CompetitionRanking();
        long start =
                StageTiming.compactUtc(
                        Instant.parse("2026-10-13T12:00:00Z"));
        List<ActiveRegistrations.Registration> rows =
                new ArrayList<ActiveRegistrations.Registration>();
        ActiveRegistrations.Registration invalid =
                registration(1L, "2026-10-13T12:00:05Z", start);
        ActiveRegistrations.Registration first =
                registration(2L, "2026-10-13T12:00:10Z", start);
        ActiveRegistrations.Registration second =
                registration(3L, "2026-10-13T12:00:20Z", start);
        invalid.invalidate();
        rows.add(invalid);
        rows.add(first);
        rows.add(second);

        ranking.recalculate(rows);

        assertEquals(0, invalid.rank());
        assertEquals(1, first.rank());
        assertEquals(2, second.rank());
    }

    @Test
    public void missingElapsedTimeDoesNotParticipate() {
        CompetitionRanking ranking = new CompetitionRanking();
        long start =
                StageTiming.compactUtc(
                        Instant.parse("2026-10-13T12:00:00Z"));
        List<ActiveRegistrations.Registration> rows =
                new ArrayList<ActiveRegistrations.Registration>();
        ActiveRegistrations.Registration missing =
                registration(
                        1L,
                        "2026-10-13T12:00:00Z",
                        Long.MIN_VALUE);
        ActiveRegistrations.Registration ranked =
                registration(
                        2L,
                        "2026-10-13T12:00:10Z",
                        start);
        rows.add(missing);
        rows.add(ranked);

        ranking.recalculate(rows);

        assertEquals(0, missing.rank());
        assertEquals(1, ranked.rank());
    }

    @Test
    public void repeatedRecalculationReplacesPreviousRanks() {
        CompetitionRanking ranking = new CompetitionRanking();
        long start =
                StageTiming.compactUtc(
                        Instant.parse("2026-10-13T12:00:00Z"));
        List<ActiveRegistrations.Registration> rows =
                new ArrayList<ActiveRegistrations.Registration>();
        ActiveRegistrations.Registration first =
                registration(1L, "2026-10-13T12:00:10Z", start);
        ActiveRegistrations.Registration second =
                registration(2L, "2026-10-13T12:00:20Z", start);
        rows.add(first);
        rows.add(second);

        ranking.recalculate(rows);
        assertEquals(1, first.rank());
        assertEquals(2, second.rank());

        first.invalidate();
        ranking.recalculate(rows);

        assertEquals(0, first.rank());
        assertEquals(1, second.rank());
    }

    private static ActiveRegistrations.Registration registration(
            long sequence,
            String passage,
            long stageStart) {

        return new ActiveRegistrations.Registration(
                sequence,
                Instant.parse(passage).toEpochMilli(),
                1,
                stageStart);
    }
}
