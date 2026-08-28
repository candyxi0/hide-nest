package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.Objects;

/** Immutable, body-free audit and idempotency fact for one Bubble turn. */
public record BubbleTurnReceipt(
        String spaceKey,
        String roomKey,
        String turnKey,
        byte[] requestHash,
        int queryUtf8Bytes,
        String resultCategory,
        String policyVersion,
        double minScore,
        byte[] resultManifestHash,
        OffsetDateTime issuedAt) {

    public BubbleTurnReceipt {
        spaceKey = Objects.requireNonNull(spaceKey, "spaceKey");
        roomKey = Objects.requireNonNull(roomKey, "roomKey");
        turnKey = Objects.requireNonNull(turnKey, "turnKey");
        requestHash = Objects.requireNonNull(requestHash, "requestHash").clone();
        resultCategory = Objects.requireNonNull(resultCategory, "resultCategory");
        policyVersion = Objects.requireNonNull(policyVersion, "policyVersion");
        resultManifestHash =
                Objects.requireNonNull(resultManifestHash, "resultManifestHash").clone();
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
    }

    @Override
    public byte[] requestHash() {
        return requestHash.clone();
    }

    @Override
    public byte[] resultManifestHash() {
        return resultManifestHash.clone();
    }
}
