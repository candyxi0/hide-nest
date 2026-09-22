package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SourceAdvanceResult(
        SourceAdvanceOutcome outcome,
        UUID pendingTaskId,
        OffsetDateTime readyAt) {}
