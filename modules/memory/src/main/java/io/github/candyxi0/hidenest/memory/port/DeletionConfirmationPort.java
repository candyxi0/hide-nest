package io.github.candyxi0.hidenest.memory.port;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Database-neutral boundary for the S3B2A confirmation half of a deletion closure. */
public interface DeletionConfirmationPort {

    ConfirmationSnapshot lockConfirmationSnapshot(UUID closureId);

    boolean confirmClosure(
            UUID closureId,
            long expectedPreviewRevision,
            byte[] expectedManifestHash,
            UUID expectedRootCurrentRevisionId,
            long expectedRootRevisionNo,
            UUID expectedRootPolicyId,
            long expectedRootPolicyRevisionNo,
            UUID decisionId,
            OffsetDateTime confirmedAt);

    record ConfirmationSnapshot(
            UUID closureId,
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
            List<Member> members,
            UUID confirmedByDecisionId,
            OffsetDateTime confirmedAt) {
        public ConfirmationSnapshot {
            requestHash = copyHash(requestHash, "requestHash");
            manifestHash = copyHash(manifestHash, "manifestHash");
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

        private static byte[] copyHash(byte[] value, String name) {
            if (value == null || value.length != 32) {
                throw new IllegalArgumentException(name + " must be exactly 32 bytes");
            }
            return value.clone();
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
            if (ordinal < 1) throw new IllegalArgumentException("ordinal must be positive");
            if (memberKind == null || memberKind.isBlank()) throw new IllegalArgumentException("memberKind must not be blank");
            if (targetId == null) throw new IllegalArgumentException("targetId must not be null");
            if (targetRevisionRef != null && targetRevisionRef < 1) {
                throw new IllegalArgumentException("targetRevisionRef must be positive when present");
            }
            if (disposition == null || disposition.isBlank()) throw new IllegalArgumentException("disposition must not be blank");
            contentHash = contentHash == null ? null : contentHash.clone();
        }

        @Override
        public byte[] contentHash() {
            return contentHash == null ? null : contentHash.clone();
        }
    }
}
