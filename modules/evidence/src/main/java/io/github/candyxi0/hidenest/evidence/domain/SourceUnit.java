package io.github.candyxi0.hidenest.evidence.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceUnit(
        UUID sourceUnitId,
        UUID sourceId,
        String externalUnitRef,
        String sourceVersion,
        Long ordinal,
        UUID actorId,
        OffsetDateTime occurredAt,
        OffsetDateTime createdAt) {}
