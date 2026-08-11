package io.github.candyxi0.hidenest.memory.port;

import io.github.candyxi0.hidenest.memory.domain.DeletionPreviewGraph;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** S3A storage boundary; implementations own locking, graph reads, and atomic inserts. */
public interface DeletionPreviewPort {

    ExistingPreview findByIdempotencyKey(String idempotencyKey);

    DeletionPreviewGraph lockAndReadGraph(UUID rootMemoryId);

    long nextPreviewRevision(UUID rootMemoryId);

    void insertPreview(PreviewDraft draft);

    record ExistingPreview(
            UUID previewId,
            UUID rootMemoryId,
            long previewRevision,
            UUID rootCurrentRevisionId,
            long rootRevisionNo,
            UUID rootPolicyId,
            long rootPolicyRevisionNo,
            byte[] requestHash,
            byte[] manifestHash,
            String state,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            List<Member> members) {
        public ExistingPreview {
            requestHash = requestHash.clone();
            manifestHash = manifestHash.clone();
            members = List.copyOf(members);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public byte[] manifestHash() {
            return manifestHash.clone();
        }
    }

    record PreviewDraft(
            UUID previewId,
            UUID rootMemoryId,
            long previewRevision,
            UUID rootCurrentRevisionId,
            long rootRevisionNo,
            UUID rootPolicyId,
            long rootPolicyRevisionNo,
            String idempotencyKey,
            byte[] requestHash,
            byte[] manifestHash,
            String state,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt,
            List<Member> members) {
        public PreviewDraft {
            requestHash = requestHash.clone();
            manifestHash = manifestHash.clone();
            members = List.copyOf(members);
        }

        @Override
        public byte[] requestHash() {
            return requestHash.clone();
        }

        @Override
        public byte[] manifestHash() {
            return manifestHash.clone();
        }
    }

    record Member(
            long ordinal,
            String memberKind,
            UUID targetId,
            Long targetRevisionRef,
            String disposition,
            Long sizeBytes,
            byte[] contentHash) {
        public Member {
            contentHash = contentHash == null ? null : contentHash.clone();
        }

        @Override
        public byte[] contentHash() {
            return contentHash == null ? null : contentHash.clone();
        }
    }
}
