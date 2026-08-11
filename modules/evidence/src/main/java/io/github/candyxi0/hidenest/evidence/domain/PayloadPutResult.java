package io.github.candyxi0.hidenest.evidence.domain;

import java.util.UUID;

public record PayloadPutResult(
        UUID payloadId,
        String objectRef,
        long sizeBytes,
        byte[] contentHash,
        String storeAdapter,
        boolean created) {

    public PayloadPutResult {
        if (contentHash != null) {
            contentHash = contentHash.clone();
        }
    }

    /** Defensive copy. */
    @Override
    public byte[] contentHash() {
        return contentHash != null ? contentHash.clone() : null;
    }
}
