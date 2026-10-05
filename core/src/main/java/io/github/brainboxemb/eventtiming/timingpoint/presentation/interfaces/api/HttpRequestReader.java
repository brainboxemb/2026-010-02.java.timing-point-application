package io.github.brainboxemb.eventtiming.timingpoint.presentation.interfaces.api;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * Focused inbound request decoder for the compact IF-03 HTTP contract.
 *
 * <p>This helper owns only route-shape, query-string and JSON request parsing. It
 * does not choose HTTP resources, status codes or domain/application operations.</p>
 */
final class HttpRequestReader {
    private static final int MAX_REQUEST_BODY_BYTES = 64 * 1024;
    private static final int MAX_LOGBOOK_LIMIT = 1000;

    private final JsonFactory jsonFactory = new JsonFactory();

    NodeRoute nodeRoute(String remainder) {
        int slash = remainder.indexOf('/');
        if (slash <= 0 || slash == remainder.length() - 1) {
            return null;
        }
        return new NodeRoute(
                remainder.substring(0, slash),
                remainder.substring(slash));
    }

    void requireEmptyBody(HttpExchange exchange) throws IOException {
        byte[] body = readBody(exchange);
        for (byte value : body) {
            if (!Character.isWhitespace((char) (value & 0xff))) {
                throw malformed(
                        "This operation does not accept a request body");
            }
        }
    }

    int readLocationIdRequest(HttpExchange exchange)
            throws IOException {
        return parseLocationIdBody(readBody(exchange));
    }

    int parseLocationIdBody(byte[] body) {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed("Request must be one JSON object");
            }
            Integer locationId = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw malformed("Expected JSON member name");
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("locationId".equals(name)) {
                    if (locationId != null
                            || value != JsonToken.VALUE_NUMBER_INT) {
                        throw invalidValue(
                                "locationId must be one JSON integer");
                    }
                    locationId = parser.getIntValue();
                } else {
                    throw invalidValue(
                            "Unsupported request field: " + name);
                }
            }
            if (parser.nextToken() != null) {
                throw malformed(
                        "Unexpected data after request object");
            }
            if (locationId == null) {
                throw invalidValue(
                        "Missing required field: locationId");
            }
            return locationId.intValue();
        } catch (RequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw malformed("Malformed JSON request", ex);
        }
    }

    AutoRegistrationRequest readAutoRegistrationRequest(
            HttpExchange exchange)
            throws IOException {
        return parseAutoRegistrationBody(readBody(exchange));
    }

    AutoRegistrationRequest parseAutoRegistrationBody(byte[] body) {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed("Request must be one JSON object");
            }
            String id = null;
            String time = null;
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw malformed("Expected JSON member name");
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("id".equals(name)) {
                    if (id != null
                            || value != JsonToken.VALUE_STRING) {
                        throw invalidValue(
                                "id must be one JSON string");
                    }
                    id = parser.getText();
                } else if ("time".equals(name)) {
                    if (time != null
                            || value != JsonToken.VALUE_STRING) {
                        throw invalidValue(
                                "time must be one JSON string");
                    }
                    time = parser.getText();
                } else {
                    throw invalidValue(
                            "Unsupported request field: " + name);
                }
            }
            if (parser.nextToken() != null) {
                throw malformed(
                        "Unexpected data after request object");
            }
            if (id == null) {
                throw invalidValue(
                        "Missing required field: id");
            }
            if (time == null) {
                throw invalidValue(
                        "Missing required field: time");
            }
            return new AutoRegistrationRequest(id, time);
        } catch (RequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw malformed("Malformed JSON request", ex);
        }
    }

    TagProcessingUpdateRequest readTagProcessingUpdateRequest(
            HttpExchange exchange)
            throws IOException {
        return parseTagProcessingUpdateBody(readBody(exchange));
    }

    TagProcessingUpdateRequest parseTagProcessingUpdateBody(
            byte[] body) {
        try (JsonParser parser = jsonFactory.createParser(body)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                throw malformed("Request must be one JSON object");
            }

            String action = null;
            TagProcessingValues values = null;
            boolean actionSeen = false;
            boolean valueSeen = false;

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken() != JsonToken.FIELD_NAME) {
                    throw malformed("Expected JSON member name");
                }
                String name = parser.currentName();
                JsonToken value = parser.nextToken();

                if ("action".equals(name)) {
                    if (actionSeen
                            || value != JsonToken.VALUE_STRING) {
                        throw invalidValue(
                                "action must be one JSON string");
                    }
                    actionSeen = true;
                    action = parser.getText();
                } else if ("value".equals(name)) {
                    if (valueSeen
                            || value != JsonToken.START_OBJECT) {
                        throw invalidValue(
                                "value must be one JSON object");
                    }
                    valueSeen = true;
                    values = parseTagProcessingValues(parser);
                } else {
                    throw invalidValue(
                            "Unsupported request field: " + name);
                }
            }

            if (parser.nextToken() != null) {
                throw malformed(
                        "Unexpected data after request object");
            }
            if (!actionSeen) {
                throw invalidValue(
                        "Missing required field: action");
            }

            if ("SET".equals(action)) {
                if (!valueSeen) {
                    throw invalidValue(
                            "SET requires value");
                }
                return TagProcessingUpdateRequest.set(values);
            }
            if ("CLEAR".equals(action)) {
                if (valueSeen) {
                    throw invalidValue(
                            "CLEAR does not accept value");
                }
                return TagProcessingUpdateRequest.clear();
            }

            throw invalidValue(
                    "Unsupported configuration action: "
                            + action);
        } catch (RequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            throw malformed("Malformed JSON request", ex);
        }
    }

    private static TagProcessingValues parseTagProcessingValues(
            JsonParser parser)
            throws IOException {
        Long quietTimeoutMillis = null;
        Long maxBurstDurationMillis = null;
        Long duplicateWindowMillis = null;
        Long sweepCadenceMillis = null;
        Long observationQueueCapacity = null;
        Set<String> seen = new HashSet<String>();

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken() != JsonToken.FIELD_NAME) {
                throw malformed("Expected JSON member name");
            }

            String name = parser.currentName();
            if (!seen.add(name)) {
                throw invalidValue(
                        "Duplicate tag-processing field: "
                                + name);
            }

            JsonToken value = parser.nextToken();
            if (value != JsonToken.VALUE_NUMBER_INT) {
                throw invalidValue(
                        name + " must be a JSON integer");
            }
            long number = parser.getLongValue();

            switch (name) {
                case "quietTimeoutMillis":
                    quietTimeoutMillis =
                            Long.valueOf(number);
                    break;
                case "maxBurstDurationMillis":
                    maxBurstDurationMillis =
                            Long.valueOf(number);
                    break;
                case "duplicateWindowMillis":
                    duplicateWindowMillis =
                            Long.valueOf(number);
                    break;
                case "sweepCadenceMillis":
                    sweepCadenceMillis =
                            Long.valueOf(number);
                    break;
                case "observationQueueCapacity":
                    observationQueueCapacity =
                            Long.valueOf(number);
                    break;
                default:
                    throw invalidValue(
                            "Unsupported tag-processing field: "
                                    + name);
            }
        }

        return new TagProcessingValues(
                quietTimeoutMillis,
                maxBurstDurationMillis,
                duplicateWindowMillis,
                sweepCadenceMillis,
                observationQueueCapacity);
    }

    LogBookQuery readLogBookQuery(String query) {
        Long from = null;
        Integer limit = null;
        Integer last = null;

        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equals = pair.indexOf('=');
            if (equals <= 0
                    || equals == pair.length() - 1) {
                throw invalidValue(
                        "LogBook query parameters require a value");
            }
            String name = pair.substring(0, equals);
            String value = pair.substring(equals + 1);

            if ("from".equals(name)) {
                if (from != null) {
                    throw invalidValue(
                            "Duplicate LogBook query field: from");
                }
                from = Long.valueOf(
                        parsePositiveLong("from", value));
            } else if ("limit".equals(name)) {
                if (limit != null) {
                    throw invalidValue(
                            "Duplicate LogBook query field: limit");
                }
                limit = Integer.valueOf(
                        parseLogBookLimit("limit", value));
            } else if ("last".equals(name)) {
                if (last != null) {
                    throw invalidValue(
                            "Duplicate LogBook query field: last");
                }
                last = Integer.valueOf(
                        parseLogBookLimit("last", value));
            } else {
                throw invalidValue(
                        "Unsupported LogBook query field: "
                                + name);
            }
        }

        if (last != null) {
            if (from != null || limit != null) {
                throw invalidValue(
                        "last cannot be combined with from or limit");
            }
            return new LogBookQuery(null, null, last);
        }
        if (from == null || limit == null) {
            throw invalidValue(
                    "LogBook range requires both from and limit");
        }
        return new LogBookQuery(from, limit, null);
    }

    private static long parsePositiveLong(
            String name,
            String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1L) {
                throw invalidValue(
                        name + " must be >= 1");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw invalidValue(
                    name + " must be a positive integer");
        }
    }

    private static int parseLogBookLimit(
            String name,
            String value) {
        long parsed = parsePositiveLong(name, value);
        if (parsed > MAX_LOGBOOK_LIMIT) {
            throw invalidValue(
                    name + " must be <= "
                            + MAX_LOGBOOK_LIMIT);
        }
        return (int) parsed;
    }

    private static byte[] readBody(HttpExchange exchange)
            throws IOException {
        ByteArrayOutputStream output =
                new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        int total = 0;
        try (InputStream input = exchange.getRequestBody()) {
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_REQUEST_BODY_BYTES) {
                    throw malformed(
                            "Request body exceeds 64 KiB");
                }
                output.write(buffer, 0, read);
            }
        }
        return output.toByteArray();
    }

    static RequestException invalidValue(String message) {
        return new RequestException(
                "INVALID_VALUE",
                message,
                null);
    }

    private static RequestException malformed(String message) {
        return new RequestException(
                "MALFORMED_REQUEST",
                message,
                null);
    }

    private static RequestException malformed(
            String message,
            Throwable cause) {
        return new RequestException(
                "MALFORMED_REQUEST",
                message,
                cause);
    }

    static final class NodeRoute {
        final String nodeId;
        final String resource;

        private NodeRoute(
                String nodeId,
                String resource) {
            this.nodeId = nodeId;
            this.resource = resource;
        }
    }

    static final class LogBookQuery {
        final Long from;
        final Integer limit;
        final Integer last;

        private LogBookQuery(
                Long from,
                Integer limit,
                Integer last) {
            this.from = from;
            this.limit = limit;
            this.last = last;
        }
    }

    static final class AutoRegistrationRequest {
        final String id;
        final String time;

        private AutoRegistrationRequest(
                String id,
                String time) {
            this.id = id;
            this.time = time;
        }
    }

    static final class TagProcessingUpdateRequest {
        enum Action {
            SET,
            CLEAR
        }

        final Action action;
        final TagProcessingValues values;

        private TagProcessingUpdateRequest(
                Action action,
                TagProcessingValues values) {
            this.action = action;
            this.values = values;
        }

        private static TagProcessingUpdateRequest set(
                TagProcessingValues values) {
            return new TagProcessingUpdateRequest(
                    Action.SET,
                    values);
        }

        private static TagProcessingUpdateRequest clear() {
            return new TagProcessingUpdateRequest(
                    Action.CLEAR,
                    null);
        }
    }

    static final class TagProcessingValues {
        final Long quietTimeoutMillis;
        final Long maxBurstDurationMillis;
        final Long duplicateWindowMillis;
        final Long sweepCadenceMillis;
        final Long observationQueueCapacity;

        private TagProcessingValues(
                Long quietTimeoutMillis,
                Long maxBurstDurationMillis,
                Long duplicateWindowMillis,
                Long sweepCadenceMillis,
                Long observationQueueCapacity) {
            this.quietTimeoutMillis =
                    quietTimeoutMillis;
            this.maxBurstDurationMillis =
                    maxBurstDurationMillis;
            this.duplicateWindowMillis =
                    duplicateWindowMillis;
            this.sweepCadenceMillis =
                    sweepCadenceMillis;
            this.observationQueueCapacity =
                    observationQueueCapacity;
        }
    }

    static final class RequestException
            extends RuntimeException {
        private final String code;

        private RequestException(
                String code,
                String message,
                Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        String code() {
            return code;
        }
    }
}
