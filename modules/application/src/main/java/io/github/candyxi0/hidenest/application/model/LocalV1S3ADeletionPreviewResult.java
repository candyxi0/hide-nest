package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record LocalV1S3ADeletionPreviewResult(
        UUID previewId,
        long previewRevision,
        byte[] manifestHash,
        OffsetDateTime expiresAt,
        UUID rootMemoryId,
        String state,
        long currentRevisionNo,
        long policyRevisionNo,
        String rootBodyPreview,
        List<UUID> deleteCandidateMemoryIds,
        long payloadCount,
        long payloadBytes,
        List<LocalV1S3AAffectedMemory> affectedMemories,
        boolean requiresUserChoice) {

    public LocalV1S3ADeletionPreviewResult {
        manifestHash = manifestHash.clone();
        deleteCandidateMemoryIds = List.copyOf(deleteCandidateMemoryIds);
        affectedMemories = List.copyOf(affectedMemories);
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
