package io.github.brainboxemb.eventtiming.testclient;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class HttpTrafficJournalTest {
    @Test
    void retainsLatestTenThousandCallsOutsideJavaFxViewState() {
        HttpTrafficJournal journal = new HttpTrafficJournal();
        Instant start = Instant.parse("2026-10-24T18:00:00Z");

        for (int index = 1; index <= 10_001; index++) {
            journal.record(
                    new ApiClient.HttpCall(
                            start.plusMillis(index),
                            "GET",
                            "/call/" + index,
                            null,
                            Integer.valueOf(200),
                            "{}",
                            null));
        }

        assertEquals(10_000, journal.snapshot().size());
        assertEquals("/call/2", journal.snapshot().get(0).path());
        assertEquals("/call/10001", journal.snapshot().get(9_999).path());
    }
}
