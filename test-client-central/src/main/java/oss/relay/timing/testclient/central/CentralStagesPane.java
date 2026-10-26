package oss.relay.timing.testclient.central;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Central-owned Stage/StageStartTimes inspector. */
final class CentralStagesPane {
    enum Kind {
        ROOT,
        STAGE,
        START_TIMES,
        START_TIME
    }

    record Row(
            Kind kind,
            int stageId,
            String registrationId,
            String start,
            int count) {

        static Row root() {
            return new Row(Kind.ROOT, 0, null, null, 0);
        }

        static Row stage(int stageId) {
            return new Row(Kind.STAGE, stageId, null, null, 0);
        }

        static Row startTimes(int stageId, int count) {
            return new Row(Kind.START_TIMES, stageId, null, null, count);
        }

        static Row startTime(
                int stageId,
                String registrationId,
                String start) {
            return new Row(
                    Kind.START_TIME,
                    stageId,
                    registrationId,
                    start,
                    0);
        }

        @Override
        public String toString() {
            return switch (kind) {
                case ROOT -> "Stages";
                case STAGE -> "Stage " + stageId;
                case START_TIMES -> "StartTimes (" + count + ")";
                case START_TIME -> registrationId + " · " + start;
            };
        }
    }

    private final CentralSystemSimulator simulator;
    private final TreeItem<Row> root =
            new TreeItem<>(Row.root());
    private final TreeView<Row> tree =
            new TreeView<>(root);
    private final VBox pane =
            new VBox(tree);

    private List<CentralSystemSimulator.StageView> snapshot =
            List.of();

    CentralStagesPane(CentralSystemSimulator simulator) {
        if (simulator == null) {
            throw new IllegalArgumentException(
                    "simulator must not be null");
        }
        this.simulator = simulator;

        tree.setShowRoot(false);
        tree.getStyleClass().add("engineering-stage-tree");
        pane.getStyleClass().add("engineering-explorer-pane");
        VBox.setVgrow(tree, Priority.ALWAYS);

        refresh();
    }

    javafx.scene.Node pane() {
        return pane;
    }

    void refresh() {
        List<CentralSystemSimulator.StageView> next =
                simulator.stages();
        if (next.equals(snapshot)) {
            return;
        }

        Set<Integer> expandedStartTimes =
                expandedStartTimeStages();

        root.getChildren().clear();
        for (CentralSystemSimulator.StageView stage : next) {
            TreeItem<Row> stageItem =
                    new TreeItem<>(
                            Row.stage(stage.stageId()));
            stageItem.setExpanded(true);

            TreeItem<Row> startsItem =
                    new TreeItem<>(
                            Row.startTimes(
                                    stage.stageId(),
                                    stage.startTimes().size()));
            startsItem.setExpanded(
                    expandedStartTimes.contains(
                            Integer.valueOf(stage.stageId())));

            for (CentralSystemSimulator.StageStartView start
                    : stage.startTimes()) {
                startsItem.getChildren().add(
                        new TreeItem<>(
                                Row.startTime(
                                        stage.stageId(),
                                        start.registrationId(),
                                        start.start())));
            }

            stageItem.getChildren().add(startsItem);
            root.getChildren().add(stageItem);
        }

        snapshot = next;
    }

    private Set<Integer> expandedStartTimeStages() {
        Set<Integer> result = new HashSet<>();
        for (TreeItem<Row> stage : root.getChildren()) {
            if (stage.getChildren().isEmpty()) {
                continue;
            }
            TreeItem<Row> starts = stage.getChildren().get(0);
            if (starts.isExpanded()) {
                result.add(
                        Integer.valueOf(
                                stage.getValue().stageId()));
            }
        }
        return result;
    }
}
