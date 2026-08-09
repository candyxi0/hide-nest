package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

public record CanonicalPublishResult(
        UUID memoryId,
        UUID revisionId,
        Long revisionNo,
        String state) {

    public static CanonicalPublishResult success(UUID memoryId, UUID revisionId, Long revisionNo) {
        return new CanonicalPublishResult(memoryId, revisionId, revisionNo, "CANONICAL_COMMITTED");
    }
}
