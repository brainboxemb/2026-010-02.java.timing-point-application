package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Public/reference IF-06 development-v1 JSON packet codec. */
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
            generator.writeStringField("type", data.direction().name());
            generator.writeStringField("nodeId", data.timingNodeId().value());
            generator.writeNumberField("packetId", data.packetId());
            generator.writeObjectFieldStart("message");
            generator.writeStringField("type", data.family().name());
            writeMessage(generator, data.message());
            generator.writeEndObject();
            generator.writeEndObject();
            generator.close();
            return output.toByteArray();
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw encodeFailure("Could not encode UpstreamData", ex);
        }
    }

    private void writeMessage(
            JsonGenerator generator,
            UpstreamData.Message message)
            throws IOException, CodecException {
        switch (message.family()) {
            case TIMING_DATA:
                generator.writeFieldName("data");
                writeTimingData(
                        generator,
                        (UpstreamData.TimingDataTransfer) message);
                return;

            case RACE_DATA:
                UpstreamData.RaceData raceData =
                        (UpstreamData.RaceData) message;
                generator.writeNumberField(
                        "locationId",
                        raceData.locationId().value());
                generator.writeFieldName("data");
                writeRaceData(generator, raceData);
                return;

            case TIMING_DATA_REQ:
                generator.writeObjectFieldStart("data");
                generator.writeNumberField(
                        "fromSeq",
                        ((UpstreamData.TimingDataRequest) message).fromSequence());
                generator.writeEndObject();
                return;

            case SEQ:
                writeSequence(
                        generator,
                        (UpstreamData.Sequence) message);
                return;

            case RECV:
                generator.writeObjectFieldStart("data");
                generator.writeNumberField(
                        "receivedPacketId",
                        ((UpstreamData.Receive) message).receivedPacketId());
                generator.writeEndObject();
                return;

            case PING:
                writePing(
                        generator,
                        (UpstreamData.Ping) message);
                return;

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
        generator.writeStartArray();
        for (TimingRecord record : transfer.records()) {
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
                    generator.writeStringField(
                            "teamId",
                            change.teamId().value());
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
                generator.writeStringField(
                        "regId",
                        change.registrationId().value());
                if (change.operation() == UpstreamData.ChangeOperation.SET) {
                    generator.writeStringField(
                            "start",
                            change.start().toString());
                }
                generator.writeEndObject();
            }
            generator.writeEndArray();
        }

        generator.writeEndObject();
    }

    private void writeSequence(
            JsonGenerator generator,
            UpstreamData.Sequence sequence)
            throws IOException {
        generator.writeObjectFieldStart("data");
        generator.writeStringField("op", sequence.operation().name());
        if (sequence.sequence() != null) {
            generator.writeNumberField(
                    "seq",
                    sequence.sequence().longValue());
        }
        generator.writeEndObject();
    }

    private void writePing(
            JsonGenerator generator,
            UpstreamData.Ping ping)
            throws IOException, CodecException {
        generator.writeObjectFieldStart("data");
        generator.writeStringField("op", ping.operation().name());
        if (ping.mode() != null) {
            generator.writeStringField("mode", ping.mode().name());
        }
        if (ping.time() != null) {
            if (!ping.time().isAbsolute()) {
                throw encodeFailure(
                        "PING time must be absolute UTC",
                        null);
            }
            generator.writeStringField("time", ping.time().toString());
        }
        if (ping.pingId() != null) {
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
            String direction = null;
            String nodeId = null;
            Long packetId = null;
            byte[] message = null;

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
                    if (direction != null) {
                        parser.close();
                        throw invalid("Duplicate type", null);
                    }
                    direction = readString(parser, value, "type");
                } else if ("nodeId".equals(field)) {
                    if (nodeId != null) {
                        parser.close();
                        throw invalid("Duplicate nodeId", null);
                    }
                    nodeId = readString(parser, value, "nodeId");
                } else if ("packetId".equals(field)) {
                    if (packetId != null) {
                        parser.close();
                        throw invalid("Duplicate packetId", null);
                    }
                    packetId = Long.valueOf(
                            readLong(parser, value, "packetId"));
                } else if ("message".equals(field)) {
                    if (message != null) {
                        parser.close();
                        throw invalid("Duplicate message", null);
                    }
                    message = captureCurrentValue(parser);
                } else {
                    parser.skipChildren();
                }
            }

            if (parser.nextToken() != null) {
                parser.close();
                throw invalid("Trailing JSON content", null);
            }
            parser.close();

            if (version == null
                    || direction == null
                    || nodeId == null
                    || packetId == null
                    || message == null) {
                throw invalid(
                        "v, type, nodeId, packetId and message are required",
                        null);
            }
            if (version.intValue() != VERSION) {
                throw CodecException.unsupportedVersion(
                        version.intValue(),
                        "Unsupported UpstreamData version " + version);
            }

            UpstreamData.Direction parsedDirection =
                    parseDirection(direction);
            NodeId parsedNodeId =
                    requireNodeId(nodeId);
            UpstreamData.Message parsedMessage =
                    decodeMessage(message);

            try {
                if (parsedDirection == UpstreamData.Direction.UP_PACKET) {
                    return DefaultUpstreamData.createUpPacket(
                            parsedNodeId,
                            packetId.longValue(),
                            parsedMessage);
                }
                return DefaultUpstreamData.createDnPacket(
                        parsedNodeId,
                        packetId.longValue(),
                        parsedMessage);
            } catch (IllegalArgumentException ex) {
                throw invalid("Invalid IF-06 packet", ex);
            }
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid UpstreamData JSON", ex);
        }
    }

    private UpstreamData.Message decodeMessage(
            byte[] encodedMessage)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(encodedMessage);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("message must be an object", null);
            }

            String type = null;
            Integer locationId = null;
            byte[] payload = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("type".equals(field)) {
                    if (type != null) {
                        parser.close();
                        throw invalid("Duplicate message type", null);
                    }
                    type = readString(parser, value, "message.type");
                } else if ("locationId".equals(field)) {
                    if (locationId != null) {
                        parser.close();
                        throw invalid("Duplicate message locationId", null);
                    }
                    locationId = Integer.valueOf(
                            readInt(parser, value, "locationId"));
                } else if ("data".equals(field)) {
                    if (payload != null) {
                        parser.close();
                        throw invalid("Duplicate message data", null);
                    }
                    payload = captureCurrentValue(parser);
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (type == null || payload == null) {
                throw invalid(
                        "message.type and message.data are required",
                        null);
            }

            if ("TIMING_DATA".equals(type)) {
                rejectLocationId(locationId, type);
                return decodeTimingData(payload);
            }
            if ("RACE_DATA".equals(type)) {
                if (locationId == null) {
                    throw invalid(
                            "RACE_DATA requires message locationId",
                            null);
                }
                return decodeRaceData(
                        new LocationId(locationId.intValue()),
                        payload);
            }
            if ("TIMING_DATA_REQ".equals(type)) {
                rejectLocationId(locationId, type);
                return decodeTimingDataRequest(payload);
            }
            if ("SEQ".equals(type)) {
                rejectLocationId(locationId, type);
                return decodeSequence(payload);
            }
            if ("RECV".equals(type)) {
                rejectLocationId(locationId, type);
                return decodeReceive(payload);
            }
            if ("PING".equals(type)) {
                rejectLocationId(locationId, type);
                return decodePing(payload);
            }

            throw CodecException.unsupportedMessageType(
                    VERSION,
                    type,
                    "Unsupported UpstreamData message type " + type);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid message object", ex);
        }
    }

    private UpstreamData.TimingDataTransfer decodeTimingData(
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                parser.close();
                throw invalid("TIMING_DATA data must be an array", null);
            }

            List<TimingRecord> records =
                    new ArrayList<TimingRecord>();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (records.size() >= UpstreamData.MAX_TIMING_DATA_RECORDS) {
                    parser.close();
                    throw invalid(
                            "TIMING_DATA supports at most "
                                    + UpstreamData.MAX_TIMING_DATA_RECORDS
                                    + " records",
                            null);
                }

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

            return DefaultUpstreamData.createTimingData(records);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid TIMING_DATA payload", ex);
        }
    }

    private UpstreamData.RaceData decodeRaceData(
            LocationId locationId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("RACE_DATA data must be an object", null);
            }

            List<UpstreamData.TeamAssignmentChange> teamChanges = null;
            List<UpstreamData.StageStartTimeChange> startChanges = null;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("teamAssignments".equals(field)) {
                    if (teamChanges != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate teamAssignments",
                                null);
                    }
                    teamChanges =
                            readTeamAssignmentChanges(parser, value);
                } else if ("stageStartTimes".equals(field)) {
                    if (startChanges != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate stageStartTimes",
                                null);
                    }
                    startChanges =
                            readStageStartTimeChanges(parser, value);
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            return DefaultUpstreamData.createRaceData(
                    locationId,
                    teamChanges,
                    startChanges);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid RACE_DATA payload", ex);
        }
    }

    private UpstreamData.TimingDataRequest decodeTimingDataRequest(
            byte[] payload)
            throws CodecException {
        Long fromSequence =
                readSingleLongField(
                        payload,
                        "fromSeq",
                        "TIMING_DATA_REQ");
        try {
            return DefaultUpstreamData.createTimingDataRequest(
                    fromSequence.longValue());
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid TIMING_DATA_REQ payload", ex);
        }
    }

    private UpstreamData.Sequence decodeSequence(
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid("SEQ data must be an object", null);
            }

            String operation = null;
            Long sequence = null;
            boolean sequenceSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        parser.close();
                        throw invalid("Duplicate SEQ op", null);
                    }
                    operation =
                            readString(parser, value, "op");
                } else if ("seq".equals(field)) {
                    if (sequenceSeen) {
                        parser.close();
                        throw invalid("Duplicate SEQ seq", null);
                    }
                    sequenceSeen = true;
                    sequence = Long.valueOf(
                            readLong(parser, value, "seq"));
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (operation == null) {
                throw invalid("SEQ requires op", null);
            }

            UpstreamData.SequenceOperation parsedOperation;
            try {
                parsedOperation =
                        UpstreamData.SequenceOperation.valueOf(operation);
            } catch (IllegalArgumentException ex) {
                throw invalid(
                        "Unsupported SEQ op " + operation,
                        ex);
            }

            if (parsedOperation == UpstreamData.SequenceOperation.REQ) {
                if (sequenceSeen) {
                    throw invalid(
                            "SEQ REQ must not carry seq",
                            null);
                }
                return DefaultUpstreamData.createSequenceRequest();
            }
            if (sequence == null) {
                throw invalid(
                        "SEQ " + parsedOperation + " requires seq",
                        null);
            }
            if (parsedOperation == UpstreamData.SequenceOperation.ANS) {
                return DefaultUpstreamData.createSequenceAnswer(
                        sequence.longValue());
            }
            return DefaultUpstreamData.createSequenceNotification(
                    sequence.longValue());
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid SEQ payload", ex);
        }
    }

    private UpstreamData.Receive decodeReceive(
            byte[] payload)
            throws CodecException {
        Long receivedPacketId =
                readSingleLongField(
                        payload,
                        "receivedPacketId",
                        "RECV");
        try {
            return DefaultUpstreamData.createReceive(
                    receivedPacketId.longValue());
        } catch (IllegalArgumentException ex) {
            throw invalid("Invalid RECV payload", ex);
        }
    }

    private UpstreamData.Ping decodePing(
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
            String time = null;
            boolean modeSeen = false;
            boolean pingIdSeen = false;
            boolean timeSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        parser.close();
                        throw invalid("Duplicate PING op", null);
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
                } else if ("time".equals(field)) {
                    if (timeSeen) {
                        parser.close();
                        throw invalid("Duplicate PING time", null);
                    }
                    timeSeen = true;
                    time = readString(parser, value, "time");
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
                throw invalid(
                        "Unsupported PING op " + operation,
                        ex);
            }

            UpstreamData.PingMode parsedMode = null;
            if (mode != null) {
                if (!"HB".equals(mode)) {
                    throw invalid(
                            "Unsupported PING mode " + mode,
                            null);
                }
                parsedMode = UpstreamData.PingMode.HB;
            }

            TimingTimestamp parsedTime = null;
            if (time != null) {
                try {
                    parsedTime = TimingTimestamp.parseAbsolute(time);
                } catch (IllegalArgumentException ex) {
                    throw invalid(
                            "PING time must be absolute UTC",
                            ex);
                }
            }

            return DefaultUpstreamData.createPing(
                    parsedOperation,
                    parsedMode,
                    pingId,
                    parsedTime);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid("Invalid PING payload", ex);
        }
    }

    private List<UpstreamData.TeamAssignmentChange>
            readTeamAssignmentChanges(
                    JsonParser parser,
                    JsonToken token)
                    throws IOException, CodecException {
        if (token != JsonToken.START_ARRAY) {
            throw invalid(
                    "teamAssignments must be an array",
                    null);
        }

        List<UpstreamData.TeamAssignmentChange> result =
                new ArrayList<UpstreamData.TeamAssignmentChange>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                throw invalid(
                        "teamAssignments item must be an object",
                        null);
            }

            String operation = null;
            String registrationId = null;
            String teamId = null;
            boolean teamIdSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        throw invalid(
                                "Duplicate teamAssignments op",
                                null);
                    }
                    operation = readString(parser, value, "op");
                } else if ("regId".equals(field)) {
                    if (registrationId != null) {
                        throw invalid("Duplicate regId", null);
                    }
                    registrationId =
                            readString(parser, value, "regId");
                } else if ("teamId".equals(field)) {
                    if (teamIdSeen) {
                        throw invalid("Duplicate teamId", null);
                    }
                    teamIdSeen = true;
                    teamId =
                            readString(parser, value, "teamId");
                } else {
                    parser.skipChildren();
                }
            }

            if (operation == null || registrationId == null) {
                throw invalid(
                        "teamAssignments item requires op and regId",
                        null);
            }

            result.add(
                    new UpstreamData.TeamAssignmentChange(
                            parseChangeOperation(operation),
                            new RegistrationId(registrationId),
                            teamId == null
                                    ? null
                                    : new TeamId(teamId)));
        }
        return result;
    }

    private List<UpstreamData.StageStartTimeChange>
            readStageStartTimeChanges(
                    JsonParser parser,
                    JsonToken token)
                    throws IOException, CodecException {
        if (token != JsonToken.START_ARRAY) {
            throw invalid(
                    "stageStartTimes must be an array",
                    null);
        }

        List<UpstreamData.StageStartTimeChange> result =
                new ArrayList<UpstreamData.StageStartTimeChange>();
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
                throw invalid(
                        "stageStartTimes item must be an object",
                        null);
            }

            String operation = null;
            String registrationId = null;
            String start = null;
            boolean startSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        throw invalid(
                                "Duplicate stageStartTimes op",
                                null);
                    }
                    operation = readString(parser, value, "op");
                } else if ("regId".equals(field)) {
                    if (registrationId != null) {
                        throw invalid(
                                "Duplicate stageStartTimes regId",
                                null);
                    }
                    registrationId =
                            readString(parser, value, "regId");
                } else if ("start".equals(field)) {
                    if (startSeen) {
                        throw invalid(
                                "Duplicate stageStartTimes start",
                                null);
                    }
                    startSeen = true;
                    start =
                            readString(parser, value, "start");
                } else {
                    parser.skipChildren();
                }
            }

            if (operation == null || registrationId == null) {
                throw invalid(
                        "stageStartTimes item requires op and regId",
                        null);
            }

            TimingTimestamp parsedStart = null;
            if (start != null) {
                try {
                    parsedStart = TimingTimestamp.parse(start);
                } catch (IllegalArgumentException ex) {
                    throw invalid(
                            "Invalid stageStartTimes start " + start,
                            ex);
                }
            }

            result.add(
                    new UpstreamData.StageStartTimeChange(
                            parseChangeOperation(operation),
                            new RegistrationId(registrationId),
                            parsedStart));
        }
        return result;
    }

    private Long readSingleLongField(
            byte[] payload,
            String fieldName,
            String messageName)
            throws CodecException {
        try {
            JsonParser parser = jsonFactory.createParser(payload);
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        messageName + " data must be an object",
                        null);
            }

            Long result = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = requireFieldName(parser);
                JsonToken value = parser.nextToken();
                if (fieldName.equals(field)) {
                    if (result != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate " + fieldName,
                                null);
                    }
                    result = Long.valueOf(
                            readLong(parser, value, fieldName));
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (result == null) {
                throw invalid(
                        messageName + " requires " + fieldName,
                        null);
            }
            return result;
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException ex) {
            throw invalid(
                    "Invalid " + messageName + " payload",
                    ex);
        }
    }

    private byte[] captureCurrentValue(JsonParser parser)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        JsonGenerator generator =
                jsonFactory.createGenerator(output);
        generator.copyCurrentStructure(parser);
        generator.close();
        return output.toByteArray();
    }

    private static UpstreamData.Direction parseDirection(
            String text)
            throws CodecException {
        try {
            return UpstreamData.Direction.valueOf(text);
        } catch (IllegalArgumentException ex) {
            throw invalid(
                    "Unsupported packet type " + text,
                    ex);
        }
    }

    private static NodeId requireNodeId(
            String nodeId)
            throws CodecException {
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
            throw invalid(
                    "Unsupported change op " + text,
                    ex);
        }
    }

    private static void rejectLocationId(
            Integer locationId,
            String type)
            throws CodecException {
        if (locationId != null) {
            throw invalid(
                    type + " must not carry message locationId",
                    null);
        }
    }

    private static String requireFieldName(
            JsonParser parser)
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
            throw invalid(
                    field + " must be a string",
                    null);
        }
        return parser.getText();
    }

    private static int readInt(
            JsonParser parser,
            JsonToken token,
            String field)
            throws CodecException, IOException {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(
                    field + " must be an integer",
                    null);
        }
        return parser.getIntValue();
    }

    private static long readLong(
            JsonParser parser,
            JsonToken token,
            String field)
            throws CodecException, IOException {
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(
                    field + " must be an integer",
                    null);
        }
        return parser.getLongValue();
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
}
