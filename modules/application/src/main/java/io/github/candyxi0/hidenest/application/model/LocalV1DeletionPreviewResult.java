package io.github.candyxi0.hidenest.application.model;

import io.github.candyxi0.hidenest.memory.port.DeletionPreviewPort;
import java.util.List;
import java.util.UUID;

/**
 * Transport result of a deletion preview. The closure members come from the persisted preview
 * closure, in ordinal order, and are never recomputed or trimmed by the HTTP layer.
 */
public record LocalV1DeletionPreviewResult(
        UUID previewId,
        long previewRevision,
        byte[] manifestHash,
        List<DeletionPreviewPort.Member> closureMembers) {

    public LocalV1DeletionPreviewResult {
        manifestHash = manifestHash.clone();
        closureMembers = List.copyOf(closureMembers);
    }

    @Override
    public byte[] manifestHash() {
        return manifestHash.clone();
    }
}
