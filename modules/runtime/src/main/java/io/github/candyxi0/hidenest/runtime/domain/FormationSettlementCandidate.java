package io.github.candyxi0.hidenest.runtime.domain;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** Body-free settlement envelope. The canonical payload stays in process memory. */
public record FormationSettlementCandidate(
        UUID taskId,
        UUID attemptId,
        UUID sourceId,
        SourceBoundary fromExclusive,
        SourceBoundary toInclusive,
        SourceReadBinding readBinding,
        int schemaVersion,
        UUID idempotencyKey,
        FormationResultKind kind,
        byte[] resultHash,
        boolean complete) {
    public FormationSettlementCandidate {
        Objects.requireNonNull(taskId);
        Objects.requireNonNull(attemptId);
        Objects.requireNonNull(sourceId);
        Objects.requireNonNull(toInclusive);
        Objects.requireNonNull(readBinding);
        Objects.requireNonNull(idempotencyKey);
        Objects.requireNonNull(kind);
        if (resultHash == null || resultHash.length != 32) {
            throw new IllegalArgumentException("resultHash must be SHA-256 length");
        }
        resultHash = Arrays.copyOf(resultHash, resultHash.length);
    }

    @Override
    public byte[] resultHash() {
        return Arrays.copyOf(resultHash, resultHash.length);
    }
}
