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

    private static final Set<String> TOP_LEVEL_FIELDS =
            Set.of("threadId", "turnId", "purpose", "query", "maxResults", "minScore");
    private static final int DEFAULT_MAX_RESULTS = 3;
    private static final double DEFAULT_MIN_SCORE = 0.6d;
    private static final int MIN_MAX_RESULTS = 1;
    private static final int MAX_MAX_RESULTS = 5;
    private static final double MIN_MIN_SCORE = 0.4d;
    private static final double MAX_MIN_SCORE = 1.0d;

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
                requiredText(body, "query"),
                optionalMaxResults(body),
                optionalMinScore(body));
    }

    /**
     * Absent field mechanically resolves to the contract default; a field present but explicitly
     * {@code null} is not "absent" and must be rejected as schema-invalid before any work.
     */
    private static int optionalMaxResults(JsonNode node) {
        JsonNode value = node.get("maxResults");
        if (value == null) {
            return DEFAULT_MAX_RESULTS;
        }
        if (value.isNull()) {
            throw schema();
        }
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw schema();
        }
        int maxResults = value.asInt();
        if (maxResults < MIN_MAX_RESULTS || maxResults > MAX_MAX_RESULTS) {
            throw schema();
        }
        return maxResults;
    }

    private static double optionalMinScore(JsonNode node) {
        JsonNode value = node.get("minScore");
        if (value == null) {
            return DEFAULT_MIN_SCORE;
        }
        if (value.isNull()) {
            throw schema();
        }
        if (!value.isNumber()) {
            throw schema();
        }
        double minScore = value.asDouble();
        if (Double.isNaN(minScore)
                || Double.isInfinite(minScore)
                || minScore < MIN_MIN_SCORE
                || minScore > MAX_MIN_SCORE) {
            throw schema();
        }
        return minScore;
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
