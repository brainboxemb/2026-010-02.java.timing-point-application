package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Public/reference IF-06 v1 JSON codec. */
public final class DefaultUpstreamDataCodec
        implements UpstreamDataCodec {
    private static final int VERSION = 1;


    private final JsonFactory jsonFactory = new JsonFactory();
    private final TimingDataCodec timingDataCodec;

    public DefaultUpstreamDataCodec(TimingDataCodec timingDataCodec) {
        if (timingDataCodec == null) {
            throw new IllegalArgumentException("timingDataCodec must not be null");
        }
        this.timingDataCodec = timingDataCodec;
    }

    @Override
    public byte[] encode(UpstreamData data) throws CodecException {
        if (data == null) {
            throw encodeFailure("UpstreamData must not be null", null);
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            JsonGenerator generator = jsonFactory.createGenerator(output);
            generator.writeStartObject();
            generator.writeNumberField("v", VERSION);
            generator.writeStringField("type", data.family().name());

            generator.writeStringField("nodeId", data.timingNodeId().value());
            if (data.family() == UpstreamData.Family.RACE_DATA) {
                generator.writeNumberField("seq", ((UpstreamData.RaceData) data).sequence());
            }

            generator.writeFieldName("data");
            writePayload(generator, data);
            generator.writeEndObject();
            generator.close();
            return output.toByteArray();
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw encodeFailure("Could not encode UpstreamData", ex);
        }
    }

    private void writePayload(
            JsonGenerator generator,
            UpstreamData data)
            throws IOException, CodecException {
        switch (data.family()) {
            case TIMING_DATA:
                writeTimingData(generator, (UpstreamData.TimingDataTransfer) data);
                break;
            case RACE_DATA:
                writeRaceData(generator, (UpstreamData.RaceData) data);
                break;
            case SYNC:
                writeSync(generator, (UpstreamData.Sync) data);
                break;
            case ACK:
                writeAck(generator, (UpstreamData.Ack) data);
                break;
            case REQ:
                writeReq(generator, (UpstreamData.Req) data);
                break;
            case PING:
                writePing(generator, (UpstreamData.Ping) data);
                break;
            case STATUS:
            default:
                throw encodeFailure(
                        "No default v1 STATUS payload is defined",
                        null);
        }
    }

    private void writeTimingData(
            JsonGenerator generator,
            UpstreamData.TimingDataTransfer transfer)
            throws IOException, CodecException {
        if (transfer.records() == null || transfer.records().isEmpty()) {
            throw encodeFailure(
                    "TIMING_DATA requires at least one record",
                    null);
        }

        generator.writeStartArray();
        for (TimingRecord record : transfer.records()) {
            if (record == null
                    || record.nodeId()
                            != transfer.timingNodeId().value().charAt(0)) {
                throw encodeFailure(
                        "TIMING_DATA contains a null or wrong-node record",
                        null);
            }

            final byte[] encoded;
            try {
                encoded = timingDataCodec.encode(record);
            } catch (TimingDataCodec.CodecException ex) {
                throw encodeFailure(
                        "Could not encode nested TimingData",
                        ex);
            }

            JsonParser parser = jsonFactory.createParser(encoded);
            JsonToken token = parser.nextToken();
            if (token != JsonToken.START_OBJECT) {
                parser.close();
                throw encodeFailure(
                        "TimingData codec did not produce one JSON object",
                        null);
            }
            generator.copyCurrentStructure(parser);
            if (parser.nextToken() != null) {
                parser.close();
                throw encodeFailure(
                        "TimingData codec produced trailing JSON content",
                        null);
            }
            parser.close();
        }
        generator.writeEndArray();
    }

    private void writeRaceData(
            JsonGenerator generator,
            UpstreamData.RaceData raceData)
            throws IOException, CodecException {
        requirePositiveSequence(raceData.sequence(), "RACE_DATA seq");
        if (raceData.teamAssignmentChanges().isEmpty()
                && raceData.stageStartTimeChanges().isEmpty()) {
            throw encodeFailure(
                    "RACE_DATA requires at least one change",
                    null);
        }

        generator.writeStartObject();

        if (!raceData.teamAssignmentChanges().isEmpty()) {
            generator.writeArrayFieldStart("teamAssignments");
            for (UpstreamData.TeamAssignmentChange change
                    : raceData.teamAssignmentChanges()) {
                generator.writeStartObject();
                generator.writeStringField("op", change.operation().name());
                generator.writeStringField(
                        "regId",
                        change.registrationId().value());
                if (change.operation() == UpstreamData.ChangeOperation.SET) {
                    if (change.teamId() == null) {
                        throw encodeFailure(
                                "teamAssignments SET requires teamId",
                                null);
                    }
                    generator.writeStringField(
                            "teamId",
                            change.teamId().value());
                } else if (change.teamId() != null) {
                    throw encodeFailure(
                            "teamAssignments DEL must not carry teamId",
                            null);
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
        }

        if (!raceData.stageStartTimeChanges().isEmpty()) {
            generator.writeArrayFieldStart("stageStartTimes");
            for (UpstreamData.StageStartTimeChange change
                    : raceData.stageStartTimeChanges()) {
                generator.writeStartObject();
                generator.writeStringField("op", change.operation().name());
                generator.writeStringField("regId", change.registrationId().value());
                if (change.operation() == UpstreamData.ChangeOperation.SET) {
                    TimingTimestamp start = change.start();
                    if (start == null) {
                        throw encodeFailure(
                                "stageStartTimes SET requires start timestamp",
                                null);
                    }
                    generator.writeStringField(
                            "start",
                            start.toString());
                } else if (change.start() != null) {
                    throw encodeFailure(
                            "stageStartTimes DEL must not carry start timestamp",
                            null);
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
        }

        generator.writeEndObject();
    }

    private void writeSync(
            JsonGenerator generator,
            UpstreamData.Sync sync)
            throws IOException, CodecException {
        requireSyncSequence(sync.sequence());
        writeStreamSequence(
                generator,
                sync.stream(),
                "seq",
                sync.sequence());
    }

    private void writeAck(
            JsonGenerator generator,
            UpstreamData.Ack ack)
            throws IOException, CodecException {
        if (ack.stream() != UpstreamData.Stream.RACE_DATA) {
            throw encodeFailure(
                    "public v1 ACK supports RACE_DATA only",
                    null);
        }
        requirePositiveSequence(ack.sequence(), "ACK seq");
        writeStreamSequence(
                generator,
                ack.stream(),
                "seq",
                ack.sequence());
    }

    private void writeReq(
            JsonGenerator generator,
            UpstreamData.Req req)
            throws IOException, CodecException {
        requirePositiveSequence(req.fromSequence(), "REQ fromSeq");
        writeStreamSequence(
                generator,
                req.stream(),
                "fromSeq",
                req.fromSequence());
    }

    private void writeStreamSequence(
            JsonGenerator generator,
            UpstreamData.Stream stream,
            String sequenceField,
            long sequence)
            throws IOException, CodecException {
        if (stream == null) {
            throw encodeFailure("stream must not be null", null);
        }
        generator.writeStartObject();
        generator.writeStringField("stream", stream.name());
        generator.writeNumberField(sequenceField, sequence);
        generator.writeEndObject();
    }

    private void writePing(
            JsonGenerator generator,
            UpstreamData.Ping ping)
            throws IOException, CodecException {
        if (ping.operation() == null) {
            throw encodeFailure("PING op must not be null", null);
        }

        generator.writeStartObject();
        generator.writeStringField("op", ping.operation().name());
        if (ping.mode() != null) {
            if (ping.mode() != UpstreamData.PingMode.HB) {
                throw encodeFailure(
                        "Unsupported PING mode " + ping.mode(),
                        null);
            }
            generator.writeStringField("mode", ping.mode().name());
        }
        if (ping.pingId() != null) {
            if (ping.pingId().trim().isEmpty()) {
                throw encodeFailure("pingId must not be blank", null);
            }
            generator.writeStringField("pingId", ping.pingId());
        }
        generator.writeEndObject();
    }

    @Override
    public UpstreamData decode(byte[] encodedMessage)
            throws CodecException {
        if (encodedMessage == null) {
            throw invalid("encodedMessage must not be null", null);
        }
        if (hasUtf8Bom(encodedMessage)) {
            throw invalid("UTF-8 BOM is not allowed", null);
        }

        try {
            JsonParser parser = jsonFactory.createParser(encodedMessage);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "UpstreamData must be one JSON object",
                        null);
            }

            Integer version = null;
            String type = null;
            String nodeId = null;
            Long topSequence = null;
            byte[] payload = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();

                if ("v".equals(field)) {
                    if (version != null) {
                        parser.close();
                        throw invalid("Duplicate v", null);
                    }
                    version = Integer.valueOf(readInt(parser, value, "v"));
                } else if ("type".equals(field)) {
                    if (type != null) {
                        parser.close();
                        throw invalid("Duplicate type", null);
                    }
                    type = readString(parser, value, "type");
                } else if ("nodeId".equals(field)) {
                    if (nodeId != null) {
                        parser.close();
                        throw invalid("Duplicate nodeId", null);
                    }
                    nodeId = readString(parser, value, "nodeId");
                } else if ("seq".equals(field)) {
                    if (topSequence != null) {
                        parser.close();
                        throw invalid("Duplicate top-level seq", null);
                    }
                    topSequence = Long.valueOf(
                            readLong(parser, value, "seq"));
                } else if ("data".equals(field)) {
                    if (payload != null) {
                        parser.close();
                        throw invalid("Duplicate data", null);
                    }
                    payload = captureCurrentValue(parser);
                } else {
                    parser.skipChildren();
                }
            }

            if (parser.nextToken() != null) {
                parser.close();
                throw invalid("Trailing JSON content", null);
            }
            parser.close();

            if (version == null || type == null || payload == null) {
                throw invalid("v, type and data are required", null);
            }
            if (version.intValue() != VERSION) {
                throw CodecException.unsupportedVersion(
                        version.intValue(),
                        "Unsupported UpstreamData version " + version);
            }

            return decodeByType(
                    type,
                    nodeId,
                    topSequence,
                    payload);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid UpstreamData JSON", ex);
        }
    }

    private UpstreamData decodeByType(
            String type,
            String nodeId,
            Long topSequence,
            byte[] payload)
            throws CodecException {
        if ("TIMING_DATA".equals(type)) {
            rejectTopSequence(topSequence, type);
            return decodeTimingData(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("RACE_DATA".equals(type)) {
            if (topSequence == null) {
                throw invalid("RACE_DATA requires top-level seq", null);
            }
            requirePositiveSequence(
                    topSequence.longValue(),
                    "RACE_DATA seq");
            return decodeRaceData(
                    requireNodeId(nodeId, type),
                    topSequence.longValue(),
                    payload);
        }
        if ("SYNC".equals(type)) {
            rejectTopSequence(topSequence, type);
            return decodeSync(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("ACK".equals(type)) {
            rejectTopSequence(topSequence, type);
            return decodeAck(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("REQ".equals(type)) {
            rejectTopSequence(topSequence, type);
            return decodeReq(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("PING".equals(type)) {
            rejectTopSequence(topSequence, type);
            return decodePing(
                    requireNodeId(nodeId, type),
                    payload);
        }

        throw CodecException.unsupportedMessageType(
                VERSION,
                type,
                "Unsupported UpstreamData type " + type);
    }

    private UpstreamData.TimingDataTransfer decodeTimingData(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                parser.close();
                throw invalid("TIMING_DATA data must be an array", null);
            }

            List<TimingRecord> records = new ArrayList<TimingRecord>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                byte[] recordJson = captureCurrentValue(parser);
                final TimingRecord record;
                try {
                    record = timingDataCodec.decode(recordJson);
                } catch (TimingDataCodec.CodecException ex) {
                    parser.close();
                    throw invalid("Invalid nested TimingData", ex);
                }
                records.add(record);
            }
            parser.close();

            return DefaultUpstreamData.timingData(nodeId, records);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid TIMING_DATA payload", ex);
        }
    }

    private UpstreamData.RaceData decodeRaceData(
            NodeId nodeId,
            long sequence,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("RACE_DATA data must be an object", null);
            }

            List<UpstreamData.TeamAssignmentChange> reserveChanges = null;
            List<UpstreamData.StageStartTimeChange> startChanges = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("teamAssignments".equals(field)) {
                    if (reserveChanges != null) {
                        parser.close();
                        throw invalid("Duplicate teamAssignments", null);
                    }
                    reserveChanges = readTeamAssignmentChanges(parser, value);
                } else if ("stageStartTimes".equals(field)) {
                    if (startChanges != null) {
                        parser.close();
                        throw invalid("Duplicate stageStartTimes", null);
                    }
                    startChanges = readStageStartTimeChanges(parser, value);
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            return DefaultUpstreamData.raceData(
                    nodeId,
                    sequence,
                    reserveChanges,
                    startChanges);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid RACE_DATA payload", ex);
        }
    }

    private List<UpstreamData.TeamAssignmentChange>
            readTeamAssignmentChanges(
                    JsonParser parser,
                    JsonToken token)
                    throws IOException, CodecException {
        if (token != JsonToken.START_ARRAY) {
            throw invalid("teamAssignments must be an array", null);
        }

        List<UpstreamData.TeamAssignmentChange> result =
                new ArrayList<UpstreamData.TeamAssignmentChange>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                throw invalid(
                        "teamAssignments item must be an object",
                        null);
            }

            String op = null;
            String registrationId = null;
            String teamId = null;
            boolean teamIdSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (op != null) {
                        throw invalid("Duplicate teamAssignments op", null);
                    }
                    op = readString(parser, value, "op");
                } else if ("regId".equals(field)) {
                    if (registrationId != null) {
                        throw invalid("Duplicate regId", null);
                    }
                    registrationId = readString(parser, value, "regId");
                } else if ("teamId".equals(field)) {
                    if (teamIdSeen) {
                        throw invalid("Duplicate teamId", null);
                    }
                    teamIdSeen = true;
                    teamId = readString(parser, value, "teamId");
                } else {
                    parser.skipChildren();
                }
            }

            if (op == null || registrationId == null) {
                throw invalid(
                        "teamAssignments item requires op and regId",
                        null);
            }
            UpstreamData.ChangeOperation operation =
                    parseChangeOperation(op);
            result.add(
                    new UpstreamData.TeamAssignmentChange(
                            operation,
                            new RegistrationId(registrationId),
                            teamId == null ? null : new TeamId(teamId)));
        }
        return result;
    }

    private List<UpstreamData.StageStartTimeChange>
            readStageStartTimeChanges(
                    JsonParser parser,
                    JsonToken token)
                    throws IOException, CodecException {
        if (token != JsonToken.START_ARRAY) {
            throw invalid("stageStartTimes must be an array", null);
        }

        List<UpstreamData.StageStartTimeChange> result =
                new ArrayList<UpstreamData.StageStartTimeChange>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                throw invalid(
                        "stageStartTimes item must be an object",
                        null);
            }

            String op = null;
            String registrationId = null;
            String start = null;
            boolean startSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (op != null) {
                        throw invalid("Duplicate stageStartTimes op", null);
                    }
                    op = readString(parser, value, "op");
                } else if ("regId".equals(field)) {
                    if (registrationId != null) {
                        throw invalid("Duplicate stageStartTimes regId", null);
                    }
                    registrationId = readString(parser, value, "regId");
                } else if ("start".equals(field)) {
                    if (startSeen) {
                        throw invalid("Duplicate stageStartTimes start", null);
                    }
                    startSeen = true;
                    start = readString(parser, value, "start");
                } else {
                    parser.skipChildren();
                }
            }

            if (op == null || registrationId == null) {
                throw invalid(
                        "stageStartTimes item requires op and regId",
                        null);
            }

            UpstreamData.ChangeOperation operation =
                    parseChangeOperation(op);
            TimingTimestamp parsedStart = null;
            if (start != null) {
                try {
                    parsedStart =
                            TimingTimestamp.parse(start);
                } catch (IllegalArgumentException ex) {
                    throw invalid(
                            "Invalid stageStartTimes start " + start,
                            ex);
                }
            }

            result.add(
                    new UpstreamData.StageStartTimeChange(
                            operation,
                            new RegistrationId(registrationId),
                            parsedStart));
        }
        return result;
    }

    private UpstreamData.Sync decodeSync(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        StreamSequence value =
                decodeStreamSequence(payload, "seq", true);
        return DefaultUpstreamData.sync(
                nodeId,
                value.stream,
                value.sequence);
    }

    private UpstreamData.Ack decodeAck(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        StreamSequence value =
                decodeStreamSequence(payload, "seq", false);
        try {
            return DefaultUpstreamData.ack(
                    nodeId,
                    value.stream,
                    value.sequence);
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid ACK payload", ex);
        }
    }

    private UpstreamData.Req decodeReq(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        StreamSequence value =
                decodeStreamSequence(payload, "fromSeq", false);
        try {
            return DefaultUpstreamData.req(
                    nodeId,
                    value.stream,
                    value.sequence);
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid REQ payload", ex);
        }
    }

    private StreamSequence decodeStreamSequence(
            byte[] payload,
            String sequenceField,
            boolean allowZero)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("control data must be an object", null);
            }

            String stream = null;
            Long sequence = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("stream".equals(field)) {
                    if (stream != null) {
                        parser.close();
                        throw invalid("Duplicate stream", null);
                    }
                    stream = readString(parser, value, "stream");
                } else if (sequenceField.equals(field)) {
                    if (sequence != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate " + sequenceField,
                                null);
                    }
                    sequence = Long.valueOf(
                            readLong(parser, value, sequenceField));
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (stream == null || sequence == null) {
                throw invalid(
                        "control data requires stream and " + sequenceField,
                        null);
            }

            UpstreamData.Stream parsedStream;
            try {
                parsedStream = UpstreamData.Stream.valueOf(stream);
            } catch (IllegalArgumentException ex) {
                throw invalid("Unsupported stream " + stream, ex);
            }

            if (allowZero) {
                requireSyncSequence(sequence.longValue());
            } else {
                requirePositiveSequence(
                        sequence.longValue(),
                        sequenceField);
            }
            return new StreamSequence(
                    parsedStream,
                    sequence.longValue());
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException ex) {
            throw invalid("Invalid control payload", ex);
        }
    }

    private UpstreamData.Ping decodePing(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("PING data must be an object", null);
            }

            String operation = null;
            String mode = null;
            String pingId = null;
            boolean modeSeen = false;
            boolean pingIdSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        parser.close();
                        throw invalid("Duplicate op", null);
                    }
                    operation = readString(parser, value, "op");
                } else if ("mode".equals(field)) {
                    if (modeSeen) {
                        parser.close();
                        throw invalid("Duplicate mode", null);
                    }
                    modeSeen = true;
                    mode = readString(parser, value, "mode");
                } else if ("pingId".equals(field)) {
                    if (pingIdSeen) {
                        parser.close();
                        throw invalid("Duplicate pingId", null);
                    }
                    pingIdSeen = true;
                    pingId = readString(parser, value, "pingId");
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (operation == null) {
                throw invalid("PING requires op", null);
            }

            final UpstreamData.PingOperation parsedOperation;
            try {
                parsedOperation =
                        UpstreamData.PingOperation.valueOf(operation);
            } catch (IllegalArgumentException ex) {
                throw invalid("Unsupported PING op " + operation, ex);
            }

            UpstreamData.PingMode parsedMode = null;
            if (mode != null) {
                if (!"HB".equals(mode)) {
                    throw invalid("Unsupported PING mode " + mode, null);
                }
                parsedMode = UpstreamData.PingMode.HB;
            }

            return DefaultUpstreamData.ping(
                    nodeId,
                    parsedOperation,
                    parsedMode,
                    pingId);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid PING payload", ex);
        }
    }

    private byte[] captureCurrentValue(JsonParser parser)
            throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JsonGenerator generator = jsonFactory.createGenerator(output);
        generator.copyCurrentStructure(parser);
        generator.close();
        return output.toByteArray();
    }

    private static String requireFieldName(JsonParser parser)
            throws IOException, CodecException {
        if (parser.getCurrentToken() != JsonToken.FIELD_NAME) {
            throw invalid("Expected field name", null);
        }
        return parser.getCurrentName();
    }

    private static String readString(
            JsonParser parser,
            JsonToken token,
            String field)
            throws CodecException, IOException {
        if (token != JsonToken.VALUE_STRING) {
            throw invalid(field + " must be a string", null);
        }
        return parser.getText();
    }

    private static int readInt(
            JsonParser parser,
            JsonToken token,
            String field)
            throws CodecException, IOException {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(field + " must be an integer", null);
        }
        return parser.getIntValue();
    }

    private static long readLong(
            JsonParser parser,
            JsonToken token,
            String field)
            throws CodecException, IOException {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(field + " must be an integer", null);
        }
        return parser.getLongValue();
    }

    private static NodeId requireNodeId(
            String nodeId,
            String type)
            throws CodecException {
        if (nodeId == null) {
            throw invalid(type + " requires nodeId", null);
        }
        try {
            return new NodeId(nodeId);
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid nodeId", ex);
        }
    }

    private static UpstreamData.ChangeOperation parseChangeOperation(
            String text)
            throws CodecException {
        try {
            return UpstreamData.ChangeOperation.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw invalid("Unsupported change op " + text, ex);
        }
    }

    private static void rejectTopSequence(
            Long sequence,
            String type)
            throws CodecException {
        if (sequence != null) {
            throw invalid(
                    type + " must not carry top-level seq",
                    null);
        }
    }

    private static void requirePositiveSequence(
            long sequence,
            String field)
            throws CodecException {
        if (sequence < 1L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw invalid(
                    field + " must be in range 1.."
                            + TimingRecord.MAX_SEQUENCE_NUMBER,
                    null);
        }
    }

    private static void requireSyncSequence(long sequence)
            throws CodecException {
        if (sequence < 0L || sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
            throw invalid(
                    "SYNC seq must be in range 0.."
                            + TimingRecord.MAX_SEQUENCE_NUMBER,
                    null);
        }
    }


    private static boolean hasUtf8Bom(byte[] data) {
        return data.length >= 3
                && (data[0] & 0xff) == 0xef
                && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf;
    }

    private static CodecException invalid(
            String message,
            Throwable cause) {
        return new CodecException(
                CodecException.Reason.INVALID_DATA,
                message,
                cause);
    }

    private static CodecException encodeFailure(
            String message,
            Throwable cause) {
        return new CodecException(
                CodecException.Reason.ENCODE_FAILURE,
                message,
                cause);
    }

    private static final class StreamSequence {
        private final UpstreamData.Stream stream;
        private final long sequence;

        private StreamSequence(
                UpstreamData.Stream stream,
                long sequence) {
            this.stream = stream;
            this.sequence = sequence;
        }
    }
}
