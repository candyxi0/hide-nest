package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record IdempotencyReceipt(
        String idempotencyKey,
        String operationCode,
        byte[] requestHash,
        String state,
        String resourceKind,
        UUID resourceId,
        String responseManifest,
        OffsetDateTime createdAt,
        OffsetDateTime committedAt) {}
