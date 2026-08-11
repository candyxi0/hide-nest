package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A list-only current memory projection; it intentionally has no evidence metadata. */
public record LocalV1S2BMemoryItem(
        UUID memoryId,
        String state,
        Long revisionNo,
        String memoryType,
        UUID perspectiveActorId,
        String preview,
        OffsetDateTime updatedAt,
        int evidenceCount) {}
