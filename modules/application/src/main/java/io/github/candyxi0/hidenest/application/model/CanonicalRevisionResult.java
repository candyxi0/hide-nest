package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

public record CanonicalRevisionResult(
        UUID memoryId,
        UUID revisionId,
        Long revisionNo,
        String state) {

    public static CanonicalRevisionResult success(UUID memoryId, UUID revisionId, Long revisionNo) {
        return new CanonicalRevisionResult(memoryId, revisionId, revisionNo, "CANONICAL_COMMITTED");
    }
}
