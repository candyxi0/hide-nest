package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Projected closeout run status for the read-only {@code GET /v1/runs/{runId}} surface. */
public record LocalV1RunStatus(
        UUID runId,
        String phase,
        String failureCode,
        OffsetDateTime startedAt,
        OffsetDateTime terminalAt,
        boolean retryable) {}
