package io.github.candyxi0.hidenest.runtime.domain;

import java.util.UUID;

/** Minimal internal Bubble delivery snapshot. It intentionally contains no body text. */
public record BubbleDeliveryItem(
        String spaceKey,
        String roomKey,
        String turnKey,
        UUID memoryId,
        UUID memoryRevisionId,
        long revisionNo,
        long policyRevisionNo,
        double score,
        String memoryType,
        int evidenceAgeDays) {}
