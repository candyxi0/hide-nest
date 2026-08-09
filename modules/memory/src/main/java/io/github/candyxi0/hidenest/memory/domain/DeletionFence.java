package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DeletionFence(
        UUID fenceId,
        UUID closureId,
        String targetKind,
        UUID targetId,
        Long targetRevisionRef,
        UUID createdByDecisionId,
        OffsetDateTime createdAt) {}
