package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Decision(
        UUID decisionId,
        String decisionKind,
        UUID actorId,
        String actorRole,
        UUID proposalRevisionId,
        UUID reviewSessionId,
        String targetKind,
        UUID targetId,
        Long targetRevisionRef,
        String authorizationRef,
        String idempotencyKey,
        OffsetDateTime createdAt) {}
