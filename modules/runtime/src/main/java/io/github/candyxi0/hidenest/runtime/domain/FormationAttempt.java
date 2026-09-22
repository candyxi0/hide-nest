package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Identity and frozen range of one leased Formation execution. */
public record FormationAttempt(
        UUID attemptId,
        UUID taskId,
        UUID sourceId,
        long generation,
        SourceBoundary fromExclusive,
        SourceBoundary toInclusive,
        SourceReadBinding readBinding,
        OffsetDateTime leaseUntil) {}
