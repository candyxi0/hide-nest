package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1ContextPackException;
import io.github.candyxi0.hidenest.application.model.LocalV1ContextPackRequest;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Strict transport mapper from the raw context pack JSON body to the application transport model.
 * Rejects unknown fields, wrong types and malformed UUIDs before any business work or embedding.
 */
final class LocalV1ContextPackRequestMapper {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of("threadId", "turnId", "purpose", "query");

    private LocalV1ContextPackRequestMapper() {}

    static LocalV1ContextPackRequest map(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        rejectUnknownFields(body, TOP_LEVEL_FIELDS);
        return new LocalV1ContextPackRequest(
                requiredUuid(body, "threadId"),
                requiredUuid(body, "turnId"),
                requiredText(body, "purpose"),
                requiredText(body, "query"));
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String text = requiredText(node, field);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw schema();
        }
    }

    private static String requiredText(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isTextual()) {
            throw schema();
        }
        return value.asText();
    }

    private static JsonNode required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw schema();
        }
        return value;
    }

    private static void rejectUnknownFields(JsonNode node, Set<String> allowed) {
        for (String name : node.propertyNames()) {
            if (!allowed.contains(name)) {
                throw schema();
            }
        }
    }

    private static LocalV1ContextPackException schema() {
        return new LocalV1ContextPackException(LocalV1ContextPackException.Code.REQUEST_SCHEMA_INVALID);
    }
}
