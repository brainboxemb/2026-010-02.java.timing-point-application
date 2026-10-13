package io.github.brainboxemb.eventtiming.upstreamdata.defaultprofile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.eventdata.TeamId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamData;
import io.github.brainboxemb.eventtiming.upstreamdata.UpstreamDataCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Public/reference IF-06 v1 JSON codec.
 *
 * <p>The codec composes the configured IF-05 TimingData codec for nested
 * TIMING_DATA records. It owns no transport framing or routing metadata.</p>
 */
public final class DefaultUpstreamDataCodec
        implements UpstreamDataCodec {
    private static final int VERSION = 1;

    private static final DateTimeFormatter START_TIME_READER =
            new DateTimeFormatterBuilder()
                    .appendPattern("HH:mm:ss")
                    .optionalStart()
                    .appendFraction(
                            ChronoField.NANO_OF_SECOND,
                            2,
                            2,
                            true)
                    .optionalEnd()
                    .toFormatter(Locale.ROOT)
                    .withResolverStyle(ResolverStyle.STRICT);
    private static final DateTimeFormatter START_TIME_SECONDS =
            DateTimeFormatter.ofPattern("HH:mm:ss", Locale.ROOT);
    private static final DateTimeFormatter START_TIME_CENTISECONDS =
            DateTimeFormatter.ofPattern("HH:mm:ss.SS", Locale.ROOT);
    private static final DateTimeFormatter START_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private final JsonFactory jsonFactory = new JsonFactory();
    private final TimingDataCodec timingDataCodec;

    public DefaultUpstreamDataCodec(
            TimingDataCodec timingDataCodec) {
        if (timingDataCodec == null) {
            throw new IllegalArgumentException(
                    "timingDataCodec must not be null");
        }
        this.timingDataCodec = timingDataCodec;
    }

    @Override
    public byte[] encode(
            UpstreamData data)
            throws CodecException {
        if (data == null) {
            throw encodeFailure(
                    "UpstreamData must not be null",
                    null);
        }

        try {
            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();
            JsonGenerator generator =
                    jsonFactory.createGenerator(output);
            generator.writeStartObject();
            generator.writeNumberField("v", VERSION);
            generator.writeStringField(
                    "type",
                    data.family().name());

            if (data instanceof UpstreamData.NodeScoped) {
                generator.writeStringField(
                        "nodeId",
                        ((UpstreamData.NodeScoped) data)
                                .timingNodeId()
                                .value());
            }

            generator.writeFieldName("data");
            writePayload(
                    generator,
                    data);
            generator.writeEndObject();
            generator.close();
            return output.toByteArray();
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw encodeFailure(
                    "Could not encode UpstreamData",
                    ex);
        }
    }

    private void writePayload(
            JsonGenerator generator,
            UpstreamData data)
            throws IOException, CodecException {
        switch (data.family()) {
            case TIMING_DATA:
                writeTimingData(
                        generator,
                        requireType(
                                data,
                                UpstreamData.TimingDataTransfer.class,
                                "TIMING_DATA"));
                return;
            case REFERENCE_DATA:
                writeReferenceData(
                        generator,
                        requireType(
                                data,
                                UpstreamData.ReferenceData.class,
                                "REFERENCE_DATA"));
                return;
            case SYNC:
                writeSync(
                        generator,
                        requireType(
                                data,
                                UpstreamData.Sync.class,
                                "SYNC"));
                return;
            case PING:
                writePing(
                        generator,
                        requireType(
                                data,
                                UpstreamData.Ping.class,
                                "PING"));
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
        if (transfer.records() == null
                || transfer.records().isEmpty()) {
            throw encodeFailure(
                    "TIMING_DATA requires at least one record",
                    null);
        }

        generator.writeStartArray();
        for (TimingData record : transfer.records()) {
            if (record == null
                    || !transfer.timingNodeId()
                            .equals(record.timingNodeId())) {
                throw encodeFailure(
                        "TIMING_DATA contains a null or wrong-node record",
                        null);
            }

            final byte[] encoded;
            try {
                encoded =
                        timingDataCodec.encode(
                                record);
            } catch (TimingDataCodec.CodecException ex) {
                throw encodeFailure(
                        "Could not encode nested TimingData",
                        ex);
            }

            JsonParser parser =
                    jsonFactory.createParser(encoded);
            JsonToken token =
                    parser.nextToken();
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

    private void writeReferenceData(
            JsonGenerator generator,
            UpstreamData.ReferenceData reference)
            throws IOException, CodecException {
        requireReferenceRevision(
                reference.revision());

        generator.writeStartObject();
        generator.writeStringField(
                "dataset",
                reference.dataset().name());
        generator.writeNumberField(
                "rev",
                reference.revision());
        generator.writeArrayFieldStart("items");

        switch (reference.dataset()) {
            case RACE_DATA:
                if (!(reference instanceof UpstreamData.RaceData)) {
                    throw encodeFailure(
                            "RACE_DATA does not implement RaceData",
                            null);
                }
                for (UpstreamData.RaceAssignment assignment
                        : ((UpstreamData.RaceData) reference)
                                .assignments()) {
                    generator.writeStartObject();
                    generator.writeStringField(
                            "regId",
                            assignment.registrationId().value());
                    generator.writeStringField(
                            "teamId",
                            assignment.teamId().value());
                    generator.writeEndObject();
                }
                break;
            case STAGE_START_TIMES:
                if (!(reference instanceof UpstreamData.StageStartTimes)) {
                    throw encodeFailure(
                            "STAGE_START_TIMES does not implement StageStartTimes",
                            null);
                }
                for (UpstreamData.StageStartDefinition start
                        : ((UpstreamData.StageStartTimes) reference)
                                .starts()) {
                    generator.writeStartObject();
                    generator.writeStringField(
                            "teamId",
                            start.teamId().value());
                    generator.writeStringField(
                            "startTime",
                            formatStartTime(
                                    start.startTime()));
                    if (start.startDate() != null) {
                        generator.writeStringField(
                                "startDate",
                                START_DATE.format(
                                        start.startDate()));
                    }
                    generator.writeEndObject();
                }
                break;
            default:
                throw encodeFailure(
                        "Unsupported reference dataset "
                                + reference.dataset(),
                        null);
        }

        generator.writeEndArray();
        generator.writeEndObject();
    }

    private void writeSync(
            JsonGenerator generator,
            UpstreamData.Sync sync)
            throws IOException, CodecException {
        requireSyncValue(
                sync.timingSequence(),
                "timingSeq");
        requireNullableSyncValue(
                sync.raceDataRevision(),
                "RACE_DATA");
        requireNullableSyncValue(
                sync.stageStartTimesRevision(),
                "STAGE_START_TIMES");

        generator.writeStartObject();
        generator.writeNumberField(
                "timingSeq",
                sync.timingSequence());

        if (sync.raceDataRevision() != null
                || sync.stageStartTimesRevision() != null) {
            generator.writeObjectFieldStart("refs");
            if (sync.raceDataRevision() != null) {
                generator.writeNumberField(
                        "RACE_DATA",
                        sync.raceDataRevision());
            }
            if (sync.stageStartTimesRevision() != null) {
                generator.writeNumberField(
                        "STAGE_START_TIMES",
                        sync.stageStartTimesRevision());
            }
            generator.writeEndObject();
        }

        generator.writeEndObject();
    }

    private void writePing(
            JsonGenerator generator,
            UpstreamData.Ping ping)
            throws IOException, CodecException {
        if (ping.operation() == null) {
            throw encodeFailure(
                    "PING op must not be null",
                    null);
        }

        generator.writeStartObject();
        generator.writeStringField(
                "op",
                ping.operation().name());
        if (ping.mode() != null) {
            if (ping.mode() != UpstreamData.PingMode.HB) {
                throw encodeFailure(
                        "Unsupported PING mode "
                                + ping.mode(),
                        null);
            }
            generator.writeStringField(
                    "mode",
                    ping.mode().name());
        }
        if (ping.pingId() != null) {
            if (ping.pingId().trim().isEmpty()) {
                throw encodeFailure(
                        "pingId must not be blank",
                        null);
            }
            generator.writeStringField(
                    "pingId",
                    ping.pingId());
        }
        generator.writeEndObject();
    }

    @Override
    public UpstreamData decode(
            byte[] encodedMessage)
            throws CodecException {
        if (encodedMessage == null) {
            throw invalid(
                    "encodedMessage must not be null",
                    null);
        }
        if (hasUtf8Bom(encodedMessage)) {
            throw invalid(
                    "UTF-8 BOM is not allowed",
                    null);
        }

        try {
            JsonParser parser =
                    jsonFactory.createParser(
                            encodedMessage);
            if (parser.nextToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "UpstreamData must be one JSON object",
                        null);
            }

            Integer version = null;
            String type = null;
            String nodeId = null;
            byte[] payload = null;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                if (parser.getCurrentToken()
                        != JsonToken.FIELD_NAME) {
                    parser.close();
                    throw invalid(
                            "Expected UpstreamData field name",
                            null);
                }
                String field =
                        parser.getCurrentName();
                JsonToken value =
                        parser.nextToken();

                if ("v".equals(field)) {
                    if (version != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate v",
                                null);
                    }
                    version =
                            Integer.valueOf(
                                    readInt(
                                            parser,
                                            value,
                                            "v"));
                } else if ("type".equals(field)) {
                    if (type != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate type",
                                null);
                    }
                    type =
                            readString(
                                    parser,
                                    value,
                                    "type");
                } else if ("nodeId".equals(field)) {
                    if (nodeId != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate nodeId",
                                null);
                    }
                    nodeId =
                            readString(
                                    parser,
                                    value,
                                    "nodeId");
                } else if ("data".equals(field)) {
                    if (payload != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate data",
                                null);
                    }
                    payload =
                            captureCurrentValue(
                                    parser);
                } else {
                    parser.skipChildren();
                }
            }

            if (parser.nextToken() != null) {
                parser.close();
                throw invalid(
                        "Trailing JSON content",
                        null);
            }
            parser.close();

            if (version == null
                    || type == null
                    || payload == null) {
                throw invalid(
                        "v, type and data are required",
                        null);
            }
            if (version.intValue() != VERSION) {
                throw CodecException.unsupportedVersion(
                        version.intValue(),
                        "Unsupported UpstreamData version "
                                + version);
            }

            return decodeByType(
                    type,
                    nodeId,
                    payload);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(
                    "Invalid UpstreamData JSON",
                    ex);
        }
    }

    private UpstreamData decodeByType(
            String type,
            String nodeId,
            byte[] payload)
            throws CodecException {
        if ("TIMING_DATA".equals(type)) {
            return decodeTimingData(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("REFERENCE_DATA".equals(type)) {
            return decodeReferenceData(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("SYNC".equals(type)) {
            return decodeSync(
                    requireNodeId(nodeId, type),
                    payload);
        }
        if ("PING".equals(type)) {
            if (nodeId != null) {
                throw invalid(
                        "PING must not contain nodeId",
                        null);
            }
            return decodePing(payload);
        }

        throw CodecException.unsupportedMessageType(
                VERSION,
                type,
                "Unsupported UpstreamData type "
                        + type);
    }

    private UpstreamData.TimingDataTransfer decodeTimingData(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser =
                    jsonFactory.createParser(
                            payload);
            if (parser.nextToken()
                    != JsonToken.START_ARRAY) {
                parser.close();
                throw invalid(
                        "TIMING_DATA data must be an array",
                        null);
            }

            List<TimingData> records =
                    new ArrayList<TimingData>();
            while (parser.nextToken()
                    != JsonToken.END_ARRAY) {
                byte[] recordJson =
                        captureCurrentValue(
                                parser);
                final TimingData record;
                try {
                    record =
                            timingDataCodec.decode(
                                    recordJson);
                } catch (TimingDataCodec.CodecException ex) {
                    parser.close();
                    throw invalid(
                            "Invalid nested TimingData",
                            ex);
                }
                records.add(record);
            }
            if (parser.nextToken() != null) {
                parser.close();
                throw invalid(
                        "Trailing TIMING_DATA payload",
                        null);
            }
            parser.close();

            return DefaultUpstreamData.timingData(
                    nodeId,
                    records);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(
                    "Invalid TIMING_DATA payload",
                    ex);
        }
    }

    private UpstreamData.ReferenceData decodeReferenceData(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser =
                    jsonFactory.createParser(
                            payload);
            if (parser.nextToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "REFERENCE_DATA data must be an object",
                        null);
            }

            String dataset = null;
            Long revision = null;
            byte[] items = null;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                String field =
                        requireFieldName(parser);
                JsonToken value =
                        parser.nextToken();

                if ("dataset".equals(field)) {
                    if (dataset != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate dataset",
                                null);
                    }
                    dataset =
                            readString(
                                    parser,
                                    value,
                                    "dataset");
                } else if ("rev".equals(field)) {
                    if (revision != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate rev",
                                null);
                    }
                    revision =
                            Long.valueOf(
                                    readLong(
                                            parser,
                                            value,
                                            "rev"));
                } else if ("items".equals(field)) {
                    if (items != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate items",
                                null);
                    }
                    items =
                            captureCurrentValue(
                                    parser);
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (dataset == null
                    || revision == null
                    || items == null) {
                throw invalid(
                        "REFERENCE_DATA requires dataset, rev and items",
                        null);
            }
            requireReferenceRevision(
                    revision.longValue());

            if ("RACE_DATA".equals(dataset)) {
                return DefaultUpstreamData.raceData(
                        nodeId,
                        revision.longValue(),
                        decodeRaceItems(items));
            }
            if ("STAGE_START_TIMES".equals(dataset)) {
                return DefaultUpstreamData.stageStartTimes(
                        nodeId,
                        revision.longValue(),
                        decodeStageStartItems(items));
            }

            throw CodecException.unsupportedDataset(
                    VERSION,
                    dataset,
                    "Unsupported REFERENCE_DATA dataset "
                            + dataset);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(
                    "Invalid REFERENCE_DATA payload",
                    ex);
        }
    }

    private List<UpstreamData.RaceAssignment> decodeRaceItems(
            byte[] encodedItems)
            throws CodecException, IOException {
        JsonParser parser =
                jsonFactory.createParser(
                        encodedItems);
        if (parser.nextToken()
                != JsonToken.START_ARRAY) {
            parser.close();
            throw invalid(
                    "RACE_DATA items must be an array",
                    null);
        }

        List<UpstreamData.RaceAssignment> result =
                new ArrayList<UpstreamData.RaceAssignment>();
        while (parser.nextToken()
                != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "RACE_DATA item must be an object",
                        null);
            }
            String registrationId = null;
            String teamId = null;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                String field =
                        requireFieldName(parser);
                JsonToken value =
                        parser.nextToken();
                if ("regId".equals(field)) {
                    if (registrationId != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate regId",
                                null);
                    }
                    registrationId =
                            readString(
                                    parser,
                                    value,
                                    "regId");
                } else if ("teamId".equals(field)) {
                    if (teamId != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate teamId",
                                null);
                    }
                    teamId =
                            readString(
                                    parser,
                                    value,
                                    "teamId");
                } else {
                    parser.skipChildren();
                }
            }

            if (registrationId == null
                    || teamId == null) {
                parser.close();
                throw invalid(
                        "RACE_DATA item requires regId and teamId",
                        null);
            }

            result.add(
                    new UpstreamData.RaceAssignment(
                            new RegistrationId(registrationId),
                            new TeamId(teamId)));
        }
        parser.close();
        return result;
    }

    private List<UpstreamData.StageStartDefinition> decodeStageStartItems(
            byte[] encodedItems)
            throws CodecException, IOException {
        JsonParser parser =
                jsonFactory.createParser(
                        encodedItems);
        if (parser.nextToken()
                != JsonToken.START_ARRAY) {
            parser.close();
            throw invalid(
                    "STAGE_START_TIMES items must be an array",
                    null);
        }

        List<UpstreamData.StageStartDefinition> result =
                new ArrayList<UpstreamData.StageStartDefinition>();
        while (parser.nextToken()
                != JsonToken.END_ARRAY) {
            if (parser.getCurrentToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "STAGE_START_TIMES item must be an object",
                        null);
            }

            String teamId = null;
            String startTime = null;
            String startDate = null;
            boolean startDateSeen = false;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                String field =
                        requireFieldName(parser);
                JsonToken value =
                        parser.nextToken();
                if ("teamId".equals(field)) {
                    if (teamId != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate teamId",
                                null);
                    }
                    teamId =
                            readString(
                                    parser,
                                    value,
                                    "teamId");
                } else if ("startTime".equals(field)) {
                    if (startTime != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate startTime",
                                null);
                    }
                    startTime =
                            readString(
                                    parser,
                                    value,
                                    "startTime");
                } else if ("startDate".equals(field)) {
                    if (startDateSeen) {
                        parser.close();
                        throw invalid(
                                "Duplicate startDate",
                                null);
                    }
                    startDateSeen = true;
                    startDate =
                            readString(
                                    parser,
                                    value,
                                    "startDate");
                } else {
                    parser.skipChildren();
                }
            }

            if (teamId == null
                    || startTime == null) {
                parser.close();
                throw invalid(
                        "STAGE_START_TIMES item requires teamId and startTime",
                        null);
            }

            LocalDate parsedDate =
                    startDate == null
                            ? null
                            : parseStartDate(startDate);
            result.add(
                    new UpstreamData.StageStartDefinition(
                            new TeamId(teamId),
                            parseStartTime(startTime),
                            parsedDate));
        }
        parser.close();
        return result;
    }

    private UpstreamData.Sync decodeSync(
            NodeId nodeId,
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser =
                    jsonFactory.createParser(
                            payload);
            if (parser.nextToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "SYNC data must be an object",
                        null);
            }

            Long timingSequence = null;
            Long raceRevision = null;
            Long stageRevision = null;
            boolean refsSeen = false;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                String field =
                        requireFieldName(parser);
                JsonToken value =
                        parser.nextToken();
                if ("timingSeq".equals(field)) {
                    if (timingSequence != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate timingSeq",
                                null);
                    }
                    timingSequence =
                            Long.valueOf(
                                    readLong(
                                            parser,
                                            value,
                                            "timingSeq"));
                } else if ("refs".equals(field)) {
                    if (refsSeen) {
                        parser.close();
                        throw invalid(
                                "Duplicate refs",
                                null);
                    }
                    refsSeen = true;
                    SyncReferences refs =
                            readSyncReferences(
                                    parser,
                                    value);
                    raceRevision =
                            refs.raceDataRevision;
                    stageRevision =
                            refs.stageStartTimesRevision;
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (timingSequence == null) {
                throw invalid(
                        "SYNC requires timingSeq",
                        null);
            }

            return DefaultUpstreamData.sync(
                    nodeId,
                    timingSequence.longValue(),
                    raceRevision,
                    stageRevision);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(
                    "Invalid SYNC payload",
                    ex);
        }
    }

    private SyncReferences readSyncReferences(
            JsonParser parser,
            JsonToken token)
            throws IOException, CodecException {
        if (token != JsonToken.START_OBJECT) {
            throw invalid(
                    "SYNC refs must be an object",
                    null);
        }

        Long race = null;
        Long stage = null;
        boolean raceSeen = false;
        boolean stageSeen = false;

        while (parser.nextToken()
                != JsonToken.END_OBJECT) {
            String field =
                    requireFieldName(parser);
            JsonToken value =
                    parser.nextToken();
            if ("RACE_DATA".equals(field)) {
                if (raceSeen) {
                    throw invalid(
                            "Duplicate RACE_DATA sync ref",
                            null);
                }
                raceSeen = true;
                race =
                        Long.valueOf(
                                readLong(
                                        parser,
                                        value,
                                        "refs.RACE_DATA"));
                requireSyncValue(
                        race.longValue(),
                        "refs.RACE_DATA");
            } else if ("STAGE_START_TIMES".equals(field)) {
                if (stageSeen) {
                    throw invalid(
                            "Duplicate STAGE_START_TIMES sync ref",
                            null);
                }
                stageSeen = true;
                stage =
                        Long.valueOf(
                                readLong(
                                        parser,
                                        value,
                                        "refs.STAGE_START_TIMES"));
                requireSyncValue(
                        stage.longValue(),
                        "refs.STAGE_START_TIMES");
            } else {
                parser.skipChildren();
            }
        }

        return new SyncReferences(
                race,
                stage);
    }

    private UpstreamData.Ping decodePing(
            byte[] payload)
            throws CodecException {
        try {
            JsonParser parser =
                    jsonFactory.createParser(
                            payload);
            if (parser.nextToken()
                    != JsonToken.START_OBJECT) {
                parser.close();
                throw invalid(
                        "PING data must be an object",
                        null);
            }

            String operation = null;
            String mode = null;
            String pingId = null;
            boolean modeSeen = false;
            boolean pingIdSeen = false;

            while (parser.nextToken()
                    != JsonToken.END_OBJECT) {
                String field =
                        requireFieldName(parser);
                JsonToken value =
                        parser.nextToken();
                if ("op".equals(field)) {
                    if (operation != null) {
                        parser.close();
                        throw invalid(
                                "Duplicate op",
                                null);
                    }
                    operation =
                            readString(
                                    parser,
                                    value,
                                    "op");
                } else if ("mode".equals(field)) {
                    if (modeSeen) {
                        parser.close();
                        throw invalid(
                                "Duplicate mode",
                                null);
                    }
                    modeSeen = true;
                    mode =
                            readString(
                                    parser,
                                    value,
                                    "mode");
                } else if ("pingId".equals(field)) {
                    if (pingIdSeen) {
                        parser.close();
                        throw invalid(
                                "Duplicate pingId",
                                null);
                    }
                    pingIdSeen = true;
                    pingId =
                            readString(
                                    parser,
                                    value,
                                    "pingId");
                } else {
                    parser.skipChildren();
                }
            }
            parser.close();

            if (operation == null) {
                throw invalid(
                        "PING requires op",
                        null);
            }

            final UpstreamData.PingOperation parsedOperation;
            try {
                parsedOperation =
                        UpstreamData.PingOperation.valueOf(
                                operation);
            } catch (IllegalArgumentException ex) {
                throw invalid(
                        "Unsupported PING op "
                                + operation,
                        ex);
            }

            UpstreamData.PingMode parsedMode = null;
            if (mode != null) {
                if (!"HB".equals(mode)) {
                    throw invalid(
                            "Unsupported PING mode "
                                    + mode,
                            null);
                }
                parsedMode =
                        UpstreamData.PingMode.HB;
            }

            return DefaultUpstreamData.ping(
                    parsedOperation,
                    parsedMode,
                    pingId);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | IllegalArgumentException ex) {
            throw invalid(
                    "Invalid PING payload",
                    ex);
        }
    }

    private byte[] captureCurrentValue(
            JsonParser parser)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        JsonGenerator generator =
                jsonFactory.createGenerator(
                        output);
        generator.copyCurrentStructure(
                parser);
        generator.close();
        return output.toByteArray();
    }

    private static String requireFieldName(
            JsonParser parser)
            throws CodecException, IOException {
        if (parser.getCurrentToken()
                != JsonToken.FIELD_NAME) {
            throw invalid(
                    "Expected field name",
                    null);
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
                    field
                            + " must be a string",
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
                    field
                            + " must be an integer",
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
                    field
                            + " must be an integer",
                    null);
        }
        return parser.getLongValue();
    }

    private static NodeId requireNodeId(
            String nodeId,
            String type)
            throws CodecException {
        if (nodeId == null) {
            throw invalid(
                    type
                            + " requires nodeId",
                    null);
        }
        try {
            return new NodeId(nodeId);
        } catch (IllegalArgumentException ex) {
            throw invalid(
                    "Invalid nodeId",
                    ex);
        }
    }

    private static void requireReferenceRevision(
            long revision)
            throws CodecException {
        if (revision < 1L
                || revision > TimingData.MAX_SEQUENCE_NUMBER) {
            throw invalid(
                    "reference rev must be in range 1.."
                            + TimingData.MAX_SEQUENCE_NUMBER,
                    null);
        }
    }

    private static void requireSyncValue(
            long value,
            String field)
            throws CodecException {
        if (value < 0L
                || value > TimingData.MAX_SEQUENCE_NUMBER) {
            throw invalid(
                    field
                            + " must be in range 0.."
                            + TimingData.MAX_SEQUENCE_NUMBER,
                    null);
        }
    }

    private static void requireNullableSyncValue(
            Long value,
            String field)
            throws CodecException {
        if (value != null) {
            requireSyncValue(
                    value.longValue(),
                    field);
        }
    }

    private static String formatStartTime(
            LocalTime time)
            throws CodecException {
        if (time == null) {
            throw encodeFailure(
                    "startTime must not be null",
                    null);
        }
        int nanos =
                time.getNano();
        if (nanos == 0) {
            return START_TIME_SECONDS.format(
                    time);
        }
        if (nanos % 10000000 != 0) {
            throw encodeFailure(
                    "startTime finer than centisecond precision is not representable",
                    null);
        }
        return START_TIME_CENTISECONDS.format(
                time);
    }

    private static LocalTime parseStartTime(
            String text)
            throws CodecException {
        try {
            return LocalTime.parse(
                    text,
                    START_TIME_READER);
        } catch (DateTimeParseException ex) {
            throw invalid(
                    "Invalid startTime "
                            + text,
                    ex);
        }
    }

    private static LocalDate parseStartDate(
            String text)
            throws CodecException {
        try {
            return LocalDate.parse(
                    text,
                    START_DATE);
        } catch (DateTimeParseException ex) {
            throw invalid(
                    "Invalid startDate "
                            + text,
                    ex);
        }
    }

    private static boolean hasUtf8Bom(
            byte[] data) {
        return data.length >= 3
                && (data[0] & 0xff) == 0xef
                && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf;
    }

    private static <T> T requireType(
            UpstreamData data,
            Class<T> type,
            String name)
            throws CodecException {
        if (!type.isInstance(data)) {
            throw encodeFailure(
                    name
                            + " data has incompatible semantic type",
                    null);
        }
        return type.cast(data);
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

    private static final class SyncReferences {
        private final Long raceDataRevision;
        private final Long stageStartTimesRevision;

        private SyncReferences(
                Long raceDataRevision,
                Long stageStartTimesRevision) {
            this.raceDataRevision = raceDataRevision;
            this.stageStartTimesRevision = stageStartTimesRevision;
        }
    }
}
