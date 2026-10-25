package oss.relay.timing.testclient;

import javafx.application.Platform;
import javafx.scene.control.TextArea;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe bridge from diagnostic source callbacks to one JavaFX TextArea.
 * Coalesces concurrent lines and drains a bounded batch per FX turn; it does
 * not throw away source lines or claim to own domain/LogBook records.
 */
final class FxTextAppender {
    private static final int MAX_LINES_PER_PASS = 256;

    private final TextArea area;
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean queued = new AtomicBoolean();

    FxTextAppender(TextArea area) {
        this.area = java.util.Objects.requireNonNull(area);
    }

    void append(String text) {
        if (text == null || text.isEmpty()) return;
        pending.add(text);
        schedule();
    }

    private void schedule() {
        if (queued.compareAndSet(false, true)) {
            Platform.runLater(this::flush);
        }
    }

    private void flush() {
        StringBuilder batch = new StringBuilder();
        for (int i = 0; i < MAX_LINES_PER_PASS; i++) {
            String line = pending.poll();
            if (line == null) break;
            batch.append(line);
        }
        if (!batch.isEmpty()) area.appendText(batch.toString());
        queued.set(false);
        if (!pending.isEmpty()) schedule();
    }
}
