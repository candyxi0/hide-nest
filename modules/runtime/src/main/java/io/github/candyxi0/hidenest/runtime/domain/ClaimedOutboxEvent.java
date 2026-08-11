package io.github.candyxi0.hidenest.runtime.domain;

import java.util.UUID;

/**
 * Minimal body-less DTO returned by atomic claim-and-lease.
 * UPDATE...RETURNING provides no ordering guarantee;
 * the adapter must sort by sequenceNo ASC before returning.
 */
public record ClaimedOutboxEvent(
        UUID eventId,
        long sequenceNo,
        String eventCategory,
        String eventType,
        String aggregateKind,
        UUID aggregateId,
        Long aggregateRevision,
        String purpose,
        long policyRevision,
        byte[] manifestHash,
        String payloadManifest,
        UUID changeEventId,
        short attemptCount,
        short maxAttempts) {

    public ClaimedOutboxEvent {
        manifestHash = manifestHash != null ? manifestHash.clone() : null;
    }

    /** Defensive copy. */
    @Override
    public byte[] manifestHash() {
        return manifestHash != null ? manifestHash.clone() : null;
    }
}
