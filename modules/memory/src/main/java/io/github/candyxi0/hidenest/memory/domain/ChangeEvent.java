package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChangeEvent(
        UUID changeEventId,
        Long sequenceNo,
        String eventType,
        UUID actorId,
        String targetKind,
        UUID targetId,
        Long targetRevisionRef,
        UUID decisionId,
        OffsetDateTime occurredAt,
        String detailManifest) {}
