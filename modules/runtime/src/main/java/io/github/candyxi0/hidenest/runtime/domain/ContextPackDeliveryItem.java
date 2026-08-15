package io.github.candyxi0.hidenest.runtime.domain;

import java.util.UUID;

/**
 * Minimal, immutable delivery-item snapshot for a context pack.
 *
 * <p>Each row records only the facts that are not recoverable after delivery: the delivered memory
 * revision identity, the delivery-time policy revision and the delivery-time similarity score. The
 * memory identity, revision number, memory type and body text are reconstructed from the immutable
 * {@code memory.memory_revision} row on replay, so body content never enters the runtime domain.
 * This lets an idempotent replay rebuild the identical response from structured database facts
 * without re-embedding, re-searching, or caching a full response payload in the receipt manifest.</p>
 */
public record ContextPackDeliveryItem(
        UUID deliveryId,
        long ordinal,
        UUID memoryRevisionId,
        long policyRevisionNo,
        double score) {}
