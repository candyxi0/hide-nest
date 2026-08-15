package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Human-readable evidence message for the permanent-deletion preview.
 * Deliberately carries no object reference, hash, path, or storage-policy field.
 */
public record LocalV1DeletionEvidenceMessage(
        UUID anchorId,
        Long ordinal,
        UUID actorId,
        String actorStableRef,
        String displayLabel,
        OffsetDateTime occurredAt,
        String bodyText,
        List<UUID> sharedByMemoryIds) {}
