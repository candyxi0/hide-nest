package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Vendor-neutral, body-bearing read projection used only while building S3A. */
public record DeletionPreviewGraph(
        MemoryRecord rootMemory,
        MemoryRevision currentRevision,
        AccessPolicy policy,
        AccessPolicyRevision policyRevision,
        List<MemoryRevision> allRevisions,
        List<Anchor> anchors,
        Set<UUID> sharedAnchorIds,
        Set<UUID> sharedUnitIds,
        Set<UUID> sharedPayloadIds,
        List<EvidenceUnit> evidenceUnits,
        List<SharedMemory> sharedMemories) {

    public DeletionPreviewGraph {
        allRevisions = List.copyOf(allRevisions);
        anchors = List.copyOf(anchors);
        sharedAnchorIds = Set.copyOf(sharedAnchorIds);
        sharedUnitIds = Set.copyOf(sharedUnitIds);
        sharedPayloadIds = Set.copyOf(sharedPayloadIds);
        evidenceUnits = List.copyOf(evidenceUnits);
        sharedMemories = List.copyOf(sharedMemories);
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

    /** Ordered, display-safe evidence metadata for the root memory (body bytes are read later). */
    public record EvidenceUnit(
            UUID anchorId,
            UUID sourceUnitId,
            Long ordinal,
            UUID actorId,
            String actorKind,
            String actorStableRef,
            String displayLabel,
            OffsetDateTime occurredAt,
            String objectRef,
            Long sizeBytes,
            byte[] contentHash,
            List<UUID> sharedByMemoryIds) {
        public EvidenceUnit {
            contentHash = contentHash == null ? null : contentHash.clone();
            sharedByMemoryIds = List.copyOf(sharedByMemoryIds);
        }

        @Override
        public byte[] contentHash() {
            return contentHash == null ? null : contentHash.clone();
        }
    }

    /** A normative memory that legitimately shares evidence with the root target. */
    public record SharedMemory(UUID memoryId, Long revisionNo, String title) {}
}
