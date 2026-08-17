package io.github.candyxi0.hidenest.application.model;

import java.util.List;
import java.util.UUID;

public record LocalV1CandidateSetResult(
        UUID candidateSetId, UUID reviewSessionId, String status, List<CandidateOutcome> outcomes) {

    public record CandidateOutcome(UUID candidateId, long ordinal, String disposition, String action, UUID futureMemoryId) {}

    public static LocalV1CandidateSetResult noCandidates(UUID candidateSetId) {
        return new LocalV1CandidateSetResult(candidateSetId, null, "NO_CANDIDATES", List.of());
    }
}
