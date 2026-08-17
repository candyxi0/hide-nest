package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.UUID;

/**
 * Local V1 CandidateSet CREATE projection result. Stable by member ordinal; every accepted
 * candidate carries its exact {@code futureMemoryId} and, once canonical, its committed revision.
 *
 * <p>No body, vector or hash content is ever carried here. The candidate list is defensively copied
 * on construction so external mutation of the input list cannot change the returned result.</p>
 */
public record LocalV1CandidateSetProjectionResult(
        UUID candidateSetId, String phase, List<CandidateProjection> candidates) {

    public LocalV1CandidateSetProjectionResult {
        candidates = candidates == null ? null : List.copyOf(candidates);
    }

    public record CandidateProjection(
            UUID candidateId,
            long ordinal,
            String disposition,
            String action,
            UUID memoryId,
            UUID memoryRevisionId,
            Long revisionNo,
            String phase) {}
}
