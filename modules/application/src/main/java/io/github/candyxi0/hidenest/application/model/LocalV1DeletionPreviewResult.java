package io.github.candyxi0.hidenest.application.model;

import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import java.util.List;
import java.util.UUID;

/**
 * Transport result of a deletion preview. The closure members come from the persisted preview
 * closure, in ordinal order, and are never recomputed or trimmed by the HTTP layer. The evidence
 * and shared-memory references are a closed human-readable projection bound to the same preview.
 */
public record LocalV1DeletionPreviewResult(
        UUID previewId,
        long previewRevision,
        byte[] manifestHash,
        List<DeletionPreviewPort.Member> closureMembers,
        List<LocalV1DeletionEvidenceMessage> evidence,
        List<LocalV1SharedMemoryReference> sharedMemories) {

    public LocalV1DeletionPreviewResult {
        manifestHash = manifestHash.clone();
        closureMembers = List.copyOf(closureMembers);
        evidence = List.copyOf(evidence);
        sharedMemories = List.copyOf(sharedMemories);
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
