package oss.relay.timing.testclient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Reads one stable bounded snapshot of applied StageStartTimes through IF-03. */
final class StageStartTimesReader {
    private static final int MAX_FULL_READ_ATTEMPTS = 3;

    private StageStartTimesReader() {
    }

    static Update read(
            ApiClient api,
            String nodeId,
            ApiClient.StageStartInfo cached)
            throws IOException, InterruptedException {

        if (api == null) {
            throw new IllegalArgumentException("api must not be null");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }

        ApiClient.StageStartInfo current =
                api.getStageStartInfo(nodeId);
        if (cached != null
                && cached.count() == current.count()
                && cached.revision() == current.revision()) {
            return new Update(current, List.of(), false);
        }
        return full(api, nodeId);
    }

    static Update readFull(
            ApiClient api,
            String nodeId)
            throws IOException, InterruptedException {

        if (api == null) {
            throw new IllegalArgumentException("api must not be null");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }
        return full(api, nodeId);
    }

    private static Update full(
            ApiClient api,
            String nodeId)
            throws IOException, InterruptedException {

        for (int attempt = 0;
                attempt < MAX_FULL_READ_ATTEMPTS;
                attempt++) {
            ApiClient.StageStartInfo start =
                    api.getStageStartInfo(nodeId);
            if (start.count() == 0) {
                return new Update(start, List.of(), true);
            }

            List<ApiClient.StageStartRow> rows =
                    new ArrayList<>(start.count());
            int offset = 0;
            boolean stable = true;

            while (rows.size() < start.count()) {
                ApiClient.StageStartPage page =
                        api.getStageStartPage(
                                nodeId,
                                offset,
                                ApiClient.DEFAULT_PAGE_SIZE);

                if (page.info().count() != start.count()
                        || page.info().revision() != start.revision()) {
                    stable = false;
                    break;
                }
                if (page.rows().isEmpty()) {
                    stable = false;
                    break;
                }

                rows.addAll(page.rows());
                if (page.nextOffset() == null) {
                    break;
                }
                offset = page.nextOffset().intValue();
            }

            if (stable && rows.size() == start.count()) {
                return new Update(
                        start,
                        List.copyOf(rows),
                        true);
            }
        }

        throw new IOException(
                "StageStartTimes kept changing during bounded synchronization");
    }

    record Update(
            ApiClient.StageStartInfo info,
            List<ApiClient.StageStartRow> rows,
            boolean replace) {
    }
}
