package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record AccessPolicy(
        UUID policyId,
        String ownerKind,
        UUID ownerId,
        Long currentRevisionNo,
        OffsetDateTime createdAt) {}
