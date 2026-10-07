package io.github.brainboxemb.eventtiming.timingdata.defaultprofile;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.LocationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.RegistrationId;
import io.github.brainboxemb.eventtiming.timingdata.TimingData;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataCodec;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataFactory;
import io.github.brainboxemb.eventtiming.timingdata.TimingDataTypes.NodeId;
import io.github.brainboxemb.eventtiming.timingdata.TimingTimestamp;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Canonical IF-05 development-v1 JSON codec for the built-in default/reference
 * profile.
 *
 * <p>IF-05 uses integer format versions. Odd versions are development/unstable
 * and even versions are released/stable. Version 1 is therefore deliberately a
 * development contract and may still change before the first stable version 2.</p>
 *
 * <p>The codec owns one JSON object only. It deliberately does not add or remove
 * JSON Lines terminators: file framing, incomplete-tail handling and recovery
 * belong to the TimingData store.</p>
 *
 * <p>The canonical writer uses compact member names and deterministic member/code
 * order. The reader does not depend on object member order and ignores additional
 * members after safely skipping their JSON value.</p>
 */
public final class DefaultTimingDataCodec implements TimingDataCodec {
    private static final int VERSION = 1;

    private static final String RECORD_TYPE_AUTO_REG = "AUTO_REG";
    private static final String RECORD_TYPE_MAN_REG = "MAN_REG";
    private static final String RECORD_TYPE_NODE_INFO = "NODE_INFO";

    private static final String CODE_OPEN = "OPEN";
    private static final String CODE_CLOSE = "CLOSE";

    private static final String CODE_ADD = "ADD";
    private static final String CODE_AUTO = "AUTO";
    private static final String CODE_MAN = "MAN";

    private final JsonFactory jsonFactory;
    private final TimingDataFactory timingDataFactory;

    /** Creates the canonical codec backed by the built-in default TimingData factory. */
    public DefaultTimingDataCodec() {
        this.jsonFactory = new JsonFactory();
        this.timingDataFactory = new DefaultTimingDataFactory();
    }

    /**
     * Encodes one semantic TimingData value as canonical compact UTF-8 JSON.
     *
     * <p>No line terminator is emitted. The caller that owns JSON Lines framing
     * appends LF after the complete encoded record has been accepted for append.</p>
     */
    @Override
    public byte[] encode(TimingData data) throws CodecException {
        if (data == null) {
            throw new CodecException(
                    CodecException.Reason.ENCODE_FAILURE,
                    "TimingData must not be null");
        }

        validateCommonForEncode(data);

        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(192);
            JsonGenerator generator = jsonFactory.createGenerator(output);
            try {
                generator.writeStartObject();
                generator.writeNumberField("v", VERSION);
                generator.writeStringField("nodeId", data.timingNodeId().value());
                generator.writeNumberField("seqNr", data.sequenceNumber());
                generator.writeNumberField("locId", data.locationId().value());

                if (data instanceof TimingData.AutomaticRegistration) {
                    writeAutomatic(
                            generator,
                            (TimingData.AutomaticRegistration) data);
                } else if (data instanceof TimingData.ManualRegistration) {
                    writeManual(
                            generator,
                            (TimingData.ManualRegistration) data);
                } else if (data instanceof TimingData.NodeOpen) {
                    writeLifecycle(
                            generator,
                            data,
                            CODE_OPEN);
                } else if (data instanceof TimingData.NodeClose) {
                    writeLifecycle(
                            generator,
                            data,
                            CODE_CLOSE);
                } else {
                    throw new CodecException(
                            CodecException.Reason.ENCODE_FAILURE,
                            "Default profile cannot encode TimingData type "
                                    + data.getClass().getName());
                }

                generator.writeStringField("recTime", data.recordedAt().toString());
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
                    "Could not encode TimingData as IF-05 development-v1 JSON",
                    ex);
        }
    }

    private static void validateCommonForEncode(TimingData data) throws CodecException {
        try {
            new TimingDataFactory.Context(
                    data.timingNodeId(),
                    data.sequenceNumber(),
                    data.locationId(),
                    data.effectiveTime(),
                    data.recordedAt());
        } catch (RuntimeException ex) {
            throw new CodecException(
                    CodecException.Reason.ENCODE_FAILURE,
                    "TimingData contains invalid common IF-05 values",
                    ex);
        }
    }

    private static void writeAutomatic(
            JsonGenerator generator,
            TimingData.AutomaticRegistration data)
            throws IOException, CodecException {
        RegistrationId registrationId = requireRegistrationId(data.registrationId());
        generator.writeStringField("recType", RECORD_TYPE_AUTO_REG);
        generator.writeStringField("time", data.effectiveTime().toString());
        generator.writeStringField("regId", registrationId.value());
        writeCodes(generator, CODE_ADD);
    }

    private static void writeManual(
            JsonGenerator generator,
            TimingData.ManualRegistration data)
            throws IOException, CodecException {
        RegistrationId registrationId = requireRegistrationId(data.registrationId());
        TimingData.ManualTimeSource timeSource = data.timeSource();
        if (timeSource == null) {
            throw new CodecException(
                    CodecException.Reason.ENCODE_FAILURE,
                    "manual timeSource must not be null");
        }

        final String timeCode;
        switch (timeSource) {
            case SYSTEM_ASSIGNED:
                timeCode = CODE_AUTO;
                break;
            case OPERATOR_ENTERED:
                timeCode = CODE_MAN;
                break;
            default:
                throw new CodecException(
                        CodecException.Reason.ENCODE_FAILURE,
                        "unsupported manual timeSource " + timeSource);
        }

        generator.writeStringField("recType", RECORD_TYPE_MAN_REG);
        generator.writeStringField("time", data.effectiveTime().toString());
        generator.writeStringField("regId", registrationId.value());
        writeCodes(generator, CODE_ADD, timeCode);
    }

    private static void writeLifecycle(
            JsonGenerator generator,
            TimingData data,
            String code)
            throws IOException {
        generator.writeStringField("recType", RECORD_TYPE_NODE_INFO);
        generator.writeStringField("time", data.effectiveTime().toString());
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

    private static RegistrationId requireRegistrationId(RegistrationId registrationId)
            throws CodecException {
        if (registrationId == null) {
            throw new CodecException(
                    CodecException.Reason.ENCODE_FAILURE,
                    "registrationId must not be null");
        }
        return registrationId;
    }

    @Override
    public TimingData decode(byte[] encodedRecord) throws CodecException {
        if (encodedRecord == null || encodedRecord.length == 0) {
            throw invalid("encoded record must not be empty");
        }
        if (hasUtf8Bom(encodedRecord)) {
            throw invalid("canonical IF-05 JSON must not contain a UTF-8 BOM");
        }

        try {
            DecodedFields fields = readFields(encodedRecord);
            return toTimingData(fields);
        } catch (CodecException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw new CodecException(
                    CodecException.Reason.INVALID_DATA,
                    "Could not decode IF-05 development-v1 JSON record",
                    ex);
        }
    }

    private DecodedFields readFields(byte[] encodedRecord)
            throws IOException, CodecException {
        DecodedFields fields = new DecodedFields();

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
                JsonToken valueToken = parser.nextToken();
                if (valueToken == null) {
                    throw invalid("missing value for JSON member " + name);
                }

                switch (name) {
                    case "v":
                        fields.version =
                                readInt(parser, valueToken, name, fields.versionSeen);
                        fields.versionSeen = true;
                        break;
                    case "nodeId":
                        fields.nodeId =
                                readString(parser, valueToken, name, fields.nodeIdSeen);
                        fields.nodeIdSeen = true;
                        break;
                    case "seqNr":
                        fields.sequenceNumber =
                                readLong(parser, valueToken, name, fields.sequenceNumberSeen);
                        fields.sequenceNumberSeen = true;
                        break;
                    case "locId":
                        fields.locationId =
                                readInt(parser, valueToken, name, fields.locationIdSeen);
                        fields.locationIdSeen = true;
                        break;
                    case "recType":
                        fields.recordType =
                                readString(parser, valueToken, name, fields.recordTypeSeen);
                        fields.recordTypeSeen = true;
                        break;
                    case "time":
                        fields.effectiveTimeText =
                                readString(parser, valueToken, name, fields.effectiveTimeSeen);
                        fields.effectiveTimeSeen = true;
                        break;
                    case "regId":
                        fields.registrationId =
                                readString(parser, valueToken, name, fields.registrationIdSeen);
                        fields.registrationIdSeen = true;
                        break;
                    case "code":
                        fields.codes =
                                readStringArray(parser, valueToken, name, fields.codesSeen);
                        fields.codesSeen = true;
                        break;
                    case "recTime":
                        fields.recordedAtText =
                                readString(parser, valueToken, name, fields.recordedAtSeen);
                        fields.recordedAtSeen = true;
                        break;
                    default:
                        parser.skipChildren();
                        break;
                }
            }

            if (parser.nextToken() != null) {
                throw invalid("unexpected data after TimingData JSON object");
            }
        } finally {
            parser.close();
        }

        return fields;
    }

    private TimingData toTimingData(DecodedFields fields) throws CodecException {
        require(fields.versionSeen, "v");
        if (fields.version != VERSION) {
            throw CodecException.unsupportedVersion(
                    fields.version,
                    "unsupported IF-05 TimingData version " + fields.version);
        }

        TimingDataFactory.Context context = commonContext(fields);
        TimingData.RecordKey key = new TimingData.RecordKey(
                context.timingNodeId(),
                context.sequenceNumber());

        require(fields.recordTypeSeen, "recType");
        if (!RECORD_TYPE_AUTO_REG.equals(fields.recordType)
                && !RECORD_TYPE_MAN_REG.equals(fields.recordType)
                && !RECORD_TYPE_NODE_INFO.equals(fields.recordType)) {
            throw CodecException.unsupportedRecordType(
                    VERSION,
                    key,
                    context.locationId(),
                    fields.recordType,
                    context.effectiveTime(),
                    context.recordedAt(),
                    "unsupported IF-05 development-v1 recType " + fields.recordType);
        }

        if (RECORD_TYPE_NODE_INFO.equals(fields.recordType)) {
            rejectLifecycleRegistrationFields(fields);
            require(fields.codesSeen, "code");
            if (hasExactCodes(fields.codes, CODE_OPEN)) {
                return timingDataFactory.createNodeOpen(context);
            }
            if (hasExactCodes(fields.codes, CODE_CLOSE)) {
                return timingDataFactory.createNodeClose(context);
            }
            throw invalid(
                    "NODE_INFO code must be exactly OPEN or CLOSE");
        }

        require(fields.registrationIdSeen, "regId");
        require(fields.codesSeen, "code");
        RegistrationId registrationId = registrationId(fields.registrationId);

        if (RECORD_TYPE_AUTO_REG.equals(fields.recordType)) {
            requireExactCodes(fields.codes, CODE_ADD);
            return timingDataFactory.createAutomaticRegistration(
                    context,
                    registrationId);
        }

        if (hasExactCodes(fields.codes, CODE_ADD, CODE_AUTO)) {
            return timingDataFactory.createManualRegistration(
                    context,
                    registrationId,
                    TimingData.ManualTimeSource.SYSTEM_ASSIGNED);
        }
        if (hasExactCodes(fields.codes, CODE_ADD, CODE_MAN)) {
            return timingDataFactory.createManualRegistration(
                    context,
                    registrationId,
                    TimingData.ManualTimeSource.OPERATOR_ENTERED);
        }

        throw invalid(
                "MAN_REG code must contain ADD and exactly one of AUTO or MAN");
    }

    private static void rejectLifecycleRegistrationFields(DecodedFields fields)
            throws CodecException {
        if (fields.registrationIdSeen) {
            throw invalid(fields.recordType + " must not contain regId");
        }

    }

    private static RegistrationId registrationId(String value) throws CodecException {
        try {
            return new RegistrationId(value);
        } catch (RuntimeException ex) {
            throw invalid("regId is invalid", ex);
        }
    }

    private static TimingDataFactory.Context commonContext(DecodedFields fields)
            throws CodecException {
        require(fields.nodeIdSeen, "nodeId");
        require(fields.sequenceNumberSeen, "seqNr");
        require(fields.locationIdSeen, "locId");
        require(fields.effectiveTimeSeen, "time");
        require(fields.recordedAtSeen, "recTime");

        try {
            return new TimingDataFactory.Context(
                    new NodeId(fields.nodeId),
                    fields.sequenceNumber,
                    new LocationId(fields.locationId),
                    TimingTimestamp.parse(fields.effectiveTimeText),
                    TimingTimestamp.parse(fields.recordedAtText));
        } catch (RuntimeException ex) {
            throw invalid("common IF-05 TimingData envelope is invalid", ex);
        }
    }

    private static void requireExactCodes(List<String> actual, String... expected)
            throws CodecException {
        if (!hasExactCodes(actual, expected)) {
            throw invalid(
                    "code must be exactly " + java.util.Arrays.toString(expected));
        }
    }

    private static boolean hasExactCodes(List<String> actual, String... expected) {
        if (actual == null || actual.size() != expected.length) {
            return false;
        }
        for (String code : expected) {
            if (!actual.contains(code)) {
                return false;
            }
        }
        return true;
    }

    private static String readString(
            JsonParser parser,
            JsonToken token,
            String name,
            boolean alreadySeen)
            throws IOException, CodecException {
        rejectDuplicate(name, alreadySeen);
        if (token != JsonToken.VALUE_STRING) {
            throw invalid(name + " must be a JSON string");
        }
        return parser.getText();
    }

    private static List<String> readStringArray(
            JsonParser parser,
            JsonToken token,
            String name,
            boolean alreadySeen)
            throws IOException, CodecException {
        rejectDuplicate(name, alreadySeen);
        if (token != JsonToken.START_ARRAY) {
            throw invalid(name + " must be a JSON string array");
        }

        List<String> values = new ArrayList<String>();
        JsonToken item;
        while ((item = parser.nextToken()) != JsonToken.END_ARRAY) {
            if (item == null || item != JsonToken.VALUE_STRING) {
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
            JsonParser parser,
            JsonToken token,
            String name,
            boolean alreadySeen)
            throws IOException, CodecException {
        rejectDuplicate(name, alreadySeen);
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(name + " must be a JSON integer");
        }
        try {
            return parser.getIntValue();
        } catch (RuntimeException ex) {
            throw invalid(name + " is outside the supported integer range", ex);
        }
    }

    private static long readLong(
            JsonParser parser,
            JsonToken token,
            String name,
            boolean alreadySeen)
            throws IOException, CodecException {
        rejectDuplicate(name, alreadySeen);
        if (token != JsonToken.VALUE_NUMBER_INT) {
            throw invalid(name + " must be a JSON integer");
        }
        try {
            return parser.getLongValue();
        } catch (RuntimeException ex) {
            throw invalid(name + " is outside the supported integer range", ex);
        }
    }

    private static void rejectDuplicate(String name, boolean alreadySeen)
            throws CodecException {
        if (alreadySeen) {
            throw invalid("duplicate JSON member " + name);
        }
    }

    private static void require(boolean present, String name) throws CodecException {
        if (!present) {
            throw invalid("missing required JSON member " + name);
        }
    }

    private static boolean hasUtf8Bom(byte[] encodedRecord) {
        return encodedRecord.length >= 3
                && (encodedRecord[0] & 0xff) == 0xef
                && (encodedRecord[1] & 0xff) == 0xbb
                && (encodedRecord[2] & 0xff) == 0xbf;
    }

    private static CodecException invalid(String message) {
        return new CodecException(CodecException.Reason.INVALID_DATA, message);
    }

    private static CodecException invalid(String message, Throwable cause) {
        return new CodecException(
                CodecException.Reason.INVALID_DATA,
                message,
                cause);
    }

    /** Parsed known members before semantic construction; unknown members are skipped. */
    private static final class DecodedFields {
        private int version;
        private boolean versionSeen;
        private String nodeId;
        private boolean nodeIdSeen;
        private long sequenceNumber;
        private boolean sequenceNumberSeen;
        private int locationId;
        private boolean locationIdSeen;
        private String recordType;
        private boolean recordTypeSeen;
        private String effectiveTimeText;
        private boolean effectiveTimeSeen;
        private String registrationId;
        private boolean registrationIdSeen;
        private List<String> codes;
        private boolean codesSeen;
        private String recordedAtText;
        private boolean recordedAtSeen;
    }
}
