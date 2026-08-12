package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

public record LocalV1S3C2FileDeletionResult(
        UUID runId,
        UUID closureId,
        String state,
        long payloadTaskCount,
        OffsetDateTime completedAt) {
    public LocalV1S3C2FileDeletionResult {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(closureId, "closureId");
        Objects.requireNonNull(state, "state");
        if (payloadTaskCount < 0) throw new IllegalArgumentException("payloadTaskCount must be non-negative");
    }
}
