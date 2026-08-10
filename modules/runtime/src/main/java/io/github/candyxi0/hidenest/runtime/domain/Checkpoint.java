package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record Checkpoint(
        UUID checkpointId,
        String runKind,
        UUID runId,
        Long sequenceNo,
        byte[] manifestHash,
        String objectRef,
        OffsetDateTime createdAt) {}
