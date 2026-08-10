package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ContextDelivery(
        UUID deliveryId,
        UUID requestId,
        UUID threadId,
        UUID turnId,
        String purpose,
        byte[] policyRevisionSetHash,
        byte[] manifestHash,
        OffsetDateTime deliveredAt,
        OffsetDateTime expiresAt,
        OffsetDateTime invalidatedAt,
        String invalidationReason) {}
