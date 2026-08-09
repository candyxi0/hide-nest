package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CanonicalRevisionRequest(
        String idempotencyKey,
        byte[] requestHash,
        Set<UUID> decisionIds,
        UUID proposalRevisionId,
        UUID reviewSessionId,
        UUID memoryId,
        Long expectedRevisionNo,
        Long expectedPolicyRevisionNo,
        String memoryType,
        UUID perspectiveActorId,
        String bodyText,
        boolean policyChange,
        List<RelationSpec> relations,
        byte[] manifestHash) {

    public record RelationSpec(
            String relationType,
            UUID toRevisionId,
            UUID toAnchorId,
            UUID perspectiveActorId) {}
}
