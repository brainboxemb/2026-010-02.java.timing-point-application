package oss.relay.timing.testclient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Incrementally reads the active NodeService registration projection.
 *
 * <p>ADD-only growth is appended after the client's last known ADD sequence.
 * A new REV, location change, count regression or ambiguous mutation forces a
 * complete bounded reread because an earlier retained row may have changed.</p>
 */
final class RegistrationProjectionReader {
    private static final int PAGE_SIZE = 1000;
    private static final int MAX_FULL_READ_ATTEMPTS = 3;

    private RegistrationProjectionReader() {
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

    static Update read(
            ApiClient api,
            String nodeId,
            ApiClient.RegistrationProjectionInfo cachedInfo,
            int cachedCount,
            long cachedLastSequence)
            throws IOException, InterruptedException {

        if (api == null) {
            throw new IllegalArgumentException("api must not be null");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId must not be blank");
        }

        ApiClient.RegistrationProjectionInfo current =
                api.getRegistrationProjectionInfo(nodeId);

        if (requiresFullRead(
                cachedInfo,
                cachedCount,
                current)) {
            return full(api, nodeId);
        }

        if (current.count() == cachedCount) {
            return new Update(
                    current,
                    List.of(),
                    false);
        }

        List<ApiClient.RegistrationProjectionRow> appended =
                new ArrayList<>();
        long after = cachedLastSequence;
        ApiClient.RegistrationProjectionInfo latest = current;

        while (cachedCount + appended.size() < latest.count()) {
            ApiClient.RegistrationProjectionPage page =
                    api.getRegistrationProjectionPage(
                            nodeId,
                            after,
                            PAGE_SIZE);

            if (page.info().locationId()
                            != latest.locationId()
                    || page.info().lastRevokeSequence()
                            != cachedInfo.lastRevokeSequence()) {
                return full(api, nodeId);
            }

            latest = page.info();
            if (page.rows().isEmpty()) {
                return full(api, nodeId);
            }

            appended.addAll(page.rows());
            after = page.rows()
                    .get(page.rows().size() - 1)
                    .sequenceNumber();
        }

        if (cachedCount + appended.size()
                != latest.count()) {
            return full(api, nodeId);
        }

        return new Update(
                latest,
                List.copyOf(appended),
                false);
    }

    private static boolean requiresFullRead(
            ApiClient.RegistrationProjectionInfo cached,
            int cachedCount,
            ApiClient.RegistrationProjectionInfo current) {

        if (cached == null) {
            return true;
        }
        if (cached.locationId()
                != current.locationId()) {
            return true;
        }
        if (current.count() < cachedCount) {
            return true;
        }
        if (current.lastMutationSequence()
                < cached.lastMutationSequence()) {
            return true;
        }
        if (current.lastRevokeSequence()
                != cached.lastRevokeSequence()) {
            return true;
        }

        return current.count() == cachedCount
                && current.lastMutationSequence()
                        != cached.lastMutationSequence();
    }

    private static Update full(
            ApiClient api,
            String nodeId)
            throws IOException, InterruptedException {

        for (int attempt = 0;
                attempt < MAX_FULL_READ_ATTEMPTS;
                attempt++) {
            ApiClient.RegistrationProjectionInfo start =
                    api.getRegistrationProjectionInfo(
                            nodeId);
            if (start.count() == 0) {
                return new Update(
                        start,
                        List.of(),
                        true);
            }

            List<ApiClient.RegistrationProjectionRow> rows =
                    new ArrayList<>(start.count());
            long after = 0L;
            boolean stable = true;

            while (rows.size() < start.count()) {
                ApiClient.RegistrationProjectionPage page =
                        api.getRegistrationProjectionPage(
                                nodeId,
                                after,
                                PAGE_SIZE);

                if (!sameVersion(start, page.info())) {
                    stable = false;
                    break;
                }
                if (page.rows().isEmpty()) {
                    stable = false;
                    break;
                }

                rows.addAll(page.rows());
                after = page.rows()
                        .get(page.rows().size() - 1)
                        .sequenceNumber();
            }

            if (stable
                    && rows.size() == start.count()) {
                return new Update(
                        start,
                        List.copyOf(rows),
                        true);
            }
        }

        throw new IOException(
                "Registration projection kept changing during bounded synchronization");
    }

    private static boolean sameVersion(
            ApiClient.RegistrationProjectionInfo first,
            ApiClient.RegistrationProjectionInfo second) {

        return first.locationId()
                        == second.locationId()
                && first.count()
                        == second.count()
                && first.uniqueCount()
                        == second.uniqueCount()
                && first.lastMutationSequence()
                        == second.lastMutationSequence()
                && first.lastRevokeSequence()
                        == second.lastRevokeSequence();
    }

    record Update(
            ApiClient.RegistrationProjectionInfo info,
            List<ApiClient.RegistrationProjectionRow> rows,
            boolean replace) {
    }
}
