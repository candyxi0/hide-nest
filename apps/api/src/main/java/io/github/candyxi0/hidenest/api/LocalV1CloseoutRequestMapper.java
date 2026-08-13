package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CloseoutException;
import io.github.candyxi0.hidenest.application.model.LocalV1CloseoutSubmission;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Strict transport mapper from the raw closeout JSON body to the application transport model.
 * Rejects unknown fields, wrong types and malformed UUIDs/dates before any business write.
 */
final class LocalV1CloseoutRequestMapper {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "submissionId", "threadId", "hideSelection", "userConfirmation",
            "sourceAnchors", "threadReaderManifest", "confirmationProof");
    private static final Set<String> HIDE_SELECTION_FIELDS =
            Set.of("perspectiveActorId", "memoryType", "bodyText", "bodyHash");
    private static final Set<String> USER_CONFIRMATION_FIELDS =
            Set.of("decision", "reviewManifestHash", "confirmationSourceUnitId");
    private static final Set<String> ANCHOR_FIELDS = Set.of("anchorId", "units");
    private static final Set<String> ANCHOR_UNIT_FIELDS =
            Set.of("sourceUnitId", "fromOffset", "toOffset", "ordinal");
    private static final Set<String> MANIFEST_FIELDS = Set.of(
            "schemaVersion", "fromOrdinal", "toOrdinal", "continuous",
            "manifestHash", "selectedEvidenceMessages");
    private static final Set<String> EVIDENCE_MESSAGE_FIELDS = Set.of(
            "sourceUnitId", "actorId", "ordinal", "externalUnitRef",
            "occurredAt", "bodyText", "bodyHash");

    private LocalV1CloseoutRequestMapper() {}

    static LocalV1CloseoutSubmission map(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        rejectUnknownFields(body, TOP_LEVEL_FIELDS);

        JsonNode hideSelection = requiredObject(body, "hideSelection");
        JsonNode userConfirmation = requiredObject(body, "userConfirmation");
        JsonNode manifest = requiredObject(body, "threadReaderManifest");

        return new LocalV1CloseoutSubmission(
                requiredUuid(body, "submissionId"),
                requiredUuid(body, "threadId"),
                mapHideSelection(hideSelection),
                mapUserConfirmation(userConfirmation),
                mapAnchors(requiredArray(body, "sourceAnchors")),
                mapManifest(manifest),
                requiredText(body, "confirmationProof"));
    }

    private static LocalV1CloseoutSubmission.HideSelection mapHideSelection(JsonNode node) {
        rejectUnknownFields(node, HIDE_SELECTION_FIELDS);
        return new LocalV1CloseoutSubmission.HideSelection(
                requiredUuid(node, "perspectiveActorId"),
                requiredText(node, "memoryType"),
                requiredText(node, "bodyText"),
                requiredText(node, "bodyHash"));
    }

    private static LocalV1CloseoutSubmission.UserConfirmation mapUserConfirmation(JsonNode node) {
        rejectUnknownFields(node, USER_CONFIRMATION_FIELDS);
        return new LocalV1CloseoutSubmission.UserConfirmation(
                requiredText(node, "decision"),
                requiredText(node, "reviewManifestHash"),
                requiredUuid(node, "confirmationSourceUnitId"));
    }

    private static List<LocalV1CloseoutSubmission.SourceAnchor> mapAnchors(JsonNode array) {
        List<LocalV1CloseoutSubmission.SourceAnchor> anchors = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isObject()) {
                throw schema();
            }
            rejectUnknownFields(element, ANCHOR_FIELDS);
            List<LocalV1CloseoutSubmission.AnchorUnit> units = new ArrayList<>();
            for (JsonNode unit : requiredArray(element, "units")) {
                if (!unit.isObject()) {
                    throw schema();
                }
                rejectUnknownFields(unit, ANCHOR_UNIT_FIELDS);
                Long fromOffset = optionalLong(unit, "fromOffset");
                Long toOffset = optionalLong(unit, "toOffset");
                units.add(new LocalV1CloseoutSubmission.AnchorUnit(
                        requiredUuid(unit, "sourceUnitId"),
                        fromOffset,
                        toOffset,
                        requiredLong(unit, "ordinal")));
            }
            anchors.add(new LocalV1CloseoutSubmission.SourceAnchor(
                    requiredUuid(element, "anchorId"), units));
        }
        return anchors;
    }

    private static LocalV1CloseoutSubmission.ThreadReaderManifest mapManifest(JsonNode node) {
        rejectUnknownFields(node, MANIFEST_FIELDS);
        List<LocalV1CloseoutSubmission.EvidenceMessage> messages = new ArrayList<>();
        for (JsonNode element : requiredArray(node, "selectedEvidenceMessages")) {
            if (!element.isObject()) {
                throw schema();
            }
            rejectUnknownFields(element, EVIDENCE_MESSAGE_FIELDS);
            messages.add(new LocalV1CloseoutSubmission.EvidenceMessage(
                    requiredUuid(element, "sourceUnitId"),
                    requiredUuid(element, "actorId"),
                    requiredLong(element, "ordinal"),
                    requiredText(element, "externalUnitRef"),
                    requiredDateTime(element, "occurredAt"),
                    requiredText(element, "bodyText"),
                    requiredText(element, "bodyHash")));
        }
        return new LocalV1CloseoutSubmission.ThreadReaderManifest(
                requiredText(node, "schemaVersion"),
                requiredLong(node, "fromOrdinal"),
                requiredLong(node, "toOrdinal"),
                requiredBoolean(node, "continuous"),
                requiredText(node, "manifestHash"),
                messages);
    }

    // ── field extraction helpers ──────────────────────────────────────────

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isObject()) {
            throw schema();
        }
        return value;
    }

    private static JsonNode requiredArray(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isArray()) {
            throw schema();
        }
        return value;
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

    private static Long requiredLong(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isIntegralNumber()) {
            throw schema();
        }
        return value.asLong();
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw schema();
        }
        return value.asLong();
    }

    private static boolean requiredBoolean(JsonNode node, String field) {
        JsonNode value = required(node, field);
        if (!value.isBoolean()) {
            throw schema();
        }
        return value.asBoolean();
    }

    private static OffsetDateTime requiredDateTime(JsonNode node, String field) {
        String text = requiredText(node, field);
        try {
            return OffsetDateTime.parse(text);
        } catch (java.time.format.DateTimeParseException exception) {
            throw schema();
        }
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

    private static LocalV1CloseoutException schema() {
        return new LocalV1CloseoutException(LocalV1CloseoutException.Code.REQUEST_SCHEMA_INVALID);
    }
}
