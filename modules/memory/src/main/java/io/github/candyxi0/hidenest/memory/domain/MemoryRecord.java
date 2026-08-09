package io.github.candyxi0.hidenest.memory.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record MemoryRecord(
        UUID memoryId,
        String state,
        UUID currentRevisionId,
        UUID policyId,
        Long currentPolicyRevisionNo,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
