package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

/** Minimal similarity match. Never carries body or vector content. */
public record LocalV1VectorMatch(UUID memoryId, UUID memoryRevisionId, Long revisionNo, double score) {}
