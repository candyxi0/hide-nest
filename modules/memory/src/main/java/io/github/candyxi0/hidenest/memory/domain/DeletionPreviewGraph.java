package io.github.candyxi0.hidenest.memory.domain;

import java.util.List;
import java.util.UUID;

/** Vendor-neutral, body-bearing read projection used only while building S3A. */
public record DeletionPreviewGraph(
        MemoryRecord rootMemory,
        MemoryRevision currentRevision,
        AccessPolicy policy,
        AccessPolicyRevision policyRevision,
        List<MemoryRevision> allRevisions,
        List<Anchor> anchors,
        List<DeletionPreviewAffectedMemory> affectedMemories) {

    public DeletionPreviewGraph {
        allRevisions = List.copyOf(allRevisions);
        anchors = List.copyOf(anchors);
        affectedMemories = List.copyOf(affectedMemories);
    }

    public record Anchor(UUID anchorId, UUID sourceId, List<SourceUnit> sourceUnits) {
        public Anchor {
            sourceUnits = List.copyOf(sourceUnits);
        }
    }

    public record SourceUnit(UUID sourceUnitId, UUID sourceId, List<Payload> payloads) {
        public SourceUnit {
            payloads = List.copyOf(payloads);
        }
    }

    public record Payload(
            UUID payloadId,
            UUID sourceUnitId,
            String payloadKind,
            String contentType,
            String storeAdapter,
            String objectVersionRef,
            String retentionClass,
            Long sizeBytes,
            byte[] contentHash) {
        public Payload {
            contentHash = contentHash == null ? null : contentHash.clone();
        }

        @Override
        public byte[] contentHash() {
            return contentHash == null ? null : contentHash.clone();
        }
    }

    public record DeletionPreviewAffectedMemory(
            MemoryRecord memory,
            MemoryRevision currentRevision,
            int affectedPayloadCount) {}
}
