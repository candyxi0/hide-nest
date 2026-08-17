package io.github.candyxi0.hidenest.api;

import io.github.candyxi0.hidenest.application.coordinator.LocalV1CandidateSetException;
import io.github.candyxi0.hidenest.application.model.LocalV1CandidateSetRequest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Strict transport mapper from the CandidateSet JSON body to the application transport model.
 * Rejects unknown fields, wrong types, malformed UUIDs/dates/hex, and structural attacks before
 * any business write.
 */
final class LocalV1CandidateSetRequestMapper {

    private static final Set<String> TOP_LEVEL_FIELDS = Set.of(
            "candidateSetId", "requestHash", "threadId", "scopeRef", "setVersion",
            "finalConfirmation", "evidencePool", "candidates");
    private static final Set<String> CONFIRMATION_FIELDS =
            Set.of("decision", "confirmedSetVersion", "confirmationHash");
    private static final Set<String> EVIDENCE_POOL_FIELDS = Set.of("messages", "anchors");
    private static final Set<String> EVIDENCE_MESSAGE_FIELDS = Set.of(
            "sourceUnitId", "actorId", "ordinal", "externalUnitRef",
            "occurredAt", "bodyText", "bodyHash");
    private static final Set<String> EVIDENCE_ANCHOR_FIELDS = Set.of("anchorId", "units");
    private static final Set<String> ANCHOR_UNIT_FIELDS =
            Set.of("sourceUnitId", "fromOffset", "toOffset", "ordinal");
    private static final Set<String> CANDIDATE_FIELDS = Set.of(
            "candidateId", "ordinal", "disposition", "action", "originKind",
            "finalAuthorKind", "memoryText", "memoryType", "perspectiveActorId",
            "evidenceAnchorIds", "targetMemoryId", "expectedMemoryRevisionId",
            "expectedRevisionNo", "expectedPolicyRevisionNo", "hideReason");
    private static final Set<String> VALID_DISPOSITIONS = Set.of("ACCEPTED", "REJECTED");
    private static final Set<String> VALID_ACTIONS = Set.of("CREATE", "REVISE", "SUPERSEDE");
    private static final Set<String> VALID_ORIGINS = Set.of("HIDE_PROPOSED", "USER_EDITED", "USER_ADDED");
    private static final Set<String> VALID_AUTHORS = Set.of("HIDE", "USER");
    private static final int MAX_MEMORY_TEXT = 16000;

    private LocalV1CandidateSetRequestMapper() {}

    static LocalV1CandidateSetRequest map(JsonNode body) {
        if (body == null || !body.isObject()) {
            throw schema();
        }
        rejectUnknownFields(body, TOP_LEVEL_FIELDS);

        UUID candidateSetId = requiredUuid(body, "candidateSetId");
        byte[] requestHash = requiredHash(body, "requestHash");
        UUID threadId = requiredUuid(body, "threadId");
        String scopeRef = requiredText(body, "scopeRef");
        if (scopeRef.isEmpty() || scopeRef.length() > 256) {
            throw schema();
        }
        long setVersion = requiredLong(body, "setVersion");
        if (setVersion < 1) {
            throw schema();
        }

        JsonNode confirmationNode = requiredObject(body, "finalConfirmation");
        rejectUnknownFields(confirmationNode, CONFIRMATION_FIELDS);
        String decision = requiredText(confirmationNode, "decision");
        if (!"CONFIRM_SET".equals(decision)) {
            throw schema();
        }
        long confirmedSetVersion = requiredLong(confirmationNode, "confirmedSetVersion");
        if (confirmedSetVersion != setVersion) {
            throw schema();
        }
        byte[] confirmationHash = requiredHash(confirmationNode, "confirmationHash");
        LocalV1CandidateSetRequest.FinalConfirmation finalConfirmation =
                new LocalV1CandidateSetRequest.FinalConfirmation(decision, confirmedSetVersion, confirmationHash);

        LocalV1CandidateSetRequest.EvidencePool evidencePool = mapEvidencePool(
                requiredObject(body, "evidencePool"));

        List<LocalV1CandidateSetRequest.Candidate> candidates = mapCandidates(
                requiredArray(body, "candidates"));

        return new LocalV1CandidateSetRequest(
                candidateSetId,
                candidateSetId.toString(), // idempotencyKey == candidateSetId.toString()
                requestHash,
                threadId,
                scopeRef,
                setVersion,
                finalConfirmation,
                evidencePool,
                candidates);
    }

    private static LocalV1CandidateSetRequest.EvidencePool mapEvidencePool(JsonNode node) {
        rejectUnknownFields(node, EVIDENCE_POOL_FIELDS);
        List<LocalV1CandidateSetRequest.EvidenceMessage> messages = new ArrayList<>();
        for (JsonNode element : requiredArray(node, "messages")) {
            if (!element.isObject()) {
                throw schema();
            }
            rejectUnknownFields(element, EVIDENCE_MESSAGE_FIELDS);
            messages.add(new LocalV1CandidateSetRequest.EvidenceMessage(
                    requiredUuid(element, "sourceUnitId"),
                    requiredUuid(element, "actorId"),
                    requiredLong(element, "ordinal"),
                    requiredText(element, "externalUnitRef"),
                    requiredDateTime(element, "occurredAt"),
                    requiredText(element, "bodyText"),
                    requiredHash(element, "bodyHash")));
        }
        List<LocalV1CandidateSetRequest.AnchorSpec> anchors = new ArrayList<>();
        for (JsonNode element : requiredArray(node, "anchors")) {
            if (!element.isObject()) {
                throw schema();
            }
            rejectUnknownFields(element, EVIDENCE_ANCHOR_FIELDS);
            List<LocalV1CandidateSetRequest.AnchorUnit> units = new ArrayList<>();
            for (JsonNode unitNode : requiredArray(element, "units")) {
                if (!unitNode.isObject()) {
                    throw schema();
                }
                rejectUnknownFields(unitNode, ANCHOR_UNIT_FIELDS);
                Long fromOffset = optionalLong(unitNode, "fromOffset");
                Long toOffset = optionalLong(unitNode, "toOffset");
                units.add(new LocalV1CandidateSetRequest.AnchorUnit(
                        requiredUuid(unitNode, "sourceUnitId"),
                        fromOffset,
                        toOffset,
                        requiredLong(unitNode, "ordinal")));
            }
            anchors.add(new LocalV1CandidateSetRequest.AnchorSpec(
                    requiredUuid(element, "anchorId"), units));
        }
        return new LocalV1CandidateSetRequest.EvidencePool(messages, anchors);
    }

    private static List<LocalV1CandidateSetRequest.Candidate> mapCandidates(JsonNode array) {
        List<LocalV1CandidateSetRequest.Candidate> candidates = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isObject()) {
                throw schema();
            }
            rejectUnknownFields(element, CANDIDATE_FIELDS);

            String disposition = requiredText(element, "disposition");
            if (!VALID_DISPOSITIONS.contains(disposition)) {
                throw schema();
            }
            String action = requiredText(element, "action");
            if (!VALID_ACTIONS.contains(action)) {
                throw schema();
            }
            String originKind = requiredText(element, "originKind");
            if (!VALID_ORIGINS.contains(originKind)) {
                throw schema();
            }
            String finalAuthorKind = requiredText(element, "finalAuthorKind");
            if (!VALID_AUTHORS.contains(finalAuthorKind)) {
                throw schema();
            }

            String memoryText = optionalText(element, "memoryText");
            if (memoryText != null && memoryText.length() > MAX_MEMORY_TEXT) {
                throw schema();
            }
            String memoryType = optionalText(element, "memoryType");

            List<UUID> evidenceAnchorIds = new ArrayList<>();
            JsonNode anchorIdsNode = requiredArray(element, "evidenceAnchorIds");
            for (JsonNode anchorIdNode : anchorIdsNode) {
                if (!anchorIdNode.isTextual()) {
                    throw schema();
                }
                try {
                    evidenceAnchorIds.add(UUID.fromString(anchorIdNode.asText()));
                } catch (IllegalArgumentException e) {
                    throw schema();
                }
            }

            UUID targetMemoryId = optionalUuid(element, "targetMemoryId");
            UUID expectedMemoryRevisionId = optionalUuid(element, "expectedMemoryRevisionId");
            Long expectedRevisionNo = optionalLong(element, "expectedRevisionNo");
            Long expectedPolicyRevisionNo = optionalLong(element, "expectedPolicyRevisionNo");
            String hideReason = optionalText(element, "hideReason");

            candidates.add(new LocalV1CandidateSetRequest.Candidate(
                    requiredUuid(element, "candidateId"),
                    requiredLong(element, "ordinal"),
                    disposition,
                    action,
                    originKind,
                    finalAuthorKind,
                    memoryText,
                    memoryType,
                    requiredUuid(element, "perspectiveActorId"),
                    evidenceAnchorIds,
                    targetMemoryId,
                    expectedMemoryRevisionId,
                    expectedRevisionNo,
                    expectedPolicyRevisionNo,
                    hideReason));
        }
        return candidates;
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

    private static UUID optionalUuid(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isTextual()) {
            throw schema();
        }
        try {
            return UUID.fromString(value.asText());
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

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
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
        if (value == null || value.isNull() || value.isMissingNode()) {
            return null;
        }
        if (!value.isIntegralNumber()) {
            throw schema();
        }
        return value.asLong();
    }

    private static byte[] requiredHash(JsonNode node, String field) {
        String text = requiredText(node, field);
        if (text.length() != 64 || !text.matches("^[a-f0-9]{64}$")) {
            throw schema();
        }
        return HexFormat.of().parseHex(text);
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

    private static LocalV1CandidateSetException schema() {
        return new LocalV1CandidateSetException(LocalV1CandidateSetException.Code.REQUEST_SCHEMA_INVALID);
    }
}