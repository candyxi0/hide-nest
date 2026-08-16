package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record CandidateSet(
        UUID candidateSetId,
        UUID reviewSessionId,
        UUID threadId,
        String scopeRef,
        long setVersion,
        byte[] confirmationHash,
        byte[] requestHash,
        OffsetDateTime createdAt,
        OffsetDateTime confirmedAt) {}
