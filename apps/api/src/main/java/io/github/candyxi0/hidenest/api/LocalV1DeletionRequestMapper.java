package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1DeletionException;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/** Strict transport mapper from the raw deletion JSON body to primitive facade inputs. */
final class LocalV1DeletionRequestMapper {

    private static final Set<String> PREVIEW_FIELDS =
            Set.of("targetId", "expectedRevision", "expectedPolicyRevision", "requestManifestHash");
    private static final Set<String> CONFIRM_FIELDS = Set.of(
            "targetId", "expectedRevision", "expectedPolicyRevision", "requestManifestHash",
            "previewId", "previewRevision", "manifestHash");

    private LocalV1DeletionRequestMapper() {}

    record PreviewInput(UUID targetId, long expectedRevision, long expectedPolicyRevision, String requestManifestHash) {}

    record ConfirmInput(
            UUID targetId,
            long expectedRevision,
            long expectedPolicyRevision,
            String requestManifestHash,
            UUID previewId,
            long previewRevision,
            String manifestHash) {}

    static PreviewInput mapPreview(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        rejectUnknownFields(body, PREVIEW_FIELDS);
        return new PreviewInput(
                requiredUuid(body, "targetId"),
                requiredLong(body, "expectedRevision"),
                requiredLong(body, "expectedPolicyRevision"),
                requiredText(body, "requestManifestHash"));
    }

    static ConfirmInput mapConfirm(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        rejectUnknownFields(body, CONFIRM_FIELDS);
        return new ConfirmInput(
                requiredUuid(body, "targetId"),
                requiredLong(body, "expectedRevision"),
                requiredLong(body, "expectedPolicyRevision"),
                requiredText(body, "requestManifestHash"),
                requiredUuid(body, "previewId"),
                requiredLong(body, "previewRevision"),
                requiredText(body, "manifestHash"));
    }

    private static UUID requiredUuid(JsonNode node, String field) {
        String text = requiredText(node, field);
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException exception) {
            throw schema();
        }
    }

    private static long requiredLong(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) {
            throw schema();
        }
        return value.asLong();
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

    private static LocalV1DeletionException schema() {
        return new LocalV1DeletionException(LocalV1DeletionException.Code.REQUEST_SCHEMA_INVALID);
    }
}
