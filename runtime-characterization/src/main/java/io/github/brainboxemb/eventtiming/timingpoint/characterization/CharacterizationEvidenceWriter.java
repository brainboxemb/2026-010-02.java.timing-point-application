package io.github.brainboxemb.eventtiming.timingpoint.characterization;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingMetrics;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.JvmRuntimeSnapshot;
import io.github.brainboxemb.eventtiming.timingpoint.runtime.measurement.TimingNodeRuntimeSnapshot;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Writes one compact machine-readable characterization summary. */
final class CharacterizationEvidenceWriter {
    private static final int SCHEMA_VERSION = 1;

    private CharacterizationEvidenceWriter() {
    }

    static void writeSuccess(
            CharacterizationConfig config,
            CharacterizationBuildInfo build,
            RuntimeCharacterizationHarness.Result result)
            throws IOException {
        JsonGenerator json =
                open(
                        config.output());
        try {
            json.writeStartObject();
            writeCommon(
                    json,
                    config,
                    build,
                    result.startedAt(),
                    result.endedAt(),
                    "PASS");

            json.writeObjectFieldStart(
                    "results");
            writeTimingNode(
                    json,
                    result.nodeBefore(),
                    result.nodeAfter());
            writeTagProcessing(
                    json,
                    result.tagBefore(),
                    result.tagAfter());
            writeJvm(
                    json,
                    result.jvmBefore(),
                    result.jvmAfter());

            RuntimeCharacterizationHarness.QueryMeasurement query =
                    result.query();
            json.writeObjectFieldStart(
                    "historyQuery");
            json.writeNumberField(
                    "requestedLimit",
                    query.requestedLimit());
            json.writeNumberField(
                    "visited",
                    query.visited());
            json.writeNumberField(
                    "totalHistory",
                    query.totalHistory());
            json.writeNumberField(
                    "elapsedNanos",
                    query.elapsedNanos());
            json.writeEndObject();

            json.writeNumberField(
                    "historyAtMeasurementStart",
                    result.historyAtMeasurementStart());
            json.writeNumberField(
                    "measuredElapsedNanos",
                    result.measuredElapsedNanos());
            json.writeEndObject();

            json.writeEndObject();
        } finally {
            json.close();
        }
    }

    static void writeFailure(
            CharacterizationConfig config,
            CharacterizationBuildInfo build,
            Instant startedAt,
            Throwable failure)
            throws IOException {
        JsonGenerator json =
                open(
                        config.output());
        try {
            json.writeStartObject();
            writeCommon(
                    json,
                    config,
                    build,
                    startedAt,
                    Instant.now(),
                    "FAIL");
            json.writeObjectFieldStart(
                    "failure");
            json.writeStringField(
                    "type",
                    failure.getClass()
                            .getName());
            json.writeStringField(
                    "message",
                    String.valueOf(
                            failure.getMessage()));
            json.writeEndObject();
            json.writeEndObject();
        } finally {
            json.close();
        }
    }

    private static JsonGenerator open(
            Path output)
            throws IOException {
        Path parent =
                output.toAbsolutePath()
                        .getParent();
        if (parent != null) {
            Files.createDirectories(
                    parent);
        }

        JsonGenerator json =
                new JsonFactory()
                        .createGenerator(
                                output.toFile(),
                                JsonEncoding.UTF8);
        json.useDefaultPrettyPrinter();
        return json;
    }

    private static void writeCommon(
            JsonGenerator json,
            CharacterizationConfig config,
            CharacterizationBuildInfo build,
            Instant startedAt,
            Instant endedAt,
            String outcome)
            throws IOException {
        json.writeNumberField(
                "schemaVersion",
                SCHEMA_VERSION);
        json.writeStringField(
                "runId",
                config.runId());
        json.writeStringField(
                "outcome",
                outcome);
        json.writeStringField(
                "startedAt",
                startedAt.toString());
        json.writeStringField(
                "endedAt",
                endedAt.toString());

        json.writeObjectFieldStart(
                "source");
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
                "buildOrigin",
                build.buildOrigin());
        json.writeBooleanField(
                "dirty",
                build.dirty());
        json.writeEndObject();

        json.writeObjectFieldStart(
                "environment");
        json.writeStringField(
                "javaVendor",
                System.getProperty(
                        "java.vendor"));
        json.writeStringField(
                "javaVersion",
                System.getProperty(
                        "java.version"));
        json.writeStringField(
                "vmName",
                System.getProperty(
                        "java.vm.name"));
        json.writeStringField(
                "osName",
                System.getProperty(
                        "os.name"));
        json.writeStringField(
                "osVersion",
                System.getProperty(
                        "os.version"));
        json.writeStringField(
                "osArch",
                System.getProperty(
                        "os.arch"));
        json.writeStringField(
                "stackOption",
                stackOption());
        json.writeEndObject();

        json.writeObjectFieldStart(
                "workload");
        json.writeStringField(
                "name",
                config.workload()
                        .name()
                        .toLowerCase());
        json.writeStringField(
                "version",
                "1");
        json.writeStringField(
                "inputPattern",
                "sequential-known-tags-v1");
        json.writeBooleanField(
                "deterministicInput",
                true);
        json.writeNumberField(
                "knownTagPercent",
                100);
        json.writeNumberField(
                "unknownTagPercent",
                0);
        json.writeStringField(
                "deliveryShape",
                config.workload() == CharacterizationConfig.Workload.BURST
                        ? "unpaced-burst"
                        : "paced");
        json.writeNumberField(
                "timingNodeCount",
                1);
        json.writeNumberField(
                "locationId",
                1);
        json.writeStringField(
                "initialTimingNodeState",
                "OPEN");
        json.writeNumberField(
                "warmupCount",
                config.warmupCount());
        json.writeNumberField(
                "measuredCount",
                config.measuredCount());
        json.writeNumberField(
                "referenceRegistrationsPerSecond",
                config.registrationsPerSecond());
        if (config.workload() == CharacterizationConfig.Workload.BURST) {
            json.writeNullField(
                    "pacedDeliveryRegistrationsPerSecond");
        } else {
            json.writeNumberField(
                    "pacedDeliveryRegistrationsPerSecond",
                    config.registrationsPerSecond());
        }
        json.writeNumberField(
                "repetitionsInRun",
                1);
        json.writeNumberField(
                "preloadedCommittedRecords",
                config.preloadCount());
        json.writeNumberField(
                "timingNodeQueueCapacity",
                RuntimeCharacterizationHarness.timingNodeQueueCapacity());
        json.writeNumberField(
                "tagProcessorLaneQueueCapacity",
                RuntimeCharacterizationHarness.tagProcessorQueueCapacity());
        json.writeNumberField(
                "tagObservationQueueCapacity",
                io.github.brainboxemb.eventtiming.timingpoint.domain.timing.processing.TagProcessingPolicy
                        .defaults()
                        .observationQueueCapacity());
        json.writeStringField(
                "persistenceMode",
                "file-backed-jsonl");
        json.writeStringField(
                "workDirectory",
                config.workDirectory()
                        .toString());
        json.writeEndObject();
    }

    private static void writeTimingNode(
            JsonGenerator json,
            TimingNodeRuntimeSnapshot before,
            TimingNodeRuntimeSnapshot after)
            throws IOException {
        json.writeObjectFieldStart(
                "timingNode");
        json.writeNumberField(
                "queueDepthAfter",
                after.queueDepth());
        json.writeNumberField(
                "queueHighWaterBefore",
                before.queueHighWaterMark());
        json.writeNumberField(
                "queueHighWaterAfter",
                after.queueHighWaterMark());
        json.writeNumberField(
                "queueAcceptedDelta",
                delta(
                        before.queueAcceptedCount(),
                        after.queueAcceptedCount()));
        json.writeNumberField(
                "queueFullDelta",
                delta(
                        before.queueFullCount(),
                        after.queueFullCount()));
        json.writeNumberField(
                "queueNotRunningDelta",
                delta(
                        before.queueNotRunningCount(),
                        after.queueNotRunningCount()));
        json.writeNumberField(
                "queueCompletedDelta",
                delta(
                        before.queueCompletedCount(),
                        after.queueCompletedCount()));
        json.writeNumberField(
                "queueWaitNanosDelta",
                delta(
                        before.totalQueueWaitNanos(),
                        after.totalQueueWaitNanos()));
        json.writeNumberField(
                "maxQueueWaitNanosBefore",
                before.maxQueueWaitNanos());
        json.writeNumberField(
                "maxQueueWaitNanosAfter",
                after.maxQueueWaitNanos());
        json.writeNumberField(
                "executionNanosDelta",
                delta(
                        before.totalExecutionNanos(),
                        after.totalExecutionNanos()));
        json.writeNumberField(
                "maxExecutionNanosBefore",
                before.maxExecutionNanos());
        json.writeNumberField(
                "maxExecutionNanosAfter",
                after.maxExecutionNanos());
        json.writeNumberField(
                "appendAttemptsDelta",
                delta(
                        before.timingDataAppendAttempts(),
                        after.timingDataAppendAttempts()));
        json.writeNumberField(
                "appendFailuresDelta",
                delta(
                        before.timingDataAppendFailures(),
                        after.timingDataAppendFailures()));
        json.writeNumberField(
                "committedDelta",
                delta(
                        before.timingDataCommitCount(),
                        after.timingDataCommitCount()));
        json.writeNumberField(
                "appendNanosDelta",
                delta(
                        before.totalTimingDataAppendNanos(),
                        after.totalTimingDataAppendNanos()));
        json.writeNumberField(
                "maxAppendNanosBefore",
                before.maxTimingDataAppendNanos());
        json.writeNumberField(
                "maxAppendNanosAfter",
                after.maxTimingDataAppendNanos());
        json.writeNumberField(
                "eventDeliveriesDelta",
                delta(
                        before.timingDataEventDeliveries(),
                        after.timingDataEventDeliveries()));
        json.writeNumberField(
                "eventListenerFailuresDelta",
                delta(
                        before.timingDataEventListenerFailures(),
                        after.timingDataEventListenerFailures()));
        json.writeNumberField(
                "eventDeliveryNanosDelta",
                delta(
                        before.totalTimingDataEventNanos(),
                        after.totalTimingDataEventNanos()));
        json.writeNumberField(
                "maxEventDeliveryNanosBefore",
                before.maxTimingDataEventNanos());
        json.writeNumberField(
                "maxEventDeliveryNanosAfter",
                after.maxTimingDataEventNanos());
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
                delta(
                        before.observations(),
                        after.observations()));
        json.writeNumberField(
                "observationQueueFullDelta",
                delta(
                        before.observationQueueFull(),
                        after.observationQueueFull()));
        json.writeNumberField(
                "processorNotRunningDelta",
                delta(
                        before.processorNotRunning(),
                        after.processorNotRunning()));
        json.writeNumberField(
                "closedBurstsDelta",
                delta(
                        before.closedBursts(),
                        after.closedBursts()));
        json.writeNumberField(
                "mappedDelta",
                delta(
                        before.mapped(),
                        after.mapped()));
        json.writeNumberField(
                "unmappedDelta",
                delta(
                        before.unmapped(),
                        after.unmapped()));
        json.writeNumberField(
                "duplicatesDelta",
                delta(
                        before.duplicates(),
                        after.duplicates()));
        json.writeNumberField(
                "timingNodeAdmittedDelta",
                delta(
                        before.admitted(),
                        after.admitted()));
        json.writeNumberField(
                "timingNodeQueueFullDelta",
                delta(
                        before.queueFull(),
                        after.queueFull()));
        json.writeNumberField(
                "timingNodeNotRunningDelta",
                delta(
                        before.nodeNotRunning(),
                        after.nodeNotRunning()));
        json.writeEndObject();
    }

    private static void writeJvm(
            JsonGenerator json,
            JvmRuntimeSnapshot before,
            JvmRuntimeSnapshot after)
            throws IOException {
        json.writeObjectFieldStart(
                "jvm");
        json.writeNumberField(
                "heapUsedBytesBefore",
                before.heapUsedBytes());
        json.writeNumberField(
                "heapUsedBytesAfter",
                after.heapUsedBytes());
        json.writeNumberField(
                "liveThreadCountBefore",
                before.liveThreadCount());
        json.writeNumberField(
                "liveThreadCountAfter",
                after.liveThreadCount());
        json.writeNumberField(
                "gcCollectionCountDelta",
                availableDelta(
                        before.gcCollectionCount(),
                        after.gcCollectionCount()));
        json.writeNumberField(
                "gcCollectionTimeMillisDelta",
                availableDelta(
                        before.gcCollectionTimeMillis(),
                        after.gcCollectionTimeMillis()));
        json.writeNumberField(
                "roleWorkerCpuTimeNanosDelta",
                availableDelta(
                        before.roleWorkerCpuTimeNanos(),
                        after.roleWorkerCpuTimeNanos()));
        json.writeStringField(
                "nativeThreadMemory",
                "not-collected-by-portable-baseline");
        json.writeEndObject();
    }

    private static long delta(
            long before,
            long after) {
        return after - before;
    }

    private static long availableDelta(
            long before,
            long after) {
        if (before < 0L || after < 0L) {
            return -1L;
        }
        return after - before;
    }

    private static String stackOption() {
        List<String> arguments =
                ManagementFactory.getRuntimeMXBean()
                        .getInputArguments();
        for (String argument : arguments) {
            if (argument.startsWith(
                    "-Xss")) {
                return argument;
            }
        }
        return "JVM-default";
    }
}
