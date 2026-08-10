package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CloseoutRun(
        UUID runId,
        UUID scopeId,
        String state,
        UUID retryOf,
        UUID submissionId,
        OffsetDateTime startedAt,
        OffsetDateTime terminalAt,
        String failureCode,
        OffsetDateTime createdAt) {}
