package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MemoryRelation(
        UUID relationId,
        UUID fromRevisionId,
        String relationType,
        UUID toRevisionId,
        UUID toAnchorId,
        UUID perspectiveActorId,
        UUID createdByDecisionId,
        OffsetDateTime createdAt) {}
