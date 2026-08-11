package io.github.candyxi0.hidenest.application.model;

import java.util.UUID;

public record LocalV1S3ADeletionPreviewRequest(UUID memoryId, String idempotencyKey, byte[] requestHash) {
    public LocalV1S3ADeletionPreviewRequest {
        requestHash = requestHash == null ? null : requestHash.clone();
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }
}
