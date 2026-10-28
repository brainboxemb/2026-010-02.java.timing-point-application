package oss.relay.timing.timingpoint.domain.node.registration;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/** Verifies competition ranking independently from registration projection state. */
public class CompetitionRankingTest {

    @Test
    public void equalElapsedTimesShareCompetitionRank() {
        CompetitionRanking ranking = new CompetitionRanking();

        ranking.begin(4);
        ranking.add(20_000L);
        ranking.add(10_000L);
        ranking.add(10_000L);
        ranking.add(30_000L);
        ranking.complete();

        assertEquals(3, ranking.rankOf(20_000L));
        assertEquals(1, ranking.rankOf(10_000L));
        assertEquals(4, ranking.rankOf(30_000L));
    }

    @Test
    public void slowerRanksCountAllStrictlyFasterEntries() {
        CompetitionRanking ranking = new CompetitionRanking();

        ranking.begin(5);
        ranking.add(5_000L);
        ranking.add(5_000L);
        ranking.add(7_000L);
        ranking.add(9_000L);
        ranking.add(9_000L);
        ranking.complete();

        assertEquals(1, ranking.rankOf(5_000L));
        assertEquals(3, ranking.rankOf(7_000L));
        assertEquals(4, ranking.rankOf(9_000L));
    }

    @Test
    public void sameInstanceCanBeRebuiltForAnotherPopulation() {
        CompetitionRanking ranking = new CompetitionRanking();

        ranking.begin(3);
        ranking.add(10_000L);
        ranking.add(20_000L);
        ranking.add(30_000L);
        ranking.complete();
        assertEquals(3, ranking.rankOf(30_000L));

        ranking.begin(2);
        ranking.add(20_000L);
        ranking.add(30_000L);
        ranking.complete();

        assertEquals(1, ranking.rankOf(20_000L));
        assertEquals(2, ranking.rankOf(30_000L));
    }

    @Test(expected = IllegalStateException.class)
    public void rankIsUnavailableUntilPopulationIsCompleted() {
        CompetitionRanking ranking = new CompetitionRanking();

        ranking.begin(1);
        ranking.add(10_000L);
        ranking.rankOf(10_000L);
    }
}
