package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

public record LocalV1S3B2BDeletionConfirmResult(
        UUID closureId,
        long previewRevision,
        byte[] manifestHash,
        UUID decisionId,
        OffsetDateTime confirmedAt,
        String state,
        int fenceCount,
        int unfencedAffectedCount) {

    public LocalV1S3B2BDeletionConfirmResult {
        if (manifestHash == null || manifestHash.length != 32) {
            throw new IllegalArgumentException("manifestHash must be exactly 32 bytes");
        }
        manifestHash = manifestHash.clone();
        if (decisionId == null) throw new IllegalArgumentException("decisionId must not be null");
        if (confirmedAt == null) throw new IllegalArgumentException("confirmedAt must not be null");
        if (!"CONFIRMED".equals(state)) throw new IllegalArgumentException("state must be CONFIRMED");
        if (fenceCount < 0) throw new IllegalArgumentException("fenceCount must be non-negative");
        if (unfencedAffectedCount < 0) throw new IllegalArgumentException("unfencedAffectedCount must be non-negative");
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
