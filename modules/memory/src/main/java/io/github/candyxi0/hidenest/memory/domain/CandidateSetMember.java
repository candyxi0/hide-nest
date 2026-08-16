package io.github.candyxi0.hidenest.memory.domain;

import java.util.UUID;

public record CandidateSetMember(
        UUID candidateSetId,
        UUID candidateId,
        long ordinal,
        UUID proposalRevisionId,
        UUID decisionId,
        String disposition,
        String action,
        String originKind,
        String finalAuthorKind,
        UUID futureMemoryId,
        UUID targetMemoryId,
        UUID expectedMemoryRevisionId,
        Long expectedPolicyRevisionNo) {}
