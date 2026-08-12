package io.github.candyxi0.hidenest.application.model;

import java.util.Arrays;
import java.util.UUID;

public record LocalV1S3B2BDeletionConfirmRequest(
        UUID closureId,
        long previewRevision,
        byte[] manifestHash,
        UUID actorId,
        String idempotencyKey) {

    public LocalV1S3B2BDeletionConfirmRequest {
        if (previewRevision < 1) throw new IllegalArgumentException("previewRevision must be positive");
        if (manifestHash == null || manifestHash.length != 32) {
            throw new IllegalArgumentException("manifestHash must be exactly 32 bytes");
        }
        manifestHash = manifestHash.clone();
        if (actorId == null) throw new IllegalArgumentException("actorId must not be null");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotencyKey must not be blank");
        }
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
