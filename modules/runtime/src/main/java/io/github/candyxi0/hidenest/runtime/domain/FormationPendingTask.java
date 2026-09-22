package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** Body-free Formation range that has not yet been claimed. */
public record FormationPendingTask(
        UUID taskId,
        UUID sourceId,
        SourceBoundary fromExclusive,
        SourceBoundary toInclusive,
        SourceReadBinding readBinding,
        OffsetDateTime readyAt,
        long generation,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public FormationPendingTask {
        Objects.requireNonNull(taskId, "taskId must not be null");
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        Objects.requireNonNull(toInclusive, "toInclusive must not be null");
        Objects.requireNonNull(readBinding, "readBinding must not be null");
        Objects.requireNonNull(readyAt, "readyAt must not be null");
        Objects.requireNonNull(createdAt, "createdAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
        if (fromExclusive != null && fromExclusive.sequence() >= toInclusive.sequence()) {
            throw new IllegalArgumentException("Formation range must advance beyond fromExclusive");
        }
        if (generation != 0) {
            throw new IllegalArgumentException("S02-A pending generation must be zero");
        }
    }
}
