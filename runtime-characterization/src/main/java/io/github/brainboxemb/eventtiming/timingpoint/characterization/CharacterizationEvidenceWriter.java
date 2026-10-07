package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Writes one compact machine-readable SDE-04 evidence summary. */
final class CharacterizationEvidenceWriter {
    private final JsonFactory jsonFactory =
            new JsonFactory();

    Path write(
            CharacterizationRunResult result)
            throws IOException {
        Files.createDirectories(
                result.options()
                        .evidenceDirectory());

        String fileName =
                result.startedAt()
                        .toString()
                        .replace(':', '-')
                        + "-"
                        + result.options()
                                .workload()
                                .name()
                                .toLowerCase()
                        + "-r"
                        + result.repetition()
                        + ".json";
        Path file =
                result.options()
                        .evidenceDirectory()
                        .resolve(fileName);

        try (JsonGenerator json =
                jsonFactory.createGenerator(
                        Files.newOutputStream(file))) {
            json.useDefaultPrettyPrinter();
            json.writeStartObject();

            writeIdentity(
                    json,
                    result);
            writeEnvironment(
                    json,
                    result);
            writeWorkload(
                    json,
                    result);
            writeMeasurements(
                    json,
                    result);

            json.writeStringField(
                    "startedAt",
                    result.startedAt().toString());
            json.writeStringField(
                    "finishedAt",
                    result.finishedAt().toString());
            json.writeStringField(
                    "outcome",
                    "PASS");

            json.writeEndObject();
        }

        return file;
    }

    private static void writeIdentity(
            JsonGenerator json,
            CharacterizationRunResult result)
            throws IOException {
        HarnessBuildIdentity build =
                result.build();

        json.writeObjectFieldStart(
                "build");
        json.writeStringField(
                "version",
                build.version());
        json.writeStringField(
                "revision",
                build.revision());
        json.writeStringField(
                "sourceRef",
                build.sourceRef());
        json.writeStringField(
                "origin",
                build.origin());
        json.writeBooleanField(
                "dirty",
                build.dirty());
        json.writeEndObject();
    }

    private static void writeEnvironment(
            JsonGenerator json,
            CharacterizationRunResult result)
            throws IOException {
        json.writeObjectFieldStart(
                "environment");
        json.writeStringField(
                "javaVendor",
                System.getProperty("java.vendor"));
        json.writeStringField(
                "javaVersion",
                System.getProperty("java.version"));
        json.writeStringField(
                "osName",
                System.getProperty("os.name"));
        json.writeStringField(
                "osVersion",
                System.getProperty("os.version"));
        json.writeStringField(
                "osArch",
                System.getProperty("os.arch"));
        json.writeStringField(
                "threadStackOption",
                threadStackOption());

        JvmRuntimeSnapshot before =
                result.jvmBefore();
        JvmRuntimeSnapshot after =
                result.jvmAfter();
        json.writeNumberField(
                "heapUsedBeforeBytes",
                before.heapUsedBytes());
        json.writeNumberField(
                "heapUsedAfterBytes",
                after.heapUsedBytes());
        json.writeNumberField(
                "gcCollectionCountDelta",
                delta(
                        before.gcCollectionCount(),
                        after.gcCollectionCount()));
        json.writeNumberField(
                "gcCollectionTimeMillisDelta",
                delta(
                        before.gcCollectionTimeMillis(),
                        after.gcCollectionTimeMillis()));
        json.writeNumberField(
                "liveThreadsBefore",
                before.liveThreadCount());
        json.writeNumberField(
                "liveThreadsAfter",
                after.liveThreadCount());
        json.writeNumberField(
                "roleWorkerCpuNanosDelta",
                delta(
                        before.roleWorkerCpuTimeNanos(),
                        after.roleWorkerCpuTimeNanos()));
        json.writeEndObject();
    }

    private static void writeWorkload(
            JsonGenerator json,
            CharacterizationRunResult result)
            throws IOException {
        CharacterizationOptions options =
                result.options();

        json.writeObjectFieldStart(
                "workload");
        json.writeStringField(
                "name",
                options.workload().name().toLowerCase() + "-v1");
        json.writeNumberField(
                "deterministicSeed",
                1L);
        json.writeNumberField(
                "timingNodeCount",
                1);
        json.writeNumberField(
                "locationId",
                24);
        json.writeNumberField(
                "observations",
                options.observations());
        json.writeNumberField(
                "aggregateRatePerSecond",
                options.ratePerSecond());
        json.writeNumberField(
                "warmupObservations",
                options.warmupObservations());
        json.writeNumberField(
                "preloadedHistory",
                options.preloadedHistory());
        json.writeNumberField(
                "timingNodeQueueCapacity",
                32);
        json.writeStringField(
                "persistenceMode",
                "file");
        json.writeNumberField(
                "repetition",
                result.repetition());
        json.writeEndObject();
    }

    private static void writeMeasurements(
            JsonGenerator json,
            CharacterizationRunResult result)
            throws IOException {
        TimingNodeRuntimeSnapshot beforeNode =
                result.nodeBefore();
        TimingNodeRuntimeSnapshot afterNode =
                result.nodeAfter();

        json.writeObjectFieldStart(
                "measurements");
        json.writeNumberField(
                "measuredDurationNanos",
                result.measuredDurationNanos());
        json.writeNumberField(
                "finalTimingDataCount",
                result.finalTimingDataCount());
        json.writeNumberField(
                "historyQueryNanos",
                result.historyQueryNanos());

        json.writeObjectFieldStart(
                "timingNode");
        json.writeNumberField(
                "queueDepthAfter",
                afterNode.queueDepth());
        json.writeNumberField(
                "queueHighWaterAfter",
                afterNode.queueHighWaterMark());
        json.writeNumberField(
                "queueAcceptedDelta",
                afterNode.queueAcceptedCount()
                        - beforeNode.queueAcceptedCount());
        json.writeNumberField(
                "queueFullDelta",
                afterNode.queueFullCount()
                        - beforeNode.queueFullCount());
        json.writeNumberField(
                "queueNotRunningDelta",
                afterNode.queueNotRunningCount()
                        - beforeNode.queueNotRunningCount());
        json.writeNumberField(
                "queueCompletedDelta",
                afterNode.queueCompletedCount()
                        - beforeNode.queueCompletedCount());
        json.writeNumberField(
                "queueWaitNanosDelta",
                afterNode.totalQueueWaitNanos()
                        - beforeNode.totalQueueWaitNanos());
        json.writeNumberField(
                "maxQueueWaitNanos",
                afterNode.maxQueueWaitNanos());
        json.writeNumberField(
                "executionNanosDelta",
                afterNode.totalExecutionNanos()
                        - beforeNode.totalExecutionNanos());
        json.writeNumberField(
                "maxExecutionNanos",
                afterNode.maxExecutionNanos());
        json.writeNumberField(
                "appendAttemptsDelta",
                afterNode.timingDataAppendAttempts()
                        - beforeNode.timingDataAppendAttempts());
        json.writeNumberField(
                "appendFailuresDelta",
                afterNode.timingDataAppendFailures()
                        - beforeNode.timingDataAppendFailures());
        json.writeNumberField(
                "commitCountDelta",
                afterNode.timingDataCommitCount()
                        - beforeNode.timingDataCommitCount());
        json.writeNumberField(
                "appendNanosDelta",
                afterNode.totalTimingDataAppendNanos()
                        - beforeNode.totalTimingDataAppendNanos());
        json.writeNumberField(
                "maxAppendNanos",
                afterNode.maxTimingDataAppendNanos());
        json.writeNumberField(
                "eventDeliveriesDelta",
                afterNode.timingDataEventDeliveries()
                        - beforeNode.timingDataEventDeliveries());
        json.writeNumberField(
                "eventListenerFailuresDelta",
                afterNode.timingDataEventListenerFailures()
                        - beforeNode.timingDataEventListenerFailures());
        json.writeNumberField(
                "eventDeliveryNanosDelta",
                afterNode.totalTimingDataEventNanos()
                        - beforeNode.totalTimingDataEventNanos());
        json.writeNumberField(
                "maxEventDeliveryNanos",
                afterNode.maxTimingDataEventNanos());
        json.writeEndObject();

        writeTagProcessing(
                json,
                result.tagBefore(),
                result.tagAfter());

        json.writeEndObject();
    }

    private static void writeTagProcessing(
            JsonGenerator json,
            TagProcessingMetrics.Snapshot before,
            TagProcessingMetrics.Snapshot after)
            throws IOException {
        json.writeObjectFieldStart(
                "tagProcessing");
        json.writeNumberField(
                "observationsDelta",
                after.observations() - before.observations());
        json.writeNumberField(
                "observationQueueFullDelta",
                after.observationQueueFull() - before.observationQueueFull());
        json.writeNumberField(
                "processorNotRunningDelta",
                after.processorNotRunning() - before.processorNotRunning());
        json.writeNumberField(
                "closedBurstsDelta",
                after.closedBursts() - before.closedBursts());
        json.writeNumberField(
                "mappedDelta",
                after.mapped() - before.mapped());
        json.writeNumberField(
                "unmappedDelta",
                after.unmapped() - before.unmapped());
        json.writeNumberField(
                "duplicatesDelta",
                after.duplicates() - before.duplicates());
        json.writeNumberField(
                "admittedDelta",
                after.admitted() - before.admitted());
        json.writeNumberField(
                "queueFullDelta",
                after.queueFull() - before.queueFull());
        json.writeNumberField(
                "nodeNotRunningDelta",
                after.nodeNotRunning() - before.nodeNotRunning());
        json.writeEndObject();
    }

    private static long delta(
            long before,
            long after) {
        if (before < 0L || after < 0L) {
            return -1L;
        }
        return after - before;
    }

    private static String threadStackOption() {
        List<String> arguments =
                ManagementFactory.getRuntimeMXBean()
                        .getInputArguments();
        for (String argument : arguments) {
            if (argument.startsWith("-Xss")) {
                return argument;
            }
        }
        return "JVM-default";
    }
}
