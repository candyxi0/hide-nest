package io.github.candyxi0.hidenest.runtime.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WorkArtifact(
        UUID artifactId,
        UUID runId,
        String artifactKind,
        String objectRef,
        byte[] contentHash,
        OffsetDateTime expiresAt,
        OffsetDateTime createdAt) {}
