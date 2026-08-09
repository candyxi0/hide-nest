package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxEvent(
        UUID eventId,
        String idempotencyKey,
        Long sequenceNo,
        String eventCategory,
        String eventType,
        String aggregateKind,
        UUID aggregateId,
        Long aggregateRevision,
        String contractVersion,
        String purpose,
        Long policyRevision,
        byte[] manifestHash,
        String payloadManifest,
        UUID changeEventId,
        String state,
        OffsetDateTime availableAt,
        String leaseOwner,
        OffsetDateTime leaseUntil,
        Short attemptCount,
        Short maxAttempts,
        String lastFailureCode,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt) {}
