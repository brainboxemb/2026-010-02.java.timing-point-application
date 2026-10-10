package io.github.brainboxemb.eventtiming.testclient;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableView;
import javafx.scene.control.ScrollBar;
import javafx.geometry.Orientation;
import javafx.scene.input.ScrollEvent;

import java.util.List;

/**
 * Adds one blank presentation-only trailing row to a live table.
 * Selecting that row follows new records; inspecting history suspends following.
 * The sentinel never becomes a persisted or domain data object.
 */
final class FollowLatestTable<T> {
    private final TableView<T> table;
    private final ObservableList<T> rows = FXCollections.observableArrayList();
    private boolean following = true;
    private boolean updating;
    private List<T> values = List.of();

    FollowLatestTable(TableView<T> table) {
        this.table = table;
        rows.add(null);
        table.setItems(rows);
        table.getSelectionModel().selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> {
            if (!updating && newIndex.intValue() >= 0) {
                following = newIndex.intValue() == rows.size() - 1;
            }
        });
        table.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaY() > 0 && following) {
                following = false;
                updating = true;
                try {
                    table.getSelectionModel().clearSelection();
                } finally {
                    updating = false;
                }
            }
        });
        Platform.runLater(this::scrollToLatest);
    }

    void update(List<T> newValues) {
        List<T> next = List.copyOf(newValues);
        if (next.equals(values)) return;

        T selected = table.getSelectionModel().getSelectedItem();
        int selectedIndex = table.getSelectionModel().getSelectedIndex();
        boolean append = next.size() >= values.size()
                && next.subList(0, values.size()).equals(values);
        ScrollBar vertical = verticalScrollBar();
        double priorScroll = vertical == null ? -1 : vertical.getValue();
        updating = true;
        try {
            if (append) {
                rows.remove(rows.size() - 1);
                rows.addAll(next.subList(values.size(), next.size()));
                rows.add(null);
            } else {
                rows.setAll(next);
                rows.add(null);
            }
            values = next;
            if (following) {
                table.getSelectionModel().select(rows.size() - 1);
            } else if (selected != null && next.contains(selected)) {
                table.getSelectionModel().select(next.indexOf(selected));
            } else if (selectedIndex >= 0 && selectedIndex < next.size()) {
                table.getSelectionModel().select(selectedIndex);
            }
        } finally {
            updating = false;
        }
        if (following) {
            Platform.runLater(this::scrollToLatest);
        } else if (!append && vertical != null && priorScroll >= 0) {
            // Rebuilding after a revoke/sync must not jump away from history.
            Platform.runLater(() -> vertical.setValue(priorScroll));
        }
    }

    private ScrollBar verticalScrollBar() {
        for (javafx.scene.Node node : table.lookupAll(".scroll-bar")) {
            if (node instanceof ScrollBar bar
                    && bar.getOrientation() == Orientation.VERTICAL) {
                return bar;
            }
        }
        return null;
    }

    private void scrollToLatest() {
        if (!following) return;
        int last = rows.size() - 1;
        updating = true;
        try {
            table.getSelectionModel().select(last);
            table.scrollTo(last);
        } finally {
            updating = false;
        }
    }
}
