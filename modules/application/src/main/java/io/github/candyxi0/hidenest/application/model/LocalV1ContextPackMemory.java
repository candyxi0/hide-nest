package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Minimal delivered-memory projection for a context pack response.
 *
 * <p>Carries the wire-facing identity, version, policy version, memory type, body text and
 * similarity score, plus the latest evidence timestamp and its age relative to the pack issue time.
 * It never carries evidence payload, vector, body hash or model fingerprint.</p>
 */
public record LocalV1ContextPackMemory(
        UUID memoryId,
        UUID memoryRevisionId,
        long revisionNo,
        long policyRevisionNo,
        String memoryType,
        String bodyText,
        double score,
        OffsetDateTime evidenceOccurredAt,
        int evidenceAgeDays) {}
