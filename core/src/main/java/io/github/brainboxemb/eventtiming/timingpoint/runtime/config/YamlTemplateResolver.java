package io.github.brainboxemb.eventtiming.timingpoint.runtime.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves source-only YAML template parameters before schema mapping.
 *
 * <p>This is deliberately substitution, not a scripting engine. Mapping keys are
 * left untouched and contextual placeholders are preserved until their owning
 * configuration field resolves them.</p>
 */
final class YamlTemplateResolver {
    private static final String PARAMETERS = "parameters";
    private static final String NODE_ID = "NodeId";
    private static final String SYSTEM_ID = "SystemId";

    private static final Pattern PARAMETER_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)\\}");

    private YamlTemplateResolver() {
    }

    static Object resolve(Object document) {
        if (!(document instanceof Map)) {
            return document;
        }

        Map<?, ?> root =
                (Map<?, ?>) document;
        Map<String, String> parameters =
                root.containsKey(PARAMETERS)
                        ? readParameters(
                                root.get(PARAMETERS))
                        : Collections.<String, String>emptyMap();

        Map<Object, Object> resolved =
                new LinkedHashMap<Object, Object>();
        for (Map.Entry<?, ?> entry
                : root.entrySet()) {
            if (PARAMETERS.equals(
                    entry.getKey())) {
                continue;
            }
            resolved.put(
                    entry.getKey(),
                    resolveValue(
                            entry.getValue(),
                            parameters,
                            "configuration root."
                                    + String.valueOf(
                                            entry.getKey())));
        }
        return resolved;
    }

    static boolean hasContextPlaceholder(
            String value) {
        Matcher matcher =
                PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            if (isContextPlaceholder(
                    matcher.group(1))) {
                return true;
            }
        }
        return false;
    }

    static String resolveContext(
            String value,
            String field,
            String nodeId,
            String systemId) {
        Matcher matcher =
                PLACEHOLDER.matcher(value);
        StringBuffer resolved =
                new StringBuffer();

        while (matcher.find()) {
            String name =
                    matcher.group(1);
            String replacement;
            if (NODE_ID.equals(name)) {
                replacement = nodeId;
            } else if (SYSTEM_ID.equals(name)) {
                replacement = systemId;
            } else {
                throw new IllegalArgumentException(
                        "Unresolved configuration parameter {"
                                + name
                                + "} in "
                                + field);
            }
            matcher.appendReplacement(
                    resolved,
                    Matcher.quoteReplacement(
                            replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    static void rejectContextPlaceholders(
            String value,
            String field) {
        Matcher matcher =
                PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            String name =
                    matcher.group(1);
            if (isContextPlaceholder(name)) {
                throw new IllegalArgumentException(
                        "Context parameter {"
                                + name
                                + "} is not available in "
                                + field);
            }
        }
    }

    private static Map<String, String> readParameters(
            Object rawParameters) {
        if (!(rawParameters instanceof Map)) {
            throw new IllegalArgumentException(
                    PARAMETERS
                            + " must be a YAML mapping");
        }

        Map<String, String> result =
                new LinkedHashMap<String, String>();
        for (Map.Entry<?, ?> entry
                : ((Map<?, ?>) rawParameters).entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException(
                        PARAMETERS
                                + " parameter names must be YAML strings");
            }

            String name =
                    (String) entry.getKey();
            if (!PARAMETER_NAME
                    .matcher(name)
                    .matches()) {
                throw new IllegalArgumentException(
                        "Invalid configuration parameter name "
                                + name);
            }
            if (isContextPlaceholder(name)) {
                throw new IllegalArgumentException(
                        "Configuration parameter "
                                + name
                                + " is reserved");
            }
            if (!(entry.getValue() instanceof String)) {
                throw new IllegalArgumentException(
                        PARAMETERS
                                + "."
                                + name
                                + " must be a YAML string");
            }

            String value =
                    (String) entry.getValue();
            rejectParameterReferences(
                    name,
                    value);
            result.put(
                    name,
                    value);
        }
        return result;
    }

    private static void rejectParameterReferences(
            String parameterName,
            String value) {
        Matcher matcher =
                PLACEHOLDER.matcher(value);
        while (matcher.find()) {
            if (!isContextPlaceholder(
                    matcher.group(1))) {
                throw new IllegalArgumentException(
                        PARAMETERS
                                + "."
                                + parameterName
                                + " must not reference another configuration parameter");
            }
        }
    }

    private static Object resolveValue(
            Object value,
            Map<String, String> parameters,
            String field) {
        if (value instanceof String) {
            return resolveParameters(
                    (String) value,
                    parameters,
                    field);
        }

        if (value instanceof Map) {
            Map<Object, Object> result =
                    new LinkedHashMap<Object, Object>();
            for (Map.Entry<?, ?> entry
                    : ((Map<?, ?>) value).entrySet()) {
                Object key =
                        entry.getKey();
                result.put(
                        key,
                        resolveValue(
                                entry.getValue(),
                                parameters,
                                field
                                        + "."
                                        + String.valueOf(key)));
            }
            return result;
        }

        if (value instanceof List) {
            List<Object> result =
                    new ArrayList<Object>();
            int index = 0;
            for (Object item
                    : (List<?>) value) {
                result.add(
                        resolveValue(
                                item,
                                parameters,
                                field
                                        + "["
                                        + index
                                        + "]"));
                index++;
            }
            return result;
        }

        return value;
    }

    private static String resolveParameters(
            String value,
            Map<String, String> parameters,
            String field) {
        Matcher matcher =
                PLACEHOLDER.matcher(value);
        StringBuffer resolved =
                new StringBuffer();

        while (matcher.find()) {
            String name =
                    matcher.group(1);
            if (isContextPlaceholder(name)) {
                matcher.appendReplacement(
                        resolved,
                        Matcher.quoteReplacement(
                                matcher.group(0)));
                continue;
            }

            String replacement =
                    parameters.get(name);
            if (replacement == null) {
                throw new IllegalArgumentException(
                        "Unknown configuration parameter {"
                                + name
                                + "} in "
                                + field);
            }

            matcher.appendReplacement(
                    resolved,
                    Matcher.quoteReplacement(
                            replacement));
        }
        matcher.appendTail(resolved);
        return resolved.toString();
    }

    private static boolean isContextPlaceholder(
            String name) {
        return NODE_ID.equals(name)
                || SYSTEM_ID.equals(name);
    }
}
