package io.github.candyxi0.hidenest.memory.domain;

import java.util.UUID;

public record CandidateEvidenceMapping(UUID candidateSetId, UUID candidateId, long ordinal, UUID anchorId) {}
