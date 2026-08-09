package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Proposal(
        UUID proposalId,
        String proposalKind,
        UUID targetMemoryId,
        OffsetDateTime createdAt) {}
