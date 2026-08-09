package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MemoryRevision(
        UUID memoryRevisionId,
        UUID memoryId,
        Long revisionNo,
        String memoryType,
        UUID perspectiveActorId,
        String bodyText,
        OffsetDateTime validFrom,
        OffsetDateTime validTo,
        String uncertaintyCode,
        UUID createdByDecisionId,
        OffsetDateTime createdAt) {}
