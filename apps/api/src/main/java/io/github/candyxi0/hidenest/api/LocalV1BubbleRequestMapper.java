package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1BubbleException;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleResolveRequest;
import io.github.candyxi0.hidenest.application.model.LocalV1BubbleRoomPurgeRequest;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/** Strict, closed JSON mapper for both Bubble endpoints. */
final class LocalV1BubbleRequestMapper {

    private static final Set<String> RESOLVE_FIELDS = Set.of("spaceKey", "roomKey", "turnKey", "queryText");
    private static final Set<String> PURGE_FIELDS = Set.of("spaceKey", "roomKey");

    private LocalV1BubbleRequestMapper() {}

    static LocalV1BubbleResolveRequest resolve(JsonNode body) {
        requireObject(body, RESOLVE_FIELDS);
        return new LocalV1BubbleResolveRequest(
                requiredText(body, "spaceKey"),
                requiredText(body, "roomKey"),
                requiredText(body, "turnKey"),
                requiredText(body, "queryText"));
    }

    static LocalV1BubbleRoomPurgeRequest purge(JsonNode body) {
        requireObject(body, PURGE_FIELDS);
        return new LocalV1BubbleRoomPurgeRequest(requiredText(body, "spaceKey"), requiredText(body, "roomKey"));
    }

    private static void requireObject(JsonNode body, Set<String> allowedFields) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        for (String field : body.propertyNames()) {
            if (!allowedFields.contains(field)) {
                throw schema();
            }
        }
        if (body.size() != allowedFields.size()) {
            throw schema();
        }
    }

    private static String requiredText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            throw schema();
        }
        return value.asText();
    }

    private static LocalV1BubbleException schema() {
        return new LocalV1BubbleException(LocalV1BubbleException.Code.REQUEST_SCHEMA_INVALID);
    }
}
