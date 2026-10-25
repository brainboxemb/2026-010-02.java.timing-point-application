package oss.relay.timing.timingpoint.runtime.config;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Shared scalar/container validation for the application YAML schema.
 *
 * <p>This class only interprets YAML value shapes. Domain-specific field
 * selection and object construction stay in the mapper that owns that part of
 * the configuration schema.</p>
 */
final class YamlValues {
    private static final String BIND_ADDRESS = "bindAddress";
    private static final String PORT = "port";

    private YamlValues() {
    }

    /** Requires an ordered YAML list at the supplied schema field. */
    static List<?> requireList(Object value, String field) {
        if (!(value instanceof List)) {
            throw new IllegalArgumentException(field + " must be a YAML list");
        }
        return (List<?>) value;
    }

    /** Requires a YAML mapping at the supplied schema field. */
    static Map<?, ?> requireMapping(Object value, String field) {
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(field + " must be a YAML mapping");
        }
        return (Map<?, ?>) value;
    }

    /**
     * Accepts textual IDs and bare numeric YAML scalars as the same textual ID.
     */
    static String requireCompactId(Object value, String field) {
        if (value instanceof Integer) {
            return Integer.toString(((Integer) value).intValue());
        }
        return requireString(value, field);
    }

    /** Requires a YAML string with no unresolved context placeholders. */
    static String requireString(Object value, String field) {
        String result =
                requireTemplateString(
                        value,
                        field);
        YamlTemplateResolver.rejectContextPlaceholders(
                result,
                field);
        return result;
    }

    /** Requires a raw YAML string while allowing later context substitution. */
    static String requireTemplateString(Object value, String field) {
        if (!(value instanceof String)) {
            throw new IllegalArgumentException(
                    field + " must be a YAML string");
        }
        return (String) value;
    }

    /** Accepts YAML integer scalars represented as Integer or Long. */
    static long requireLong(Object value, String field) {
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        if (value instanceof Long) {
            return ((Long) value).longValue();
        }
        throw new IllegalArgumentException(
                field + " must be a YAML integer");
    }

    /** Requires a strictly positive YAML integer. */
    static int requirePositiveInteger(Object value, String field) {
        if (!(value instanceof Integer)
                || ((Integer) value).intValue() <= 0) {
            throw new IllegalArgumentException(
                    field + " must be a positive YAML integer");
        }
        return ((Integer) value).intValue();
    }

    /** Requires a YAML integer; the receiving endpoint type validates its range. */
    static int requirePort(Object value, String field) {
        if (!(value instanceof Integer)) {
            throw new IllegalArgumentException(
                    field + " must be a YAML integer");
        }
        return ((Integer) value).intValue();
    }

    /** Requires a bindAddress/port mapping and rejects unknown endpoint fields. */
    static Map<?, ?> endpointMapping(Object raw, String field) {
        Map<?, ?> values =
                requireMapping(
                        raw,
                        field);
        rejectUnknownFields(
                values,
                field,
                BIND_ADDRESS,
                PORT);
        if (!values.containsKey(BIND_ADDRESS)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + field
                            + "."
                            + BIND_ADDRESS);
        }
        if (!values.containsKey(PORT)) {
            throw new IllegalArgumentException(
                    "Missing required configuration field: "
                            + field
                            + "."
                            + PORT);
        }
        return values;
    }

    /** Rejects YAML mapping keys outside the supplied schema field set. */
    static void rejectUnknownFields(
            Map<?, ?> values,
            String field,
            String... allowedFields) {
        for (Object key : values.keySet()) {
            if (!(key instanceof String)
                    || !Arrays.asList(allowedFields)
                            .contains((String) key)) {
                throw new IllegalArgumentException(
                        "Unsupported configuration field in "
                                + field
                                + ": "
                                + key);
            }
        }
    }
}
