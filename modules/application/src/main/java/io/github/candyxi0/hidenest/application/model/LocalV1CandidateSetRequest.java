package io.github.candyxi0.hidenest.application.model;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Final multi-candidate closeout input. One already-confirmed CandidateSet carrying 0..8 atomic
 * candidates, a frozen evidence pool (message pool + anchor definitions), and a single final
 * confirmation. {@code hideReason} is review-only material: it participates in the request hash
 * but never enters normative memory, outbox, receipt, logs or reports.
 *
 * <p>All {@code byte[]} and {@code List} components are defensively copied on construction and
 * {@code byte[]} accessors return a clone, so mutating an array/list after construction (or the
 * returned array) cannot change the request hash or commit semantics.</p>
 */
public record LocalV1CandidateSetRequest(
        UUID candidateSetId,
        String idempotencyKey,
        byte[] requestHash,
        UUID threadId,
        String scopeRef,
        long setVersion,
        FinalConfirmation finalConfirmation,
        EvidencePool evidencePool,
        List<Candidate> candidates) {

    public LocalV1CandidateSetRequest {
        requestHash = requestHash == null ? null : requestHash.clone();
        candidates = candidates == null ? null : List.copyOf(candidates);
    }

    @Override
    public byte[] requestHash() {
        return requestHash == null ? null : requestHash.clone();
    }

    public record FinalConfirmation(String decision, long confirmedSetVersion, byte[] confirmationHash) {
        public FinalConfirmation {
            confirmationHash = confirmationHash == null ? null : confirmationHash.clone();
        }

        @Override
        public byte[] confirmationHash() {
            return confirmationHash == null ? null : confirmationHash.clone();
        }
    }

    public record EvidencePool(List<EvidenceMessage> messages, List<AnchorSpec> anchors) {
        public EvidencePool {
            messages = messages == null ? null : List.copyOf(messages);
            anchors = anchors == null ? null : List.copyOf(anchors);
        }
    }

    public record EvidenceMessage(
            UUID sourceUnitId,
            UUID actorId,
            String speakerRole,
            long ordinal,
            String externalUnitRef,
            OffsetDateTime occurredAt,
            String bodyText,
            byte[] bodyHash) {
        public EvidenceMessage {
            bodyHash = bodyHash == null ? null : bodyHash.clone();
        }

        @Override
        public byte[] bodyHash() {
            return bodyHash == null ? null : bodyHash.clone();
        }
    }

    public record AnchorSpec(UUID anchorId, List<AnchorUnit> units) {
        public AnchorSpec {
            // preserve null elements (so a null unit is rejected by validateStructure as
            // REQUEST_SCHEMA_INVALID, not a construction NPE) while returning an unmodifiable
            // copy so external mutation of the input list or units() cannot change any hash.
            units = units == null ? null : Collections.unmodifiableList(new ArrayList<>(units));
        }
    }

    public record AnchorUnit(UUID sourceUnitId, Long fromOffset, Long toOffset, long ordinal) {}

    public record Candidate(
            UUID candidateId,
            long ordinal,
            String disposition,
            String action,
            String originKind,
            String finalAuthorKind,
            String memoryText,
            String memoryType,
            UUID perspectiveActorId,
            List<UUID> evidenceAnchorIds,
            UUID targetMemoryId,
            UUID expectedMemoryRevisionId,
            Long expectedRevisionNo,
            Long expectedPolicyRevisionNo,
            String hideReason) {
        public Candidate {
            evidenceAnchorIds = evidenceAnchorIds == null ? null : List.copyOf(evidenceAnchorIds);
        }
    }
}
