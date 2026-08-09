package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DerivationBlock(
        UUID blockId,
        String targetKind,
        UUID targetId,
        UUID lineageRootId,
        String reasonCode,
        Boolean permanent,
        UUID createdByDecisionId,
        OffsetDateTime createdAt,
        UUID removedByDecisionId,
        OffsetDateTime removedAt) {}
