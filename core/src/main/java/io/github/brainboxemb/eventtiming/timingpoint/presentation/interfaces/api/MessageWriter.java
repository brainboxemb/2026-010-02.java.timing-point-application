package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TagProcessingValue;
import io.github.brainboxemb.eventtiming.timingpoint.application.ConfigurationControl.TimingNodeConfiguration;
import io.github.brainboxemb.eventtiming.timingpoint.application.TimingNodeStatus;
import io.github.brainboxemb.eventtiming.timingpoint.application.PresentationGateway;
import io.github.brainboxemb.eventtiming.timingpoint.domain.timing.TimingNodeTypes.Problem;
import io.github.brainboxemb.eventtiming.timingpoint.infra.BuildIdentity;

import java.nio.charset.StandardCharsets;

/** Explicit JSON mapping for the IF-03 v1 contract. */
public final class MessageWriter {
    private MessageWriter() {
    }

    public static String version(BuildIdentity identity) {
        return "{"
                + "\"application\":" + quote(identity.application()) + ","
                + "\"version\":" + quote(identity.version()) + ","
                + "\"revision\":" + quote(identity.revision()) + ","
                + "\"sourceRef\":" + quote(identity.sourceRef()) + ","
                + "\"buildOrigin\":" + quote(identity.buildOrigin()) + ","
                + "\"dirty\":" + identity.dirty() + ","
                + "\"apiVersion\":" + quote(identity.apiVersion())
                + "}";
    }

    public static String status(
            Iterable<TimingNodeStatus> statuses) {
        if (statuses == null) {
            throw new IllegalArgumentException(
                    "statuses must not be null");
        }

        StringBuilder nodes = new StringBuilder();
        StringBuilder problems = new StringBuilder();
        nodes.append('[');
        problems.append('[');

        boolean firstNode = true;
        boolean firstProblem = true;
        for (TimingNodeStatus status : statuses) {
            if (status == null) {
                throw new IllegalArgumentException(
                        "statuses must not contain null");
            }
            if (!firstNode) {
                nodes.append(',');
            }
            firstNode = false;

            String location = status.hasLocation()
                    ? Integer.toString(
                            status.locationId().value())
                    : "null";
            nodes.append('{')
                    .append("\"id\":")
                    .append(quote(
                            status.timingNodeId().value()))
                    .append(',')
                    .append("\"locationId\":")
                    .append(location)
                    .append(',')
                    .append("\"state\":")
                    .append(quote(
                            status.state().name()))
                    .append('}');

            for (Problem problem : status.problems()) {
                if (!firstProblem) {
                    problems.append(',');
                }
                firstProblem = false;
                problems.append('{')
                        .append("\"code\":")
                        .append(quote(
                                problem.code().name()))
                        .append(',')
                        .append("\"severity\":")
                        .append(quote(
                                problem.severity().name()))
                        .append(',')
                        .append("\"nodeId\":")
                        .append(quote(
                                status.timingNodeId()
                                        .value()))
                        .append(',')
                        .append("\"message\":")
                        .append(quote(
                                problem.message()))
                        .append('}');
            }
        }

        nodes.append(']');
        problems.append(']');
        return "{"
                + "\"nodes\":" + nodes + ","
                + "\"problems\":" + problems
                + "}";
    }

    public static String status(
            TimingNodeStatus status) {
        java.util.List<TimingNodeStatus> statuses =
                new java.util.ArrayList<TimingNodeStatus>();
        statuses.add(status);
        return status(statuses);
    }

    public static String capabilities(PresentationGateway.Capabilities capabilities) {
        return "{"
                + "\"capabilities\":[{"
                + "\"id\":\"DIRECT_REGISTRATION_SIMULATION\","
                + "\"supported\":"
                + capabilities.directRegistrationSimulationSupported() + ","
                + "\"enabled\":"
                + capabilities.directRegistrationSimulationEnabled()
                + "},{"
                + "\"id\":\"TAG_SCENARIO_SIMULATION\","
                + "\"supported\":"
                + capabilities.tagScenarioSimulationSupported() + ","
                + "\"enabled\":"
                + capabilities.tagScenarioSimulationEnabled()
                + "}]}";
    }

    public static String result(String result) {
        return "{\"result\":" + quote(result) + "}";
    }

    public static String committedRegistration(TimingData data) {
        return "{\"seq\":" + data.sequenceNumber() + "}";
    }

    public static String logBookInfo(int count) {
        return "{"
                + "\"count\":" + count + ","
                + "\"first\":" + (count == 0 ? "null" : "1") + ","
                + "\"last\":" + (count == 0 ? "null" : Integer.toString(count))
                + "}";
    }

    public static String logBookPage(
            int count,
            Long next,
            CharSequence encodedRecords) {
        return "{"
                + "\"count\":" + count + ","
                + "\"next\":"
                + (next == null ? "null" : Long.toString(next.longValue()))
                + ","
                + "\"records\":[" + encodedRecords + "]"
                + "}";
    }

    /**
     * Appends one canonical TimingData JSON value to an in-progress records array.
     */
    static void appendLogBookRecord(
            StringBuilder records,
            TimingData data,
            TimingDataCodec codec) {
        if (records.length() > 0) {
            records.append(',');
        }
        try {
            records.append(timingDataJson(data, codec));
        } catch (TimingDataCodec.CodecException ex) {
            throw new IllegalStateException(
                    "Could not encode LogBook record",
                    ex);
        }
    }

    public static String statusEvent(
            String eventType,
            java.time.Instant occurredAt,
            Iterable<TimingNodeStatus> statuses) {
        if (eventType == null || eventType.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    "eventType must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "occurredAt must not be null");
        }
        if (statuses == null) {
            throw new IllegalArgumentException(
                    "statuses must not be null");
        }
        return "{"
                + "\"eventType\":" + quote(eventType) + ","
                + "\"occurredAt\":" + quote(occurredAt.toString()) + ","
                + "\"payload\":" + status(statuses)
                + "}";
    }

    public static String statusEvent(
            String eventType,
            java.time.Instant occurredAt,
            TimingNodeStatus status) {
        java.util.List<TimingNodeStatus> statuses =
                new java.util.ArrayList<TimingNodeStatus>();
        statuses.add(status);
        return statusEvent(
                eventType,
                occurredAt,
                statuses);
    }

    public static String timingDataEvent(
            java.time.Instant occurredAt,
            TimingData data,
            TimingDataCodec codec)
            throws TimingDataCodec.CodecException {
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
        return "{"
                + "\"eventType\":\"TIMING_DATA_COMMITTED\","
                + "\"occurredAt\":" + quote(occurredAt.toString()) + ","
                + "\"payload\":" + timingDataJson(data, codec)
                + "}";
    }

    public static String configuration(
            ConfigurationControl.Snapshot snapshot) {
        StringBuilder json = new StringBuilder();
        json.append("{\"timingNodes\":[");
        boolean first = true;
        for (TimingNodeConfiguration node : snapshot.timingNodes()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('{')
                    .append("\"id\":")
                    .append(quote(node.nodeId().value()))
                    .append(',')
                    .append("\"tagProcessing\":")
                    .append(tagProcessing(node.tagProcessing()))
                    .append('}');
        }
        json.append("]}");
        return json.toString();
    }

    public static String configurationUpdate(
            ConfigurationControl.Update update) {
        return "{"
                + "\"result\":" + quote(update.result().name()) + ","
                + "\"tagProcessing\":"
                + tagProcessing(update.tagProcessing())
                + "}";
    }

    public static String configurationChangedEvent(
            java.time.Instant occurredAt,
            ConfigurationControl.Change change) {
        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "occurredAt must not be null");
        }
        if (change == null) {
            throw new IllegalArgumentException(
                    "change must not be null");
        }
        return "{"
                + "\"eventType\":\"CONFIGURATION_CHANGED\","
                + "\"occurredAt\":" + quote(occurredAt.toString()) + ","
                + "\"payload\":{"
                + "\"nodeId\":" + quote(change.nodeId().value()) + ","
                + "\"section\":\"tagProcessing\","
                + "\"configuration\":"
                + tagProcessing(change.tagProcessing())
                + "}}";
    }

    private static String tagProcessing(
            TagProcessingConfiguration configuration) {
        return "{"
                + "\"startup\":"
                + tagProcessingValue(configuration.startup())
                + ",\"current\":"
                + tagProcessingValue(configuration.current())
                + ",\"overridden\":"
                + configuration.overridden()
                + ",\"runtimeMutable\":{"
                + "\"quietTimeoutMillis\":"
                + configuration.quietTimeoutRuntimeMutable()
                + ",\"maxBurstDurationMillis\":"
                + configuration.maxBurstDurationRuntimeMutable()
                + ",\"duplicateWindowMillis\":"
                + configuration.duplicateWindowRuntimeMutable()
                + ",\"sweepCadenceMillis\":"
                + configuration.sweepCadenceRuntimeMutable()
                + ",\"observationQueueCapacity\":"
                + configuration.observationQueueCapacityRuntimeMutable()
                + "}}";
    }

    private static String tagProcessingValue(
            TagProcessingValue value) {
        return "{"
                + "\"quietTimeoutMillis\":"
                + value.quietTimeoutMillis()
                + ",\"maxBurstDurationMillis\":"
                + value.maxBurstDurationMillis()
                + ",\"duplicateWindowMillis\":"
                + value.duplicateWindowMillis()
                + ",\"sweepCadenceMillis\":"
                + value.sweepCadenceMillis()
                + ",\"observationQueueCapacity\":"
                + value.observationQueueCapacity()
                + "}";
    }

    public static String error(String code, String message) {
        return "{"
                + "\"error\":{"
                + "\"code\":" + quote(code) + ","
                + "\"message\":" + quote(message)
                + "}}";
    }

    private static String timingDataJson(
            TimingData data,
            TimingDataCodec codec)
            throws TimingDataCodec.CodecException {
        return new String(codec.encode(data), StandardCharsets.UTF_8);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2);
        result.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"':
                    result.append("\\\"");
                    break;
                case '\\':
                    result.append("\\\\");
                    break;
                case '\b':
                    result.append("\\b");
                    break;
                case '\f':
                    result.append("\\f");
                    break;
                case '\n':
                    result.append("\\n");
                    break;
                case '\r':
                    result.append("\\r");
                    break;
                case '\t':
                    result.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        result.append("\\u");
                        String hex = Integer.toHexString(ch);
                        for (int pad = hex.length(); pad < 4; pad++) {
                            result.append('0');
                        }
                        result.append(hex);
                    } else {
                        result.append(ch);
                    }
            }
        }
        result.append('"');
        return result.toString();
    }
}
