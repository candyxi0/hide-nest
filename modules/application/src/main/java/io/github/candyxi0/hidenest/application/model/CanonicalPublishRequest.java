package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public record CanonicalPublishRequest(
        String idempotencyKey,
        byte[] requestHash,
        Set<UUID> decisionIds,
        UUID proposalRevisionId,
        UUID reviewSessionId,
        UUID memoryId,
        String memoryType,
        UUID perspectiveActorId,
        String bodyText,
        UUID policyId,
        List<RelationSpec> relations,
        byte[] manifestHash) {

    public record RelationSpec(
            String relationType,
            UUID toRevisionId,
            UUID toAnchorId,
            UUID perspectiveActorId) {}
}
