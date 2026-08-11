package io.github.candyxi0.hidenest.evidence.domain;

public record PayloadHeadResult(long sizeBytes, byte[] contentHash, String contentType) {

    public PayloadHeadResult {
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
