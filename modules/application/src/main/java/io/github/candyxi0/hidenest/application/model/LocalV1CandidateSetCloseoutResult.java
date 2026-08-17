package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.UUID;

/**
 * Local V1 CandidateSet HTTP closeout result. Carries the overall phase and per-candidate
 * projection items; no body, hash, vector, hideReason or internal ID content is ever carried here.
 */
public record LocalV1CandidateSetCloseoutResult(
        UUID candidateSetId,
        UUID reviewSessionId,
        String phase,
        String resultCategory,
        List<CandidateCloseoutItem> candidates) {

    public LocalV1CandidateSetCloseoutResult {
        candidates = candidates == null ? null : List.copyOf(candidates);
    }

    public record CandidateCloseoutItem(
            UUID candidateId,
            long ordinal,
            String disposition,
            String action,
            String phase,
            UUID memoryId,
            UUID memoryRevisionId,
            Long revisionNo) {}
}