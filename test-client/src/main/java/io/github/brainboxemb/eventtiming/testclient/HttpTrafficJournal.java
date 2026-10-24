package io.github.brainboxemb.eventtiming.testclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Bounded diagnostic history for IF-03 HTTP calls made by the Engineering Client.
 *
 * <p>The journal is transport/context state. JavaFX panes only project references to
 * these immutable call records and do not own the retained HTTP history.</p>
 */
final class HttpTrafficJournal {
    private static final int MAX_HISTORY = 10_000;

    private final Deque<ApiClient.HttpCall> calls =
            new ArrayDeque<>(MAX_HISTORY);
    private final List<Runnable> listeners =
            new CopyOnWriteArrayList<>();

    void record(ApiClient.HttpCall call) {
        if (call == null) {
            throw new IllegalArgumentException("call must not be null");
        }
        synchronized (calls) {
            calls.addLast(call);
            while (calls.size() > MAX_HISTORY) {
                calls.removeFirst();
            }
        }
        notifyListeners();
    }

    List<ApiClient.HttpCall> snapshot() {
        synchronized (calls) {
            return List.copyOf(new ArrayList<>(calls));
        }
    }

    void clear() {
        synchronized (calls) {
            calls.clear();
        }
        notifyListeners();
    }

    void subscribe(Runnable listener) {
        if (listener == null) {
            throw new IllegalArgumentException("listener must not be null");
        }
        listeners.add(listener);
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            listener.run();
        }
    }
}
