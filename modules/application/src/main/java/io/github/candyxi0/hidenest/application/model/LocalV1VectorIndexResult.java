package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/** Minimal index result. Carries a hash, never body or vector content. */
public record LocalV1VectorIndexResult(
        UUID memoryId,
        UUID memoryRevisionId,
        Long revisionNo,
        String modelName,
        int dimension,
        String embeddedBodySha256Hex,
        double normalizedNorm,
        boolean idempotent) {}
