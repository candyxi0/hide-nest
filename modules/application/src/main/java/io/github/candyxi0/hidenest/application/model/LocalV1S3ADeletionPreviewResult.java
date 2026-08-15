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
        List<LocalV1DeletionEvidenceMessage> evidence,
        List<LocalV1SharedMemoryReference> sharedMemories) {

    public LocalV1S3ADeletionPreviewResult {
        manifestHash = manifestHash.clone();
        deleteCandidateMemoryIds = List.copyOf(deleteCandidateMemoryIds);
        evidence = List.copyOf(evidence);
        sharedMemories = List.copyOf(sharedMemories);
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
