package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingRecord;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical IF-05 v1 JSON codec.
 *
 * <p>The codec reads and writes {@link TimingRecord} directly. No secondary
 * Java object model is constructed on the persistence or API path.</p>
 */
public final class DefaultTimingDataCodec implements TimingDataCodec {
    private static final int VERSION = 1;

    private static final DateTimeFormatter EFFECTIVE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SS'Z'")
                    .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter RECORDED_TIME_FORMAT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'")
                    .withZone(ZoneOffset.UTC);

    private static final String AUTO_REG = "AUTO_REG";
    private static final String MAN_REG = "MAN_REG";
    private static final String NODE_INFO = "NODE_INFO";
    private static final String ADD = "ADD";
    private static final String REV = "REV";
    private static final String AUTO = "AUTO";
    private static final String MAN = "MAN";
    private static final String OPEN = "OPEN";
    private static final String CLOSE = "CLOSE";

    private final JsonFactory jsonFactory = new JsonFactory();

    @Override
    public byte[] encode(TimingRecord record) throws CodecException {
        if (record == null) {
            throw encodeFailure("TimingRecord must not be null");
        }

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(192);
            JsonGenerator generator = jsonFactory.createGenerator(output);
            try {
                generator.writeStartObject();
                generator.writeNumberField("v", VERSION);
                generator.writeStringField("nodeId", String.valueOf(record.nodeId()));
                generator.writeNumberField("seqNr", record.sequenceNumber());
                generator.writeNumberField("locId", record.locationId());

                switch (record.kind()) {
                    case AUTOMATIC_REGISTRATION:
                        writeAutomatic(generator, record);
                        break;
                    case MANUAL_REGISTRATION:
                        writeManual(generator, record);
                        break;
                    case NODE_OPEN:
                        writeLifecycle(generator, record, OPEN);
                        break;
                    case NODE_CLOSE:
                        writeLifecycle(generator, record, CLOSE);
                        break;
                    default:
                        throw encodeFailure("unsupported TimingRecord kind " + record.kind());
                }

                generator.writeStringField(
                        "recTime",
                        formatRecordedTime(record.recordedAtMillis()));
                generator.writeEndObject();
            } finally {
                generator.close();
            }
            return output.toByteArray();
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new CodecException(
                    CodecException.Reason.ENCODE_FAILURE,
                    "Could not encode TimingRecord as IF-05 v1 JSON",
                    ex);
        }
    }

    private static void writeAutomatic(
            JsonGenerator generator,
            TimingRecord record)
            throws IOException, CodecException {
        generator.writeStringField("recType", AUTO_REG);
        generator.writeStringField("time", formatEffectiveTime(record.effectiveTimeMillis()));
        generator.writeStringField(
                "regId",
                RegistrationId.fromPackedValue(record.registrationId()).value());
        writeCodes(generator, actionCode(record.action()));

        TimingRecord.TagSource tagSource = record.tagSource();
        TimingRecord.AutomaticTimeSource timeSource = record.automaticTimeSource();
        if (tagSource != null && timeSource != null) {
            generator.writeStringField("tagSrc", tagSource.name());
            generator.writeStringField("timeSrc", timeSource.name());
        } else if (tagSource != null || timeSource != null) {
            throw encodeFailure("automatic tagSrc and timeSrc must appear together");
        }
    }

    private static void writeManual(
            JsonGenerator generator,
            TimingRecord record)
            throws IOException, CodecException {
        TimingRecord.ManualTimeSource timeSource = record.manualTimeSource();
        if (timeSource == null) {
            throw encodeFailure("manual timeSource must not be null");
        }

        generator.writeStringField("recType", MAN_REG);
        generator.writeStringField("time", formatEffectiveTime(record.effectiveTimeMillis()));
        generator.writeStringField(
                "regId",
                RegistrationId.fromPackedValue(record.registrationId()).value());
        writeCodes(
                generator,
                actionCode(record.action()),
                timeSource == TimingRecord.ManualTimeSource.AUTOMATIC ? AUTO : MAN);
    }

    private static void writeLifecycle(
            JsonGenerator generator,
            TimingRecord record,
            String code)
            throws IOException {
        generator.writeStringField("recType", NODE_INFO);
        generator.writeStringField("time", formatEffectiveTime(record.effectiveTimeMillis()));
        writeCodes(generator, code);
    }

    private static void writeCodes(JsonGenerator generator, String... codes)
            throws IOException {
        generator.writeArrayFieldStart("code");
        for (String code : codes) {
            generator.writeString(code);
        }
        generator.writeEndArray();
    }

    private static String actionCode(TimingRecord.Action action) throws CodecException {
        if (action == TimingRecord.Action.ADD) {
            return ADD;
        }
        if (action == TimingRecord.Action.REV) {
            return REV;
        }
        throw encodeFailure("registration action must be ADD or REV");
    }

    private static String formatEffectiveTime(long epochMillis) {
        return EFFECTIVE_TIME_FORMAT.format(Instant.ofEpochMilli(epochMillis));
    }

    private static String formatRecordedTime(long epochMillis) {
        Instant instant = Instant.ofEpochMilli(epochMillis);
        long subCentisecondNanos = instant.getNano() % 10_000_000L;
        return RECORDED_TIME_FORMAT.format(instant.minusNanos(subCentisecondNanos));
    }

    @Override
    public TimingRecord decode(byte[] encodedRecord) throws CodecException {
        if (encodedRecord == null || encodedRecord.length == 0) {
            throw invalid("encoded record must not be empty");
        }
        if (hasUtf8Bom(encodedRecord)) {
            throw invalid("canonical IF-05 JSON must not contain a UTF-8 BOM");
        }

        try {
            Fields fields = readFields(encodedRecord);
            return toRecord(fields);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new CodecException(
                    CodecException.Reason.INVALID_DATA,
                    "Could not decode IF-05 v1 JSON record",
                    ex);
        }
    }

    private Fields readFields(byte[] encodedRecord)
            throws IOException, CodecException {
        Fields fields = new Fields();
        JsonParser parser = jsonFactory.createParser(encodedRecord);
        try {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw invalid("TimingData record must be one JSON object");
            }

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw invalid("expected JSON object member name");
                }

                String name = parser.currentName();
                JsonToken token = parser.nextToken();
                if (token == null) {
                    throw invalid("missing value for JSON member " + name);
                }

                switch (name) {
                    case "v":
                        fields.version = readInt(parser, token, name, fields.versionSeen);
                        fields.versionSeen = true;
                        break;
                    case "nodeId":
                        fields.nodeId = readString(parser, token, name, fields.nodeIdSeen);
                        fields.nodeIdSeen = true;
                        break;
                    case "seqNr":
                        fields.sequence = readLong(parser, token, name, fields.sequenceSeen);
                        fields.sequenceSeen = true;
                        break;
                    case "locId":
                        fields.locationId = readInt(parser, token, name, fields.locationSeen);
                        fields.locationSeen = true;
                        break;
                    case "recType":
                        fields.recordType = readString(parser, token, name, fields.recordTypeSeen);
                        fields.recordTypeSeen = true;
                        break;
                    case "time":
                        fields.effectiveTime = readString(parser, token, name, fields.effectiveSeen);
                        fields.effectiveSeen = true;
                        break;
                    case "regId":
                        fields.registrationId =
                                readString(parser, token, name, fields.registrationSeen);
                        fields.registrationSeen = true;
                        break;
                    case "code":
                        fields.codes = readStringArray(parser, token, name, fields.codesSeen);
                        fields.codesSeen = true;
                        break;
                    case "tagSrc":
                        fields.tagSource = readString(parser, token, name, fields.tagSourceSeen);
                        fields.tagSourceSeen = true;
                        break;
                    case "timeSrc":
                        fields.timeSource = readString(parser, token, name, fields.timeSourceSeen);
                        fields.timeSourceSeen = true;
                        break;
                    case "recTime":
                        fields.recordedAt = readString(parser, token, name, fields.recordedSeen);
                        fields.recordedSeen = true;
                        break;
                    default:
                        parser.skipChildren();
                        break;
                }
            }

            if (parser.nextToken() != null) {
                throw invalid("unexpected data after TimingData JSON object");
            }
            return fields;
        } finally {
            parser.close();
        }
    }

    private static TimingRecord toRecord(Fields fields) throws CodecException {
        require(fields.versionSeen, "v");
        if (fields.version != VERSION) {
            throw CodecException.unsupportedVersion(
                    fields.version,
                    "unsupported IF-05 TimingData version " + fields.version);
        }

        Common common = common(fields);
        require(fields.recordTypeSeen, "recType");

        if (NODE_INFO.equals(fields.recordType)) {
            rejectProvenance(fields);
            if (fields.registrationSeen) {
                throw invalid("NODE_INFO must not contain regId");
            }
            require(fields.codesSeen, "code");
            if (exact(fields.codes, OPEN)) {
                return TimingRecord.opened(
                        common.nodeId, common.sequence, common.locationId,
                        common.effectiveTimeMillis, common.recordedAtMillis);
            }
            if (exact(fields.codes, CLOSE)) {
                return TimingRecord.closed(
                        common.nodeId, common.sequence, common.locationId,
                        common.effectiveTimeMillis, common.recordedAtMillis);
            }
            throw invalid("NODE_INFO code must be exactly OPEN or CLOSE");
        }

        if (!AUTO_REG.equals(fields.recordType) && !MAN_REG.equals(fields.recordType)) {
            throw CodecException.unsupportedRecordType(
                    VERSION,
                    new TimingRecord.RecordKey(common.nodeId, common.sequence),
                    new LocationId(common.locationId),
                    fields.recordType,
                    new TimingTimestamp(Instant.ofEpochMilli(common.effectiveTimeMillis)),
                    new TimingTimestamp(Instant.ofEpochMilli(common.recordedAtMillis)),
                    "unsupported IF-05 development-v1 recType " + fields.recordType);
        }

        require(fields.registrationSeen, "regId");
        require(fields.codesSeen, "code");
        int registrationId = registrationId(fields.registrationId);

        if (AUTO_REG.equals(fields.recordType)) {
            if (fields.tagSourceSeen != fields.timeSourceSeen) {
                throw invalid("tagSrc and timeSrc must appear together");
            }

            TimingRecord.TagSource tagSource = null;
            TimingRecord.AutomaticTimeSource timeSource = null;
            if (fields.tagSourceSeen) {
                try {
                    tagSource = TimingRecord.TagSource.valueOf(fields.tagSource);
                    timeSource = TimingRecord.AutomaticTimeSource.valueOf(fields.timeSource);
                } catch (IllegalArgumentException ex) {
                    throw invalid("unsupported tagSrc/timeSrc value", ex);
                }
            }

            TimingRecord.Action action;
            if (exact(fields.codes, ADD)) {
                action = TimingRecord.Action.ADD;
            } else if (exact(fields.codes, REV)) {
                action = TimingRecord.Action.REV;
            } else {
                throw invalid("AUTO_REG code must be exactly ADD or REV");
            }

            return TimingRecord.automatic(
                    common.nodeId, common.sequence, common.locationId,
                    common.effectiveTimeMillis, common.recordedAtMillis,
                    registrationId, action, tagSource, timeSource);
        }

        rejectProvenance(fields);
        if (fields.codes.size() != 2) {
            throw invalid("MAN_REG code must contain exactly one action and one time source");
        }
        TimingRecord.Action action = oneOfAction(fields.codes);
        TimingRecord.ManualTimeSource timeSource = oneOfManualTimeSource(fields.codes);
        return TimingRecord.manual(
                common.nodeId, common.sequence, common.locationId,
                common.effectiveTimeMillis, common.recordedAtMillis,
                registrationId, action, timeSource);
    }

    private static Common common(Fields fields) throws CodecException {
        require(fields.nodeIdSeen, "nodeId");
        require(fields.sequenceSeen, "seqNr");
        require(fields.locationSeen, "locId");
        require(fields.effectiveSeen, "time");
        require(fields.recordedSeen, "recTime");

        try {
            NodeId nodeId = new NodeId(fields.nodeId);
            LocationId locationId = new LocationId(fields.locationId);
            if (fields.sequence < 1L || fields.sequence > TimingRecord.MAX_SEQUENCE_NUMBER) {
                throw new IllegalArgumentException("sequence out of range");
            }
            long effective = Instant.parse(fields.effectiveTime).toEpochMilli();
            long recorded = Instant.parse(fields.recordedAt).toEpochMilli();
            return new Common(
                    nodeId.value().charAt(0),
                    fields.sequence,
                    locationId.value(),
                    effective,
                    recorded);
        } catch (RuntimeException ex) {
            throw invalid("common IF-05 TimingData envelope is invalid", ex);
        }
    }

    private static TimingRecord.Action oneOfAction(List<String> codes)
            throws CodecException {
        boolean add = codes.contains(ADD);
        boolean rev = codes.contains(REV);
        if (add == rev) {
            throw invalid("MAN_REG code must contain exactly one of ADD or REV");
        }
        return add ? TimingRecord.Action.ADD : TimingRecord.Action.REV;
    }

    private static TimingRecord.ManualTimeSource oneOfManualTimeSource(List<String> codes)
            throws CodecException {
        boolean auto = codes.contains(AUTO);
        boolean man = codes.contains(MAN);
        if (auto == man) {
            throw invalid("MAN_REG code must contain exactly one of AUTO or MAN");
        }
        return auto
                ? TimingRecord.ManualTimeSource.AUTOMATIC
                : TimingRecord.ManualTimeSource.OPERATOR_ENTERED;
    }

    private static void rejectProvenance(Fields fields) throws CodecException {
        if (fields.tagSourceSeen || fields.timeSourceSeen) {
            throw invalid("tagSrc/timeSrc are only valid on AUTO_REG");
        }
    }

    private static int registrationId(String value) throws CodecException {
        try {
            return new RegistrationId(value).packedValue();
        } catch (RuntimeException ex) {
            throw invalid("regId is invalid", ex);
        }
    }

    private static boolean exact(List<String> actual, String expected) {
        return actual != null && actual.size() == 1 && expected.equals(actual.get(0));
    }

    private static String readString(
            JsonParser parser, JsonToken token, String name, boolean seen)
            throws IOException, CodecException {
        duplicate(name, seen);
        if (token != JsonToken.VALUE_STRING) {
            throw invalid(name + " must be a JSON string");
        }
        return parser.getText();
    }

    private static List<String> readStringArray(
            JsonParser parser, JsonToken token, String name, boolean seen)
            throws IOException, CodecException {
        duplicate(name, seen);
        if (token != JsonToken.START_ARRAY) {
            throw invalid(name + " must be a JSON string array");
        }

        List<String> values = new ArrayList<String>();
        JsonToken item;
        while ((item = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (item != JsonToken.VALUE_STRING) {
                throw invalid(name + " must contain only JSON strings");
            }
            String value = parser.getText();
            if (value == null || value.trim().isEmpty()) {
                throw invalid(name + " must not contain blank codes");
            }
            if (values.contains(value)) {
                throw invalid(name + " must not contain duplicate code " + value);
            }
            values.add(value);
        }
        if (values.isEmpty()) {
            throw invalid(name + " must contain at least one code");
        }
        return values;
    }

    private static int readInt(
            JsonParser parser, JsonToken token, String name, boolean seen)
            throws IOException, CodecException {
        duplicate(name, seen);
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(name + " must be a JSON integer");
        }
        return parser.getIntValue();
    }

    private static long readLong(
            JsonParser parser, JsonToken token, String name, boolean seen)
            throws IOException, CodecException {
        duplicate(name, seen);
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(name + " must be a JSON integer");
        }
        return parser.getLongValue();
    }

    private static void duplicate(String name, boolean seen) throws CodecException {
        if (seen) {
            throw invalid("duplicate JSON member " + name);
        }
    }

    private static void require(boolean present, String name) throws CodecException {
        if (!present) {
            throw invalid("missing required JSON member " + name);
        }
    }

    private static boolean hasUtf8Bom(byte[] data) {
        return data.length >= 3
                && (data[0] & 0xff) == 0xef
                && (data[1] & 0xff) == 0xbb
                && (data[2] & 0xff) == 0xbf;
    }

    private static CodecException encodeFailure(String message) {
        return new CodecException(CodecException.Reason.ENCODE_FAILURE, message);
    }

    private static CodecException invalid(String message) {
        return new CodecException(CodecException.Reason.INVALID_DATA, message);
    }

    private static CodecException invalid(String message, Throwable cause) {
        return new CodecException(CodecException.Reason.INVALID_DATA, message, cause);
    }

    private static final class Common {
        private final char nodeId;
        private final long sequence;
        private final int locationId;
        private final long effectiveTimeMillis;
        private final long recordedAtMillis;

        private Common(
                char nodeId,
                long sequence,
                int locationId,
                long effectiveTimeMillis,
                long recordedAtMillis) {
            this.nodeId = nodeId;
            this.sequence = sequence;
            this.locationId = locationId;
            this.effectiveTimeMillis = effectiveTimeMillis;
            this.recordedAtMillis = recordedAtMillis;
        }
    }

    private static final class Fields {
        private int version;
        private boolean versionSeen;
        private String nodeId;
        private boolean nodeIdSeen;
        private long sequence;
        private boolean sequenceSeen;
        private int locationId;
        private boolean locationSeen;
        private String recordType;
        private boolean recordTypeSeen;
        private String effectiveTime;
        private boolean effectiveSeen;
        private String registrationId;
        private boolean registrationSeen;
        private List<String> codes;
        private boolean codesSeen;
        private String tagSource;
        private boolean tagSourceSeen;
        private String timeSource;
        private boolean timeSourceSeen;
        private String recordedAt;
        private boolean recordedSeen;
    }
}
