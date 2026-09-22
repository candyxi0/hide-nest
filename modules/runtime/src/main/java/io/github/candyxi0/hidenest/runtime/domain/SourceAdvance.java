package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Objects;
import java.util.UUID;

/** Body-free source progress notification accepted by the Formation intake boundary. */
public record SourceAdvance(
        UUID sourceId,
        long notificationSequence,
        SourceBoundary discovered,
        SourceBoundary stable,
        SourceReadBinding readBinding) {

    public SourceAdvance {
        Objects.requireNonNull(sourceId, "sourceId must not be null");
        if (notificationSequence < 1) {
            throw new IllegalArgumentException("notificationSequence must be positive");
        }
        Objects.requireNonNull(discovered, "discovered must not be null");
        Objects.requireNonNull(readBinding, "readBinding must not be null");
        if (stable != null && stable.sequence() > discovered.sequence()) {
            throw new IllegalArgumentException("stable must not be ahead of discovered");
        }
    }
}
