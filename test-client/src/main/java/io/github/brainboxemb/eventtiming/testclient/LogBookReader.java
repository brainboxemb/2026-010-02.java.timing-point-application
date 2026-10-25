package io.github.brainboxemb.eventtiming.testclient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads one complete append-only LogBook snapshot through bounded IF-03 pages. */
final class LogBookReader {
    private static final int PAGE_SIZE = 1000;

    private LogBookReader() {
    }

    static Result readAll(
            ApiClient api,
            String nodeId)
            throws IOException, InterruptedException {

        if (api == null) {
            throw new IllegalArgumentException("api must not be null");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }

        ApiClient.LogBookInfo info = api.getLogBookInfo(nodeId);
        if (info.count() == 0L) {
            return new Result(info, List.of());
        }
        if (info.last() == null) {
            throw new IOException("Non-empty LogBook has no last sequence");
        }

        long targetLast = info.last().longValue();
        long from = info.first() == null ? 1L : info.first().longValue();
        List<ApiClient.LogBookPage> pages = new ArrayList<>();

        while (from <= targetLast) {
            int limit = (int) Math.min(
                    PAGE_SIZE,
                    targetLast - from + 1L);
            ApiClient.LogBookPage page =
                    api.getLogBookFrom(
                            nodeId,
                            from,
                            limit);
            if (page.records().isEmpty()) {
                throw new IOException(
                        "LogBook page was empty before sequence "
                                + targetLast);
            }

            pages.add(page);
            long lastRead =
                    page.records()
                            .get(page.records().size() - 1)
                            .sequenceNumber();
            if (lastRead < from) {
                throw new IOException(
                        "LogBook page did not advance from sequence "
                                + from);
            }
            from = lastRead + 1L;
        }

        return new Result(
                info,
                List.copyOf(pages));
    }

    record Result(
            ApiClient.LogBookInfo info,
            List<ApiClient.LogBookPage> pages) {
    }
}
