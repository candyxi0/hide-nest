package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ProposalRevision(
        UUID proposalRevisionId,
        UUID proposalId,
        Long revisionNo,
        String actionCode,
        String bodyText,
        String memoryType,
        UUID perspectiveActorId,
        UUID expectedMemoryRevisionId,
        Long expectedPolicyRevisionNo,
        byte[] bodyHash,
        OffsetDateTime createdAt) {}
