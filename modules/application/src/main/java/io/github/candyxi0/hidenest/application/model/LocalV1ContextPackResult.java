package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Minimal context pack result. The policy revision set is a stable-sorted list of
 * {@code MEMORY:<memoryId>:<policyRevisionNo>} entries exactly equal to the delivered set.
 */
public record LocalV1ContextPackResult(
        UUID requestId,
        String resultCategory,
        UUID deliveryId,
        UUID threadId,
        UUID turnId,
        String purpose,
        List<String> policyRevisionSet,
        OffsetDateTime issuedAt,
        OffsetDateTime expiresAt,
        boolean budgetLimited,
        List<LocalV1ContextPackMemory> memories) {}
